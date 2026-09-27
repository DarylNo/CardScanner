"""
Export the F2F pacing parity table for the Android app's JVM tests.

Drives the REAL ``mtg_card_scanner.facetoface._default_get_json`` (adaptive
pacing: slow start 2s -> AIMD, floor 0.5s, ceil 10s, idle reset 120s, the
two-attempt 429 / error retry) through scripted storefront responses on a
fake clock, and writes every wait, request time and pace to
android/app/src/test/resources/f2f/pacing.json — plus the session headers
(browser UA etc.) and the exact suggest URLs of the most-specific-first
query ladder, both taken from the same real code. The Kotlin port
(io.github.darylno.cardscanner.f2f.F2fFetcher, used by the Diagnostics →
Network test) must reproduce the trace exactly; CI re-runs this script with
--check so the table can never silently drift from the rig's client.

    python scripts/export_f2f_pacing_fixture.py          # (re)write
    python scripts/export_f2f_pacing_fixture.py --check  # exit 1 if stale
"""

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import time as _real_time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from mtg_card_scanner import facetoface as m  # noqa: E402

OUT = ROOT / "android" / "app" / "src" / "test" / "resources" / "f2f" / "pacing.json"
LATENCY = 0.125    # seconds each scripted response takes (advances the clock)


def ok():
    return {"status": 200}


def r429(retry_after=None):
    return {"status": 429, "retry_after": retry_after}


def err(status=500):
    return {"status": status}


def timeout():
    return {"status": "timeout"}


def gap(seconds):
    return {"gap": seconds}


# Each scenario is a queue: responses are consumed one per HTTP request in
# order; a {"gap": s} entry advances the clock between get_json calls.
SCENARIOS: dict[str, list[dict]] = {
    "slow_start_speedup_to_floor": [ok() for _ in range(16)],
    "backoff_on_429_then_recover": (
        [ok() for _ in range(6)] + [r429(), ok(), ok(), r429("5"), ok()]
        + [ok() for _ in range(4)]
    ),
    "ceiling_under_429_storm": [r429() for _ in range(12)] + [ok(), ok(), ok()],
    "errors_are_not_pacing_signals": [
        ok(), ok(), err(500), ok(), timeout(), timeout(), ok(), err(404), err(503), ok(),
    ],
    "idle_reset_after_120s": (
        [ok() for _ in range(8)] + [gap(119.0), ok(), ok(), gap(121.0), ok(), ok(), ok()]
    ),
    "retry_after_variants": [
        r429("30"), ok(),          # capped at 15
        r429("abc"), ok(),         # unparseable -> 0 -> 2*attempt
        r429("1"), r429(" 3 "),    # max(1, 2); second attempt max(3, 4) then give up
        r429("0"), err(502),       # 429 then an error on the retry -> give up
        ok(), ok(),
    ],
}


class Clock:
    def __init__(self) -> None:
        self.t = 1000.0          # like time.monotonic(): far from 0, so the first call is "idle"


def run(scenario: list[dict]) -> list[dict]:
    clock = Clock()
    trace: list[dict] = []
    queue = list(scenario)

    class FakeTime:
        @staticmethod
        def monotonic():
            return clock.t

        @staticmethod
        def sleep(s):
            trace.append({"wait": round(s, 9)})
            clock.t += s

        time = staticmethod(_real_time.time)

    class FakeEvent:
        @staticmethod
        def wait(s):
            trace.append({"wait": round(s, 9)})
            clock.t += s
            return False

        @staticmethod
        def is_set():
            return False

    holder: dict = {}

    class Resp:
        def __init__(self, spec):
            self.status_code = spec["status"]
            ra = spec.get("retry_after")
            self.headers = {} if ra is None else {"Retry-After": ra}

        def raise_for_status(self):
            if self.status_code >= 400:
                raise RuntimeError(f"HTTP {self.status_code}")

        def json(self):
            return {"ok": True}

    class Sess:
        headers: dict = {}

        def get(self, url, timeout=None):
            spec = queue.pop(0)
            trace.append({"req": spec["status"], "t": round(clock.t, 9),
                          "delay": round(holder["gj"].current_delay(), 9)})
            clock.t += LATENCY
            if spec["status"] == "timeout":
                raise TimeoutError("timed out")
            return Resp(spec)

    saved_time, saved_session = m.time, m._new_session
    m.time = FakeTime
    m._new_session = lambda: Sess()
    try:
        # (get_json prints a line per surrendered URL — noise here)
        with tempfile.TemporaryDirectory() as d, contextlib.redirect_stdout(io.StringIO()):
            gj = m._default_get_json(Path(d), FakeEvent())
            holder["gj"] = gj
            n = 0
            while queue:
                if "gap" in queue[0]:
                    clock.t += queue.pop(0)["gap"]
                    continue
                n += 1
                data = gj(f"https://example.invalid/call/{n}.json")
                trace.append({"result": "ok" if data is not None else "fail",
                              "delay": round(gj.current_delay(), 9)})
    finally:
        m.time, m._new_session = saved_time, saved_session
    return trace


def session_headers() -> dict:
    """The headers _default_get_json puts on its session (UA, Accept, ...)."""
    class Sess:
        def __init__(self):
            self.headers = {}

    saved = m._new_session
    holder = {}
    m._new_session = lambda: holder.setdefault("s", Sess())
    try:
        with tempfile.TemporaryDirectory() as d:
            m._default_get_json(Path(d))
    finally:
        m._new_session = saved
    return dict(holder["s"].headers)


# get_price calls whose every suggest answer is empty: the URLs requested are
# the whole query ladder, most specific first, exactly as the rig encodes it.
LADDER_CARDS = [
    {"name": "Lightning Bolt", "set_code": "m10", "collector_number": "146", "foil": False, "set_name": "Magic 2010"},
    {"name": "Sol Ring", "set_code": "cmr", "collector_number": "472", "foil": True, "set_name": "Commander Legends"},
    {"name": "Ragavan, Nimble Pilferer", "set_code": "mh2", "collector_number": "138", "foil": False,
     "set_name": "Modern Horizons 2"},
    {"name": "Lim-Dûl's Vault", "set_code": "all", "collector_number": "", "foil": False, "set_name": "Alliances"},
    {"name": "Wastes", "set_code": "ogw", "collector_number": "183a", "foil": True, "set_name": ""},
]


def ladders() -> list[dict]:
    out = []
    for card in LADDER_CARDS:
        urls: list[str] = []

        def get_json(url, urls=urls):
            urls.append(url)
            return {"resources": {"results": {"products": []}}}

        result = m.FaceToFaceClient(get_json=get_json).get_price(**card)
        assert result is None
        out.append({"card": card, "urls": urls})
    return out


def build() -> bytes:
    doc = {
        "about": "Generated by scripts/export_f2f_pacing_fixture.py from the real facetoface.py — do not hand-edit.",
        "base": m._BASE,
        "session_headers": session_headers(),
        "ladders": ladders(),
        "latency_s": LATENCY,
        "start_clock_s": Clock().t,
        "scenarios": {name: {"script": steps, "trace": run(steps)}
                      for name, steps in SCENARIOS.items()},
    }
    return (json.dumps(doc, indent=1, sort_keys=True) + "\n").encode()


def main() -> int:
    data = build()
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_bytes() != data:
            print("f2f pacing fixture is stale — run: python scripts/export_f2f_pacing_fixture.py")
            return 1
        print("f2f pacing fixture up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
