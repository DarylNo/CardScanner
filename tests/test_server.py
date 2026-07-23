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
                     scan_images_dir=tmp_path / "scan_images")
    return TestClient(app)


def _jpeg_bytes():
    img = np.zeros((40, 30, 3), dtype=np.uint8)
    ok, buf = cv2.imencode(".jpg", img)
    assert ok
    return buf.tobytes()


def test_health(client):
    assert client.get("/api/health").json() == {"ok": True}


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
