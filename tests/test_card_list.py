"""The external card list (mtg_card_scanner/card_list.py): name matching, the
list contract, the fetch + cache rules, and list_tags on every scan read."""
import copy
import json

from fastapi.testclient import TestClient

from mtg_card_scanner.card_list import (CardListSource, flag_list_tags, name_keys, normalize_name,
                                        parse_list, scan_card_name, tags_for)
from server.app import create_app
from server.store import ScanStore

LIST = json.dumps({"generated_at": "2026-10-12T09:00:00Z", "extra": 1, "cards": [
    {"name": "Lightning Bolt", "tags": ["wanted"]},
    {"name": "Jötun Grunt", "tags": ["sold_out", "wanted"]},
    {"name": "Fire // Ice", "tags": ["wanted"]},
    {"name": "Delver of Secrets", "tags": ["vip", "wanted", "vip"], "qty": 4},
    {"name": "Lightning Bolt", "tags": ["sold_out"]},
    {"name": "", "tags": ["wanted"]},
    {"tags": ["wanted"]},
    "junk",
]})


def test_normalize_name():
    assert normalize_name("  Jötun   Grunt ") == "jotun grunt"
    assert normalize_name("Lim-Dûl’s Vault") == "lim-dul's vault"
    assert normalize_name("“Ach! Hans, Run!”") == '"ach! hans, run!"'
    assert normalize_name("Fire//Ice") == "fire // ice"
    assert normalize_name("Fire  //   Ice") == "fire // ice"
    assert normalize_name("Ætherize") == "ætherize"            # a letter, not an accent
    assert normalize_name("Smith – Steel — Hammer") == "smith - steel - hammer"
    assert normalize_name("A B") == "a b"


def test_name_keys_are_the_full_name_then_each_face():
    assert name_keys("Delver of Secrets // Insectile Aberration") == [
        "delver of secrets // insectile aberration", "delver of secrets", "insectile aberration"]
    assert name_keys("Opt") == ["opt"]
    assert name_keys("  ") == []


def test_parse_list_keeps_good_entries_and_merges_tags():
    lst = parse_list(LIST)
    assert lst["count"] == 5 and lst["generated_at"] == "2026-10-12T09:00:00Z"
    assert lst["index"]["lightning bolt"] == ["wanted", "sold_out"]
    assert lst["index"]["delver of secrets"] == ["vip", "wanted"]
    assert lst["index"]["ice"] == ["wanted"]


def test_parse_list_rejects_what_is_not_a_list():
    for text, why in (("{not json", "not JSON"), ("", "not JSON"), ("[]", "no cards list"),
                      ('{"cards": {}}', "no cards list")):
        try:
            parse_list(text)
            raise AssertionError(text)
        except ValueError as e:
            assert str(e) == why


def test_tags_match_full_name_or_any_face_known_tags_first():
    idx = parse_list(LIST)["index"]
    assert tags_for("Lightning Bolt", idx) == ["wanted", "sold_out"]
    assert tags_for("JOTUN GRUNT", idx) == ["wanted", "sold_out"]
    assert tags_for("Fire // Ice", idx) == ["wanted"]
    assert tags_for("Ice", idx) == ["wanted"]
    assert tags_for("Delver of Secrets // Insectile Aberration", idx) == ["wanted", "vip"]
    assert tags_for("Opt", idx) == [] and tags_for(None, idx) == []


def test_scan_name_is_the_pick_then_the_top_candidate_then_the_read():
    assert scan_card_name({"selection": {"name": "A"}, "candidates": [{"name": "B"}]}) == "A"
    assert scan_card_name({"selection": None, "candidates": [{"name": "B"}], "card_read": {"name": "C"}}) == "B"
    assert scan_card_name({"candidates": [], "card_read": {"name": "C"}}) == "C"
    assert scan_card_name({"candidates": [], "card_read": {}}) is None


def test_feature_off_adds_no_key():
    scan = {"candidates": [{"name": "Lightning Bolt"}]}
    assert "list_tags" not in flag_list_tags(copy.deepcopy(scan), None)
    assert flag_list_tags(scan, parse_list(LIST))["list_tags"] == ["wanted", "sold_out"]


class Fetch:
    def __init__(self, *answers):
        self.answers, self.urls = list(answers), []

    def __call__(self, url):
        self.urls.append(url)
        a = self.answers.pop(0)
        if a == "down":
            raise OSError("no route")
        return a


def clock():
    clock.n += 1
    return f"C{clock.n}"
clock.n = 0


def test_source_keeps_the_last_good_list_on_any_error(tmp_path):
    f = Fetch((200, LIST), "down", (500, "oops"), (200, "{bad"), (200, '{"cards": []}'))
    src = CardListSource("https://x/list?key=s3cret", tmp_path / "c.json", f, clock)
    assert src.current()["count"] == 0 and src.status()["updated_at"] is None   # on, nothing yet
    assert src.refresh() is True
    for reason in ("unreachable", "HTTP 500", "not JSON"):
        assert src.refresh() is False
        st = src.status()
        assert st["error"] == reason and st["count"] == 5          # still the cache
    assert f.urls == ["https://x/list?key=s3cret"] * 4               # exactly as entered
    assert "s3cret" not in json.dumps(src.status())                 # never the URL
    assert src.refresh() is True and src.status()["count"] == 0 and src.status()["error"] is None


def test_cache_survives_a_restart_for_the_same_url_only(tmp_path):
    src = CardListSource("https://x/a", tmp_path / "c.json", Fetch((200, LIST)), clock)
    src.refresh()
    again = CardListSource("https://x/a", tmp_path / "c.json", Fetch(), clock)
    assert again.current()["count"] == 5 and again.status()["updated_at"] == src.status()["updated_at"]
    other = CardListSource("https://x/b", tmp_path / "c.json", Fetch(), clock)
    assert other.current()["count"] == 0
    off = CardListSource("", tmp_path / "c.json", Fetch(), clock)
    assert off.current() is None and off.refresh() is False and off.status()["enabled"] is False


def test_scan_reads_carry_list_tags_evaluated_at_read_time(tmp_path):
    store = ScanStore(tmp_path / "s.db")
    f = Fetch((200, LIST), (200, json.dumps({"cards": [{"name": "Opt", "tags": ["wanted"]}]})))
    src = CardListSource("", tmp_path / "c.json", f, clock)
    app = create_app(pipeline_factory=lambda: None, store=store, f2f=object(),
                     scan_images_dir=tmp_path / "img", auto_sweep_interval=None, card_list=src)
    c = TestClient(app)
    store.create_scan(identified=True, card_read={"name": "Lightning Bolt"}, confidence={},
                      candidates=[{"id": "b1", "name": "Lightning Bolt", "set": "m10", "collector_number": "146"}])
    assert "list_tags" not in c.get("/api/scans").json()[0]           # off: no key at all
    src.set_url("https://x/list"); src.refresh()
    assert c.get("/api/scans").json()[0]["list_tags"] == ["wanted", "sold_out"]
    assert c.get("/api/scans/1").json()["list_tags"] == ["wanted", "sold_out"]
    src.refresh()                                                     # a new list re-tags old scans
    assert c.get("/api/scans/1").json()["list_tags"] == []
    store.close()
