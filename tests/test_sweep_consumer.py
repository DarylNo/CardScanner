"""
ONE F2F consumer, under real concurrency (CLAUDE.md "Pricing sweep").

These started life as an adversarial review's proofs against the walk-around
price check (POST /api/scans/{id}/price-check) and now pin the fixes:
  - a sweep ending must not clobber the sweep claimed right after it;
  - a price check must not lift the breaker's cooldown for everything else;
  - Stop must not strand price checks queued into the running sweep;
  - scan-time pricing and a price check must not fetch in parallel.
"""

import threading
import time

from fastapi.testclient import TestClient

from server.app import create_app
from server.store import ScanStore
from tests.test_server import FakeF2F, NumberedPipeline, SingleCandPipeline, _jpeg_bytes


class CountF2F(FakeF2F):
    """Records calls and the max number of fetches in flight; can hold one."""

    def __init__(self, hold=None):
        self.calls, self.lock = [], threading.Lock()
        self.inflight = self.max_inflight = 0
        self.hold, self.gate, self.entered = hold, threading.Event(), threading.Event()

    def get_price(self, name, set_code, cn, foil=False, set_name=""):
        with self.lock:
            self.calls.append(cn)
            self.inflight += 1
            self.max_inflight = max(self.max_inflight, self.inflight)
        try:
            if self.hold and self.hold(cn):
                self.entered.set()
                self.gate.wait(10)
            return super().get_price(name, set_code, cn, foil, set_name)
        finally:
            with self.lock:
                self.inflight -= 1


class HookLock:
    """Wraps sweep_lock to run a callback right after every release."""

    def __init__(self, inner, on_release):
        self.inner, self.on_release = inner, on_release

    def __enter__(self):
        self.inner.acquire()
        return self

    def __exit__(self, *a):
        self.inner.release()
        self.on_release()


def _cell(app, fn_name, var):
    for r in app.routes:
        ep = getattr(r, "endpoint", None)
        if ep is not None and ep.__name__ == fn_name:
            return ep.__closure__[ep.__code__.co_freevars.index(var)]
    raise LookupError(fn_name)


def _scan(c):
    return c.post("/api/scan", files={"files": ("c.jpg", _jpeg_bytes(), "image/jpeg")}).json()


def _app(tmp_path, f2f, pipeline=NumberedPipeline, interval=None):
    app = create_app(pipeline_factory=pipeline, store=ScanStore(tmp_path / "s.db"), f2f=f2f,
                     scan_images_dir=tmp_path / "imgs", auto_sweep_interval=interval)
    return app, TestClient(app)


def test_a_finished_sweep_never_clobbers_the_next_one(tmp_path):
    f2f = CountF2F()
    app, c = _app(tmp_path, f2f)
    ids = [_scan(c)["id"] for _ in range(3)]
    sweep = app.state.sweep
    f2f.calls.clear(); f2f.max_inflight = 0
    f2f.hold = lambda cn: cn.startswith("2")            # sweep B (scan 2) parks mid-fetch
    a_thread, fired, res = [], [], {}

    def on_release():
        # Sweep A just went idle under the lock: claim sweep B in that gap.
        if fired or not a_thread or a_thread[0] is not threading.current_thread() or sweep["active"]:
            return
        fired.append(1)
        t = threading.Thread(target=lambda: res.__setitem__(
            "B", TestClient(app).post(f"/api/scans/{ids[1]}/price-check").json()))
        t.start(); res["tB"] = t
        assert f2f.entered.wait(5), "sweep B never started"

    cell = _cell(app, "price_check", "sweep_lock")
    cell.cell_contents = HookLock(cell.cell_contents, on_release)
    real = f2f.get_price

    def mark_a(*a, **k):                                 # remember sweep A's thread
        if a[2].startswith("1") and not a_thread:
            a_thread.append(threading.current_thread())
        return real(*a, **k)

    f2f.get_price = mark_a
    assert c.post(f"/api/scans/{ids[0]}/price-check").json()["queued"] == 2
    assert fired, "the gap was never reached"
    assert sweep["active"] is True                       # B still owns the slot
    assert c.post("/api/price-sweep/stop").json() == {"stopping": True}
    third = c.post(f"/api/scans/{ids[2]}/price-check").json()
    assert third.get("queued", 0) == 0                   # never a parallel sweep
    f2f.gate.set(); res["tB"].join(5)
    assert f2f.max_inflight == 1
    assert sweep["active"] is False and not sweep["priority"]


def test_price_check_leaves_the_breaker_cooldown_alone(tmp_path, monkeypatch):
    # This test runs the real auto-sweep thread. Its retro-OCR pass would
    # import the ONNX runtime in that daemon thread, and interpreter shutdown
    # mid-import aborts the process (exit 134) — keep OCR out of it.
    from mtg_card_scanner import ocr_id
    monkeypatch.setattr(ocr_id, "_ocr_status", {"available": False, "error": "test"})
    f2f = CountF2F()
    app, c = _app(tmp_path, f2f, interval=0.2)
    sweep = app.state.sweep
    ids = [_scan(c)["id"] for _ in range(3)]
    time.sleep(0.5)
    sweep["backoff_until"] = time.monotonic() + 600      # breaker just tripped
    f2f.calls.clear()
    c.post(f"/api/scans/{ids[0]}/price-check")
    time.sleep(1.2)                                      # several auto-ticks
    assert sweep["backoff_until"] is not None
    assert all(cn.startswith("1") for cn in f2f.calls), f2f.calls   # only the checked card


def test_stop_does_not_strand_a_queued_price_check(tmp_path):
    f2f = CountF2F()
    app, c = _app(tmp_path, f2f)
    s1 = _scan(c)["id"]; _scan(c)
    sweep = app.state.sweep
    f2f.calls.clear(); f2f.hold = lambda cn: cn.startswith("2")
    t = threading.Thread(target=lambda: TestClient(app).post("/api/scans/price-missing")); t.start()
    assert f2f.entered.wait(5)
    assert c.post(f"/api/scans/{s1}/price-check").json().get("sweeping")
    c.post("/api/price-sweep/stop")
    f2f.gate.set(); t.join(5)
    assert not sweep["active"] and not sweep["priority"]     # nothing left without a consumer
    f2f.hold = None
    assert c.post(f"/api/scans/{s1}/price-check").json()["queued"] >= 1   # the page's re-request works
    assert all(cc.get("f2f_conditions") is not None for cc in c.get(f"/api/scans/{s1}").json()["candidates"])


def test_scan_time_pricing_and_a_price_check_never_fetch_in_parallel(tmp_path):
    f2f = CountF2F()
    app, c = _app(tmp_path, f2f, pipeline=SingleCandPipeline)
    f2f.hold = lambda cn: len(f2f.calls) == 1            # hold the scan-time fetch
    res = {}
    t = threading.Thread(target=lambda: res.__setitem__("scan", _scan(TestClient(app)))); t.start()
    assert f2f.entered.wait(5)
    sid = app.state.store.list_scans()[0]["id"]
    pc = c.post(f"/api/scans/{sid}/price-check").json()
    assert pc.get("sweeping") is True                    # joined the slot, didn't race it
    f2f.gate.set(); t.join(5)
    assert f2f.max_inflight == 1
    assert f2f.calls.count(f2f.calls[0]) == 1            # the same print fetched once
    assert not app.state.sweep["active"]
