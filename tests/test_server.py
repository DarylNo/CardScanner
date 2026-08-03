"""End-to-end API tests using a fake pipeline + fake F2F (no camera or art index)."""

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient

from server.app import create_app
from server.store import ScanStore
from mtg_card_scanner.facetoface import F2FPrice


CANDIDATES = [
    {"id": "id-m10", "name": "Lightning Bolt", "set": "m10", "set_name": "Magic 2010",
     "collector_number": "146", "finishes": ["nonfoil"], "image_normal": "http://img/n.jpg",
     "phash_distance": 0},
    {"id": "id-m11", "name": "Lightning Bolt", "set": "m11", "set_name": "Magic 2011",
     "collector_number": "149", "finishes": ["nonfoil"], "image_normal": "http://img/n2.jpg",
     "phash_distance": 6},
]


class FakePipeline:
    def scan_candidates(self, frames, top_n=12):
        return {
            "identified": True,
            "card_read": {"name": "Lightning Bolt", "set_code": "m10",
                          "collector_number": "146", "foil": False, "language": "en",
                          "condition_estimate": "NM", "condition_reason": "", "artist": "",
                          "is_old_card": False},
            "confidence": {"name": "high", "set": "high", "collector": "high"},
            "candidates": CANDIDATES,
            "error": None,
        }

    def search_candidates(self, name, top_n=40):
        return CANDIDATES


class FakeF2F:
    def get_price(self, name, set_code, collector_number, foil=False, set_name=""):
        return F2FPrice(name=name, set_code=set_code, collector_number=collector_number,
                        foil=foil, handle="h", url="http://f2f/h",
                        conditions={"NM": 3.49, "PL": 2.79})


@pytest.fixture
def client(tmp_path):
    app = create_app(pipeline_factory=lambda: FakePipeline(),
                     store=ScanStore(tmp_path / "s.db"), f2f=FakeF2F(),
                     scan_images_dir=tmp_path / "scan_images",
                     auto_sweep_interval=None)
    return TestClient(app)


def _jpeg_bytes():
    img = np.zeros((40, 30, 3), dtype=np.uint8)
    ok, buf = cv2.imencode(".jpg", img)
    assert ok
    return buf.tobytes()


def test_health(client):
    body = client.get("/api/health").json()
    assert body["ok"] is True
    assert body["version"]          # non-empty — git hash or "unknown"


def test_version_endpoint(client):
    assert client.get("/api/version").json()["version"]


def test_html_pages_are_never_cached(client):
    """A stale cached phone.html hid a fix once — both pages must say no-store."""
    for path in ("/", "/phone"):
        assert client.get(path).headers["cache-control"] == "no-store"


def test_scan_creates_candidates(client):
    r = client.post("/api/scan", files={"files": ("card.jpg", _jpeg_bytes(), "image/jpeg")})
    assert r.status_code == 200
    scan = r.json()
    assert scan["identified"] is True
    assert scan["status"] == "candidates"
    assert len(scan["candidates"]) == 2
    assert scan["candidates"][0]["set"] == "m10"


def test_scan_rejects_non_image(client):
    r = client.post("/api/scan", files={"files": ("x.txt", b"not an image", "text/plain")})
    assert r.status_code == 400


def test_full_flow_select_price_export(client):
    scan = client.post("/api/scan",
                       files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    sid = scan["id"]

    # select a printing
    r = client.post(f"/api/scans/{sid}/select", json={
        "printing": CANDIDATES[1], "condition": "LP", "finish": "Non-Foil", "quantity": 3,
    })
    assert r.status_code == 200
    sel = r.json()
    assert sel["status"] == "selected"
    assert sel["selection"]["set"] == "m11"
    assert sel["selection"]["quantity"] == 3
    assert sel["f2f"]["conditions"]["NM"] == 3.49

    # export contains the selected card in MX mass-entry format
    text = client.get("/api/export").text
    assert "3 M11 149 LP Non-Foil" in text

    # exclude it -> export empties
    client.patch(f"/api/scans/{sid}", json={"included": False})
    assert client.get("/api/export").text.strip() == ""


def test_select_requires_set_and_collector(client):
    scan = client.post("/api/scan",
                       files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    r = client.post(f"/api/scans/{scan['id']}/select", json={"printing": {"name": "x"}})
    assert r.status_code == 400


def test_patch_quantity_and_condition(client):
    scan = client.post("/api/scan",
                       files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    sid = scan["id"]
    client.post(f"/api/scans/{sid}/select", json={"printing": CANDIDATES[0]})
    r = client.patch(f"/api/scans/{sid}", json={"quantity": 5, "condition": "mp"})
    body = r.json()
    assert body["selection"]["quantity"] == 5
    assert body["selection"]["condition"] == "MP"


def test_search_endpoint(client):
    r = client.get("/api/search", params={"q": "Lightning Bolt"})
    assert r.status_code == 200
    assert len(r.json()["candidates"]) == 2


def test_delete_scan(client):
    scan = client.post("/api/scan",
                       files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    assert client.delete(f"/api/scans/{scan['id']}").json() == {"deleted": True}
    assert client.get(f"/api/scans/{scan['id']}").status_code == 404


def test_scan_image_saved_served_and_deleted(client):
    scan = client.post(
        "/api/scan", files={"files": ("card.jpg", _jpeg_bytes(), "image/jpeg")}
    ).json()
    sid = scan["id"]

    r = client.get(f"/api/scans/{sid}/image")
    assert r.status_code == 200
    assert r.headers["content-type"] == "image/jpeg"
    assert len(r.content) > 100  # a real JPEG, not an error body

    client.delete(f"/api/scans/{sid}")
    assert client.get(f"/api/scans/{sid}/image").status_code == 404


def test_scan_image_404_for_unknown_scan(client):
    assert client.get("/api/scans/9999/image").status_code == 404


class NoCardPipeline:
    def scan_candidates(self, frames, top_n=12):
        return {"identified": False, "no_card": True, "card_read": {},
                "confidence": {"name": "low", "set": "low", "collector": "low"},
                "candidates": [], "error": "No card detected (best art score 240)."}

    def search_candidates(self, name, top_n=40):
        return []


def test_no_card_scan_is_not_stored(tmp_path):
    store = ScanStore(tmp_path / "s.db")
    app = create_app(pipeline_factory=lambda: NoCardPipeline(), store=store,
                     f2f=FakeF2F(), scan_images_dir=tmp_path / "imgs")
    client = TestClient(app)
    r = client.post("/api/scan", files={"files": ("card.jpg", _jpeg_bytes(), "image/jpeg")})
    assert r.status_code == 200
    assert r.json()["no_card"] is True
    assert client.get("/api/scans").json() == []          # nothing persisted


def test_scan_background_prices_top_candidate(client):
    scan = client.post(
        "/api/scan", files={"files": ("card.jpg", _jpeg_bytes(), "image/jpeg")}
    ).json()
    # TestClient runs background tasks before returning — the pending scan
    # should now carry the top candidate's F2F conditions.
    stored = client.get(f"/api/scans/{scan['id']}").json()
    assert stored["status"] == "candidates"               # still unselected
    assert stored["f2f"]["conditions"] == {"NM": 3.49, "PL": 2.79}


def test_delete_all_scans(client):
    for _ in range(3):
        client.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")})
    assert len(client.get("/api/scans").json()) == 3

    r = client.post("/api/scans/delete-all", json={})
    assert r.json()["deleted"] == 3
    assert client.get("/api/scans").json() == []


def test_delete_all_can_keep_selected(client):
    ids = [client.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()["id"]
           for _ in range(3)]
    client.post(f"/api/scans/{ids[0]}/select",
                json={"printing": CANDIDATES[0], "condition": "NM",
                      "finish": "Non-Foil", "quantity": 1})

    r = client.post("/api/scans/delete-all", json={"only": "unselected"})
    assert r.json()["deleted"] == 2
    left = client.get("/api/scans").json()
    assert [s["id"] for s in left] == [ids[0]]
    assert left[0]["status"] == "selected"


class FlakyF2F:
    """No prices until enabled — creates unpriced scans, then priceable ones."""

    def __init__(self):
        self.enabled = False
        self.calls = 0

    def get_price(self, name, set_code, collector_number, foil=False, set_name=""):
        self.calls += 1
        if not self.enabled:
            return None
        return F2FPrice(name=name, set_code=set_code, collector_number=collector_number,
                        foil=foil, handle="h", url="http://f2f/h",
                        conditions={"NM": 1.99})


def test_select_merges_duplicate_printings(client):
    """Selecting the exact printing+condition+finish an existing selected row
    holds folds the new scan into it as quantity instead of a duplicate row."""
    a = client.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    b = client.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    client.post(f"/api/scans/{a['id']}/select",
                json={"printing": CANDIDATES[0], "condition": "NM",
                      "finish": "Non-Foil", "quantity": 1})
    r = client.post(f"/api/scans/{b['id']}/select",
                    json={"printing": CANDIDATES[0], "condition": "NM",
                          "finish": "Non-Foil", "quantity": 1}).json()
    assert r["merged_into"] == a["id"]
    assert r["selection"]["quantity"] == 2
    scans = client.get("/api/scans").json()
    assert [s["id"] for s in scans] == [a["id"]]      # duplicate row is gone
    assert client.get(f"/api/scans/{b['id']}/image").status_code == 404


def test_select_does_not_merge_different_condition(client):
    a = client.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    b = client.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    client.post(f"/api/scans/{a['id']}/select",
                json={"printing": CANDIDATES[0], "condition": "NM",
                      "finish": "Non-Foil", "quantity": 1})
    r = client.post(f"/api/scans/{b['id']}/select",
                    json={"printing": CANDIDATES[0], "condition": "LP",
                          "finish": "Non-Foil", "quantity": 1}).json()
    assert "merged_into" not in r
    assert len(client.get("/api/scans").json()) == 2


def test_price_now_endpoint(tmp_path):
    """On-demand pricing: prices the scan, and a failed search returns a
    transient not_found flag WITHOUT clearing an existing price."""
    f2f = FlakyF2F()
    app = create_app(pipeline_factory=lambda: FakePipeline(),
                     store=ScanStore(tmp_path / "s.db"), f2f=f2f,
                     scan_images_dir=tmp_path / "imgs")
    c = TestClient(app)
    sid = c.post("/api/scan",
                 files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()["id"]

    # Search finds nothing → flag, no stored price.
    r = c.post(f"/api/scans/{sid}/price").json()
    assert r["f2f_search"] == "not_found"
    assert not c.get(f"/api/scans/{sid}").json().get("f2f")

    # Search succeeds → price stored on the scan.
    f2f.enabled = True
    r = c.post(f"/api/scans/{sid}/price").json()
    assert r["f2f"]["conditions"] == {"NM": 1.99}

    # A later failed search must NOT clear the stored price.
    f2f.enabled = False
    r = c.post(f"/api/scans/{sid}/price").json()
    assert r["f2f_search"] == "not_found"
    assert c.get(f"/api/scans/{sid}").json()["f2f"]["conditions"] == {"NM": 1.99}

    assert c.post("/api/scans/9999/price").status_code == 404


def test_price_status_endpoint(client):
    st = client.get("/api/price-status").json()
    assert st["active"] is False
    assert st["done"] == 0 and st["total"] == 0
    assert st["current"] == ""


def test_price_missing_prices_every_print_of_unpicked(tmp_path):
    """Unpicked scans get EVERY candidate print priced (so the price filter
    can hide them only on full knowledge); completed searches — even empty
    ones — are never re-queued."""
    f2f = FlakyF2F()
    app = create_app(pipeline_factory=lambda: FakePipeline(),
                     store=ScanStore(tmp_path / "s.db"), f2f=f2f,
                     scan_images_dir=tmp_path / "imgs",
                     auto_sweep_interval=None)
    c = TestClient(app)
    for _ in range(3):
        c.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")})

    f2f.enabled = True
    r = c.post("/api/scans/price-missing")
    assert r.json()["queued"] == 6              # 3 pending scans × 2 prints
    for s in c.get("/api/scans").json():        # background runs inline
        assert all(cc["f2f_conditions"] == {"NM": 1.99} for cc in s["candidates"])

    calls_before = f2f.calls
    assert c.post("/api/scans/price-missing").json()["queued"] == 0
    assert f2f.calls == calls_before


def test_failed_print_search_is_not_requeued(tmp_path):
    """A search that finds no listing records an empty result — the sweeper
    must not hammer F2F for the same unlisted prints every minute."""
    f2f = FlakyF2F()                            # disabled → all searches miss
    app = create_app(pipeline_factory=lambda: FakePipeline(),
                     store=ScanStore(tmp_path / "s.db"), f2f=f2f,
                     scan_images_dir=tmp_path / "imgs",
                     auto_sweep_interval=None)
    c = TestClient(app)
    c.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")})
    assert c.post("/api/scans/price-missing").json()["queued"] == 2
    (scan,) = c.get("/api/scans").json()
    assert all(cc["f2f_conditions"] == {} for cc in scan["candidates"])
    assert c.post("/api/scans/price-missing").json()["queued"] == 0


class SingleCandPipeline(FakePipeline):
    def scan_candidates(self, frames, top_n=12):
        out = dict(super().scan_candidates(frames, top_n))
        out["candidates"] = [CANDIDATES[0]]
        return out


def test_single_printing_auto_picks_with_flag(tmp_path):
    """One printing → nothing to choose: auto-selected NM/Non-Foil ×1 with
    auto_picked set; a second copy folds in as quantity via auto-merge."""
    app = create_app(pipeline_factory=lambda: SingleCandPipeline(),
                     store=ScanStore(tmp_path / "s.db"), f2f=FakeF2F(),
                     scan_images_dir=tmp_path / "imgs",
                     auto_sweep_interval=None)
    c = TestClient(app)
    first = c.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    assert first["status"] == "selected"
    assert first["selection"]["auto_picked"] is True
    assert first["selection"]["scryfall_id"] == "id-m10"

    second = c.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    assert second["merged_into"] == first["id"]
    assert second["selection"]["quantity"] == 2
    assert [s["id"] for s in c.get("/api/scans").json()] == [first["id"]]


def test_sweep_stop_endpoint_when_idle(client):
    assert client.post("/api/price-sweep/stop").json() == {"stopping": False}


def test_delete_all_removes_scan_images(client, tmp_path):
    scan = client.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    assert client.get(f"/api/scans/{scan['id']}/image").status_code == 200
    client.post("/api/scans/delete-all", json={})
    assert client.get(f"/api/scans/{scan['id']}/image").status_code == 404


def test_setup_status_and_qr(client):
    st = client.get("/api/setup/status").json()
    assert set(st) >= {"index_built", "indexed", "total", "building", "error"}
    assert st["building"] is False
    qr = client.get("/api/phone-qr")
    assert qr.status_code == 200
    assert qr.headers["content-type"].startswith("image/svg")


class ThrottledF2F:
    """Every call fails as storefront-unavailable (simulates a 429 storm)."""
    def __init__(self):
        self.calls = 0

    def get_price(self, *a, **k):
        from mtg_card_scanner.facetoface import F2FUnavailableError
        self.calls += 1
        raise F2FUnavailableError("throttled")


def test_sweep_circuit_breaker_aborts_and_cools_down(tmp_path):
    """Five consecutive unavailable targets abort the sweep and start a
    cooldown — retrying the whole failing list every 60s kept the
    storefront's limiter permanently tripped. Nothing gets recorded."""
    f2f = ThrottledF2F()
    app = create_app(pipeline_factory=lambda: FakePipeline(),
                     store=ScanStore(tmp_path / "s.db"), f2f=f2f,
                     scan_images_dir=tmp_path / "imgs",
                     auto_sweep_interval=None)
    c = TestClient(app)
    for _ in range(4):                            # 4 scans × 2 prints = 8 targets
        c.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")})
    calls_before_sweep = f2f.calls                # scan-time _price_top attempts

    assert c.post("/api/scans/price-missing").json()["queued"] == 8
    st = c.get("/api/price-status").json()
    assert st["active"] is False
    assert st["cooldown_s"] > 0                   # breaker tripped
    assert f2f.calls - calls_before_sweep == 5    # aborted after 5, not 8
    for s in c.get("/api/scans").json():          # nothing falsely recorded
        assert all(cc.get("f2f_conditions") is None for cc in s["candidates"])


def test_selected_scan_queues_only_its_selection(tmp_path):
    """Once a print is selected (auto or user), that selection is the ONLY
    print the sweeper queues — never the other candidates."""
    f2f = FlakyF2F()                              # select-time pricing finds nothing
    app = create_app(pipeline_factory=lambda: FakePipeline(),
                     store=ScanStore(tmp_path / "s.db"), f2f=f2f,
                     scan_images_dir=tmp_path / "imgs",
                     auto_sweep_interval=None)
    c = TestClient(app)
    sid = c.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()["id"]
    c.post(f"/api/scans/{sid}/select",
           json={"printing": CANDIDATES[0], "condition": "NM",
                 "finish": "Non-Foil", "quantity": 1})

    f2f.enabled = True
    r = c.post("/api/scans/price-missing").json()
    assert r["queued"] == 1                       # the selection — not 2 prints
    scan = c.get(f"/api/scans/{sid}").json()
    assert scan["f2f"]["conditions"] == {"NM": 1.99}
    assert all(cc.get("f2f_conditions") is None for cc in scan["candidates"])


def test_price_debug_endpoint(client):
    d = client.get("/api/price-debug").json()
    assert "events" in d and isinstance(d["events"], list)
    assert "pace_s" in d


def test_single_printing_auto_pick_gets_priced_immediately(tmp_path):
    """One printing -> auto-pick AND an immediate exact-printing price, not a
    wait for the sweep."""
    app = create_app(pipeline_factory=lambda: SingleCandPipeline(),
                     store=ScanStore(tmp_path / "s.db"), f2f=FakeF2F(),
                     scan_images_dir=tmp_path / "imgs",
                     auto_sweep_interval=None)
    c = TestClient(app)
    sid = c.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()["id"]
    scan = c.get(f"/api/scans/{sid}").json()          # bg tasks ran inline
    assert scan["selection"]["auto_picked"] is True
    assert scan["f2f"]["conditions"] == {"NM": 3.49, "PL": 2.79}


def test_update_check_endpoint_shape(client):
    u = client.get("/api/update-check").json()
    assert set(u) >= {"current", "latest", "update_available",
                      "can_self_update", "download_url"}
    assert u["can_self_update"] is True      # tests never run frozen


class OcrConfirmedPipeline(FakePipeline):
    def scan_candidates(self, frames, top_n=12):
        out = dict(super().scan_candidates(frames, top_n))
        cands = [dict(c) for c in out["candidates"]]
        cands[0]["ocr_confirmed"] = True     # collector line named this print
        out["candidates"] = cands
        return out


def test_ocr_confirmed_top_candidate_auto_picks(tmp_path):
    """Reading the printing off the card beats any art delta — an OCR-
    confirmed top candidate auto-files like a single-printing card."""
    app = create_app(pipeline_factory=lambda: OcrConfirmedPipeline(),
                     store=ScanStore(tmp_path / "s.db"), f2f=FakeF2F(),
                     scan_images_dir=tmp_path / "imgs",
                     auto_sweep_interval=None)
    c = TestClient(app)
    scan = c.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    assert scan["status"] == "selected"
    assert scan["selection"]["auto_picked"] is True
    assert scan["selection"]["scryfall_id"] == "id-m10"

    # unconfirmed multi-candidate scans still wait for a human
    app2 = create_app(pipeline_factory=lambda: FakePipeline(),
                      store=ScanStore(tmp_path / "s2.db"), f2f=FakeF2F(),
                      scan_images_dir=tmp_path / "imgs2",
                      auto_sweep_interval=None)
    c2 = TestClient(app2)
    scan2 = c2.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()
    assert scan2["status"] == "candidates"


def test_version_reports_lan_ip(client):
    d = client.get("/api/version").json()
    assert "lan_ip" in d and "is_lan" in d
    assert isinstance(d["is_lan"], bool)


def test_phone_qr_accepts_ip_override(client):
    r = client.get("/api/phone-qr", params={"ip": "192.168.1.73"})
    assert r.status_code == 200
    assert b"svg" in r.content[:200].lower()


def test_phone_qr_rejects_garbage_ip(client):
    # Non-numeric override falls back to detection rather than encoding junk.
    r = client.get("/api/phone-qr", params={"ip": "evil.example.com"})
    assert r.status_code == 200
