// Runs phone.html's REAL detection code (constants, state, detection
// functions and tick()) under Node and records the state after every step of
// a scenario, so DetectionDifferentialTest can hold the Kotlin port to it.
//
//   node phone_harness.js <phone.html>  < scenario.json  > trace.json
//
// Only the DOM/camera edges are stubbed: sampleSmallGray returns the
// scenario's frame (applying the page's own MH-change reset, copied from
// sampleSmallGray), and drawing/status calls are no-ops. The section markers
// below are the page's own comment headers — if they move, this fails loudly.
'use strict';
const fs = require('fs');
const vm = require('vm');

const html = fs.readFileSync(process.argv[2], 'utf8');
function section(start, end) {
  const a = html.indexOf(start), b = html.indexOf(end, a + 1);
  if (a < 0 || b < 0) throw new Error(`phone.html marker not found: ${a < 0 ? start : end}`);
  return html.slice(a, b);
}
const code = [
  section('/* ── detection constants', 'const DEBUG'),
  "const DEBUG = false, PANEL = true;",       // PANEL: tick is driven by us, no setInterval
  section('let autoEnabled = true;', 'function setStatus'),
  section('/* ── frame sampling + occupancy detection', '/* ── overlay'),
  section('/* ── auto-scan state machine', '/* ── controls'),
].join('\n');

const scenario = JSON.parse(fs.readFileSync(0, 'utf8'));
const driver = `
let lastDrawSig = '', settingArea = false;
let __frame = null, __trigger = null, __finally = null, __next = null;
function drawOverlay() {}
function setStatus() {}
function dbg() {}
function hideRetry() { __next = true; }
function scanOnce() { __trigger = true; return { finally(cb) { __finally = cb; } }; }
sampleSmallGray = function () {
  if (!__frame) return null;
  if (__frame.h !== MH) {                       // copied from sampleSmallGray
    MH = __frame.h;
    emptyRef = null; emptyGrad = null; prevFrame = null; bootCount = 0;
  }
  return Uint8ClampedArray.from(__frame.px);
};
const out = [];
for (const step of scenario.steps) {
  __trigger = null; __next = null;
  __frame = step.frame || null;
  switch (step.op) {
    case 'tick': tick(); break;
    case 'captureDone': if (__finally) { const f = __finally; __finally = null; f(); } break;
    case 'manual':                               // shoot handler: scan, then finally → awaitNext
      mode = 'scanning'; scannedFrame = sampleSmallGray() || scannedFrame;
      __finally = () => { mode = 'awaitNext'; }; break;
    case 'noCard': {                             // submitScan's no_card branch
      const capturedScene = scannedFrame;
      const s = sampleSmallGray();
      if (s && capturedScene && s.length === capturedScene.length
          && changedFrac(s, capturedScene) < SWAP_FRAC) { emptyRef = s; emptyGrad = gradMap(s); }
      break;
    }
    case 'reset':                                // resetDetection()
      emptyRef = null; emptyGrad = null; prevFrame = null;
      bootCount = 0; stableCount = 0; emptyRefreshTick = 0; mode = 'watching'; break;
    case 'auto':
      autoEnabled = step.on; if (autoEnabled) { mode = 'watching'; stableCount = 0; } break;
    default: throw new Error('unknown op ' + step.op);
  }
  let box = null;
  if (emptyRef && step.frame && step.frame.h === MH) {
    const b = detectBox(Uint8ClampedArray.from(step.frame.px));
    if (b) box = [b.x, b.y, b.w, b.h, b.maskFrac];
  }
  out.push({ mode, stableCount, bootCount, emptyRefreshTick,
             hasEmptyRef: !!emptyRef, trigger: !!__trigger, next: !!__next, box });
}
out;
`;
const ctx = vm.createContext({ scenario, Uint8ClampedArray, Uint8Array, Uint16Array, Math, JSON, Number, Error });
const trace = vm.runInContext(code + '\n' + driver, ctx);
process.stdout.write(JSON.stringify(trace));
