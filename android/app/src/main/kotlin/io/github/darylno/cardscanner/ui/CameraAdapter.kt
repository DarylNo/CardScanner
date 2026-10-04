package io.github.darylno.cardscanner.ui

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import io.github.darylno.cardscanner.camera.CameraController
import io.github.darylno.cardscanner.camera.CaptureBurst
import io.github.darylno.cardscanner.camera.CaptureOutcome
import io.github.darylno.cardscanner.camera.CaptureTrigger
import io.github.darylno.cardscanner.camera.DetectionUpdate
import io.github.darylno.cardscanner.camera.ScanMode
import io.github.darylno.cardscanner.core.Gray
import io.github.darylno.cardscanner.core.RoiFrac

/**
 * [CameraPort] over the CAMERA agent's [CameraController] (which owns the
 * analysis thread, the frame ring, the AutoScanner and the capture pipeline).
 * Created per camera screen; [bind] constructs the controller (it needs the
 * LifecycleOwner) and replays the settings chosen before binding.
 */
class CameraAdapter(private val context: Context, private val settings: AppSettings) : CameraPort {
    private var controller: CameraController? = null
    /** Settings → Diagnostics reads the camera block live through this while the controller exists. */
    private var diagnosticsSource: (() -> String)? = null
    private var auto = true
    private var roi: RoiFrac? = null
    private var preview: PreviewView? = null

    override fun bind(owner: LifecycleOwner, preview: PreviewView, listener: CameraPort.Listener) {
        val c = CameraController(context, owner, object : CameraController.Listener {
            override fun onDetection(update: DetectionUpdate) {
                listener.onFrameSize(update.uprightW, update.uprightH)
                val dbg = "mask ${update.box?.let { "%.1f%%".format(it.maskFrac * 100) } ?: "—"}" +
                    " · ${update.event.javaClass.simpleName}" +
                    ((update.event as? io.github.darylno.cardscanner.core.AutoScanner.Event.Watching)
                        ?.let { " · steady ${it.stableCount}" } ?: "") +
                    (update.outlineNanos?.let { " · outline %.1f ms".format(it / 1e6) } ?: "") +
                    "\n" + (controller?.pipeline?.lastSummary ?: "")
                listener.onDetection(update.event, update.grayW, update.grayH, dbg, update.outline, update.watch,
                    update.triggerRefused)
            }

            override fun onCaptureStarted(request: CaptureBurst) {
                listener.onFrameSize(request.uprightW, request.uprightH)
                listener.onCaptureStarted(request.id, request.trigger == CaptureTrigger.MANUAL)
            }

            override fun onCapture(outcome: CaptureOutcome) {
                val r = outcome.result
                if (r == null) {
                    listener.onCaptureFailed(outcome.id, outcome.error?.message ?: outcome.error?.toString() ?: "unknown error")
                    return
                }
                listener.onCaptured(
                    Captured(
                        r.primary, r.fallbacks, r.flattened, r.summary(), if (outcome.mode == ScanMode.MOUNT) outcome.scene else null,
                        quad = r.quad, frameW = r.frameWidth, frameH = r.frameHeight,
                        burstId = outcome.id, lens = outcome.lens,
                    ),
                    outcome.trigger == CaptureTrigger.MANUAL,
                )
                // The snapshot Diagnostics falls back on once this screen is gone: current as of this capture.
                runCatching { CameraDiagnostics.snapshot(controller?.diagnostics()) }
            }

            override fun onCameraError(message: String, fatal: Boolean) {
                // Forward non-fatal errors too: "capture with no frames in the ring" and
                // analyzer exceptions mid-burst END a capture without any onCapture, so
                // dropping them left the status stuck on "Capturing…"/"Retrying — capturing…".
                listener.onCameraError(message)
            }

            override fun onCameraReady(summary: String) {
                runCatching { CameraDiagnostics.snapshot(controller?.diagnostics()) }
            }
        })
        controller = c
        val source = { c.diagnostics() }
        diagnosticsSource = source
        CameraDiagnostics.attach(source)
        this.preview = preview
        c.setScanMode(ScanMode.MOUNT)   // the app's only mode since 1.1.2 (Auto off = tap to scan)
        c.setRoi(roi)
        c.setAuto(auto)
        c.setTorch(settings.torch)
        c.setAeAwbLock(settings.aeLock)
        c.setCamera(settings.cameraId)
        c.start(preview, if (settings.highRes) CameraController.Resolution.HIGH else CameraController.Resolution.STANDARD)
    }

    /**
     * Re-bind ONLY if another screen (Setup's QR camera) unbound us. Rebinding
     * unconditionally on every resume re-learned the empty tray with the last
     * card still on it — lifting that card then read as a new one, a phantom
     * scan of the bare tray.
     */
    override fun rebind() {
        val v = preview ?: return
        val c = controller ?: return
        if (!c.isBound()) c.start(v)
    }

    override fun unbind() {
        // The last live values stay readable (with their age) after the screen is gone.
        runCatching { CameraDiagnostics.snapshot(controller?.diagnostics()) }
        diagnosticsSource?.let { CameraDiagnostics.detach(it) }
        diagnosticsSource = null
        controller?.release()
        controller = null
        preview = null
    }

    override fun setAuto(on: Boolean) {
        auto = on
        controller?.setAuto(on)
    }

    override fun setRoi(roi: RoiFrac?) {
        this.roi = roi
        controller?.setRoi(roi)
    }

    override fun relearn() { controller?.reset() }
    override fun pause(paused: Boolean) { controller?.setPaused(paused) }
    override fun manualScan() { controller?.manualScan() }
    override fun tapFocus(x: Float, y: Float) { controller?.tapToFocus(x, y) }
    override fun noCard(scene: Gray?) { controller?.onNoCard(scene) }
    override fun setTorch(on: Boolean) { controller?.setTorch(on) }
    override fun setAeLock(on: Boolean) { controller?.setAeAwbLock(on) }

    override fun setHighRes(high: Boolean) {
        controller?.setResolution(if (high) CameraController.Resolution.HIGH else CameraController.Resolution.STANDARD)
    }

    override fun setCamera(id: String?) { controller?.setCamera(id) }

    override fun diagnostics(): String = controller?.diagnostics() ?: "camera not bound"
    override fun snapshotJpeg(): ByteArray? = controller?.snapshotJpeg()
}
