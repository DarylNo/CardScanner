package io.github.darylno.cardscanner.core

import io.github.darylno.cardscanner.core.Detection.changedFrac
import io.github.darylno.cardscanner.core.Detection.detectBox
import io.github.darylno.cardscanner.core.Detection.gradMap

/**
 * Port of phone.html's auto-scan state machine (`tick()` and the handful of
 * statements around it that touch its state). One [tick] per SAMPLE_MS with a
 * fresh grey sample of the scan area; it returns what the UI should show and
 * whether to capture NOW.
 *
 * JS → Kotlin map (phone.html):
 *   tick()                           → [tick]
 *   sampleSmallGray()'s MH-change reset → [accept] (every sample passes it)
 *   scanOnce().finally(awaitNext)    → [captureDone]
 *   shoot click handler              → [manualScan] + [captureDone]
 *   submitScan() no_card re-seed     → [onNoCard]
 *   resetDetection()                 → [reset]
 *   startCamera() ref reset          → [cameraRestarted]
 *   autoBtn click                    → [setAuto]
 *
 * One deliberate difference, fixed in phone.html in the same change: the
 * awaitNext swap test read prevFrame unguarded, which throws when a reset
 * races a no_card re-seed (the watching branch already guarded it).
 */
class AutoScanner {
    enum class Mode { WATCHING, SCANNING, AWAIT_NEXT }

    sealed interface Event {
        /** Auto off, capturing, area being drawn, or no sample. Nothing changes. */
        data object Idle : Event
        /** Learning the empty tray (hold it empty and steady). [learned] on the tick it's taken. */
        data class Learning(val learned: Boolean) : Event
        /** Watching: [box] is what the mask sees; [occupied] = green, else amber. */
        data class Watching(val box: Box?, val occupied: Boolean, val stableCount: Int) : Event
        /** Capture NOW: occupancy + stillness confirmed. [scene] is kept for the swap/no-card tests. */
        data class Trigger(val box: Box, val scene: Gray) : Event
        /** A card was scanned; waiting for it to leave or be swapped. */
        data class AwaitingNext(val box: Box?) : Event
        /** The scanned card left ([removed]) or a different card settled in its place. */
        data class NextCard(val box: Box?, val removed: Boolean) : Event
    }

    var mode = Mode.WATCHING
        private set
    var autoEnabled = true
        private set
    /** True while the user drags a new scan area — ticks pause (phone.html settingArea). */
    var paused = false

    private var emptyRef: Gray? = null
    private var emptyGrad: IntArray? = null
    private var prevFrame: Gray? = null
    /** The scene at the last capture (phone.html scannedFrame). */
    var scannedFrame: Gray? = null
        private set
    var stableCount = 0
        private set
    var bootCount = 0
        private set
    var emptyRefreshTick = 0
        private set
    private var mh = 132             // phone.html `let MH = 132`
    /** A capture was started and its completion is still owed ([captureDone]). */
    private var capturing = false

    val hasEmptyRef get() = emptyRef != null

    /** sampleSmallGray's geometry guard: a new scan-area aspect invalidates every reference. */
    private fun accept(sample: Gray): Gray {
        if (sample.h != mh) {
            mh = sample.h
            emptyRef = null; emptyGrad = null; prevFrame = null; bootCount = 0
        }
        return sample
    }

    fun tick(sample: Gray?): Event {
        if (!autoEnabled || mode == Mode.SCANNING || paused) return Event.Idle
        val cur = accept(sample ?: return Event.Idle)

        val ref = emptyRef
        if (ref == null) {
            val prev = prevFrame
            if (prev != null && changedFrac(cur, prev) < DetectConst.STABLE_FRAC) bootCount++
            else bootCount = 0
            var learned = false
            if (bootCount >= DetectConst.STABLE_FRAMES + 1) {
                emptyRef = cur; emptyGrad = gradMap(cur); bootCount = 0
                learned = true
            }
            prevFrame = cur
            return Event.Learning(learned)
        }

        val box = detectBox(cur, ref, emptyGrad!!)
        val occupied = box != null && box.maskFrac > DetectConst.OCCUPIED_FRAC
        val prev = prevFrame
        val event: Event

        if (mode == Mode.WATCHING) {
            // prevFrame can be null for one tick after a reset that raced an
            // in-flight no_card response re-seeding emptyRef.
            if (occupied && prev != null && changedFrac(cur, prev) < DetectConst.STABLE_FRAC) {
                stableCount++
                if (stableCount >= DetectConst.STABLE_FRAMES) {
                    stableCount = 0
                    scannedFrame = cur
                    mode = Mode.SCANNING
                    capturing = true
                    prevFrame = cur
                    return Event.Trigger(box!!, cur)
                }
                event = Event.Watching(box, true, stableCount)
            } else {
                stableCount = 0
                if ((box == null || box.maskFrac < DetectConst.EMPTY_FRAC) && prev != null &&
                    changedFrac(cur, prev) < DetectConst.STABLE_FRAC) {
                    if (++emptyRefreshTick >= 25) {
                        emptyRef = cur; emptyGrad = gradMap(cur); emptyRefreshTick = 0
                    }
                } else {
                    emptyRefreshTick = 0
                }
                event = Event.Watching(box, occupied, 0)
            }
        } else {   // AWAIT_NEXT
            val removed = box == null || box.maskFrac < DetectConst.EMPTY_FRAC
            val scanned = scannedFrame
            val swapped = scanned != null && occupied &&
                changedFrac(cur, scanned) > DetectConst.SWAP_FRAC &&
                prev != null && changedFrac(cur, prev) < DetectConst.STABLE_FRAC
            if (removed || swapped) {
                mode = Mode.WATCHING; stableCount = 0
                event = Event.NextCard(box, removed)
            } else {
                event = Event.AwaitingNext(box)
            }
        }
        prevFrame = cur
        return event
    }

    /** What the mask sees in [sample] against the learned empty tray (null before learning). */
    fun boxFor(sample: Gray): Box? {
        val ref = emptyRef ?: return null
        if (sample.h != mh) return null
        return detectBox(sample, ref, emptyGrad!!)
    }

    /**
     * The capture (auto or manual) finished — wait for the card to leave. Only
     * a capture actually in flight completes (phone.html runs this as that
     * capture's `finally`); it lands even after a reset, exactly as the page's
     * late-arriving `finally` does.
     */
    fun captureDone() {
        if (!capturing) return
        capturing = false
        mode = Mode.AWAIT_NEXT
    }

    /**
     * App-only (the Mount focus hold — phone.html has no equivalent, so the
     * differential test never calls it): a triggered capture was HELD while the
     * lens refocused on the card, and the refocus changes the card's look
     * (blur crosses SWAP_FRAC). The scene the swap test compares against must
     * be the one actually shot, or the still-present card reads as a swap and
     * is scanned again with nothing placed.
     */
    fun rebaseScene(sample: Gray) {
        if (capturing && sample.h == mh) scannedFrame = sample
    }

    /** Scan Card button: snapshot the scene BEFORE capturing (phone.html shoot handler). */
    fun manualScan(sample: Gray?) {
        mode = Mode.SCANNING
        capturing = true
        scannedFrame = sample?.let(::accept) ?: scannedFrame
    }

    /**
     * The server saw no card. Adopt the current view as "empty" ONLY if the tray
     * still shows what was scanned — the user may already have swapped in the
     * next card. [capturedScene] is [scannedFrame] as it was when the upload began.
     */
    fun onNoCard(current: Gray?, capturedScene: Gray?) {
        val s = current?.let(::accept) ?: return
        if (capturedScene != null && s.size == capturedScene.size &&
            changedFrac(s, capturedScene) < DetectConst.SWAP_FRAC) {
            emptyRef = s; emptyGrad = gradMap(s)
        }
    }

    /** Double-tap / new scan area: re-learn the empty tray. */
    fun reset() {
        emptyRef = null; emptyGrad = null; prevFrame = null
        bootCount = 0; stableCount = 0; emptyRefreshTick = 0; mode = Mode.WATCHING
    }

    /** A different camera (or a rebind): old references are wrong geometry. */
    fun cameraRestarted() {
        emptyRef = null; emptyGrad = null; prevFrame = null; bootCount = 0
    }

    fun setAuto(enabled: Boolean) {
        autoEnabled = enabled
        if (enabled) { mode = Mode.WATCHING; stableCount = 0 }
    }
}
