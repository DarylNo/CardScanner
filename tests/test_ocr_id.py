"""Tests for the collector-line OCR matcher (pure logic — no OCR engine)."""

from mtg_card_scanner.ocr_id import _canon, canon_words, match_printing


def _c(sid, set_code, num):
    return {"id": sid, "set": set_code, "collector_number": num}


CANDS = [
    _c("a25", "a25", "85"),
    _c("plst", "plst", "A25-85"),
    _c("mh1", "mh1", "87"),
    _c("j22", "j22", "67"),
    _c("tmp", "tmp", "128"),
    _c("pmei-a", "pmei", "2019-2"),
    _c("pmei-b", "pmei", "2024-5"),
]


def test_canon_collapses_ocr_confusions():
    assert _canon("mhI+ Fn") == _canon("MH1 FN")
    assert _canon("2S4") == _canon("254")


def test_real_scan_blob_matches_mh1():
    # The live Diabolic Edict scan (id 837) read "0017314MH1FN" — stored run
    # together (canonical), from the card's two printed lines "087/254 C" and
    # "MH1 • EN". Its words: the number (garbled — hence NOT required) and
    # the code line.
    assert match_printing("0017314 MH1FN", CANDS) == "mh1"


def test_a_set_code_must_be_a_whole_word():
    # 2026-09-29: an AVR Emancipation Angel — old frame, NO set code printed —
    # "confirmed" as UMA #15 from letters that met across words once the
    # spaces were stripped. Only the code as a WHOLE word counts (a glued-on
    # language tag allowed).
    angel = [_c("avr", "avr", "19"), _c("uma", "uma", "15")]
    assert match_printing("19/244 Illus. Scott Chou", angel) is None
    assert match_printing("You MAy rest 19/244", angel) is None     # yoU MAy
    assert match_printing("CHOUMA 19/244", angel) is None           # inside a word
    assert match_printing("015/254 R UMA • EN Scott Chou", angel) == "uma"
    assert match_printing("015/254 R UMA•EN", angel) == "uma"       # "UMAEN"
    assert match_printing("R 0015 umaen", angel) == "uma"
    assert match_printing("R 0015 UMAX", angel) is None                # not a language tag


def test_an_artist_name_is_never_a_set_code():
    # MSCHF reads "M5CHF" (starts with MSC); Milivoj reads "M111V0J" (M11).
    sld = [_c("slz", "slz", "63"), _c("msc", "msc", "806"), _c("m11", "m11", "149")]
    assert match_printing("C 0063 SLZ • EN Illus. MSCHF", sld) == "slz"
    assert match_printing("Illus. Milivoj Ćeran", sld) is None
    assert match_printing("Illus. MSCHF", sld) is None


def test_canon_words_keeps_word_boundaries():
    assert canon_words("087/254 R\nmh1 • En") == "087 254 R MH1 EN"
    assert canon_words("  ") == ""
    assert canon_words(canon_words("a25-85 PW")) == canon_words("a25-85 PW") == "A25 85 PW"


def test_no_line_no_match():
    assert match_printing("", CANDS) is None
    assert match_printing("XYZQQQ", CANDS) is None


def test_ambiguous_set_hits_never_guess():
    # both PMEI promos share a set code; without a collector hit → None
    assert match_printing("PMEI", CANDS) is None


def test_ambiguous_resolved_by_collector():
    assert match_printing("PMEI 20192", CANDS) == "pmei-a"


def test_list_copy_wins_over_original_set():
    # a List card prints "A25-85": compound collector beats the bare set hit
    assert match_printing("A25-85 PW", CANDS) == "plst"


def test_art_double_check_blocks_disagreeing_promotions():
    """A misread set code naming an alt-art printing must be ignored: the
    OCR'd candidate's art distance has to sit within the same-art band."""
    from mtg_card_scanner.pipeline import _art_agrees
    cands = [{"id": "a", "multi_distance": 124},
             {"id": "b", "multi_distance": 156},
             {"id": "c", "multi_distance": 208}]
    assert _art_agrees(cands[0], cands) is True    # the best itself
    assert _art_agrees(cands[1], cands) is True    # same-art band (+32)
    assert _art_agrees(cands[2], cands) is False   # alt-art (+84) — blocked
    assert _art_agrees({"id": "d"}, cands) is True # no art data — don't block


def test_ocr_status_reports_a_missing_engine(monkeypatch):
    # A missing engine used to look exactly like "read nothing" — every
    # printing silently unconfirmed. ocr_status() makes it visible WITHOUT
    # importing the engine (the ONNX runtime aborts the process at exit).
    import importlib.util
    from mtg_card_scanner import ocr_id
    monkeypatch.setattr(ocr_id, "_ocr_status", None)
    monkeypatch.setattr(importlib.util, "find_spec", lambda name: None)
    st = ocr_id.ocr_status()
    assert st["available"] is False and "rapidocr_onnxruntime" in st["error"]
    assert ocr_id.ocr_status() is st                     # cached


def test_an_engine_that_fails_to_load_is_reported(monkeypatch):
    # Installed but broken (e.g. an onnxruntime DLL failure on Windows): the
    # first read records it, so /api/health stops claiming OCR works.
    import builtins
    import numpy as np
    from mtg_card_scanner import ocr_id
    real_import = builtins.__import__

    def fake_import(name, *a, **k):
        if name == "rapidocr_onnxruntime":
            raise OSError("DLL load failed while importing onnxruntime_pybind11_state")
        return real_import(name, *a, **k)

    monkeypatch.setattr(ocr_id, "_ocr_status", {"available": True, "error": None})
    monkeypatch.setattr(ocr_id, "_ocr_engine", None)
    monkeypatch.setattr(builtins, "__import__", fake_import)
    assert ocr_id.read_bottom_strip(np.zeros((880, 630, 3), np.uint8)) == ""
    assert ocr_id.ocr_status()["available"] is False
    assert "DLL load failed" in ocr_id.ocr_status()["error"]
