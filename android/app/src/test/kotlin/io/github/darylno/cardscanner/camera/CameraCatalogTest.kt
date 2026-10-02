package io.github.darylno.cardscanner.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import io.github.darylno.cardscanner.core.CameraChoice
import io.github.darylno.cardscanner.core.server.DeviceApi
import io.github.darylno.cardscanner.phoneserver.DeviceBridge
import io.github.darylno.cardscanner.ui.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowCameraCharacteristics

/** The Camera setting reads the phone's lenses from Camera2 and hands them to the browser. */
@RunWith(RobolectricTestRunner::class)
class CameraCatalogTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun addCamera(id: String, facing: Int, w: Int, h: Int, minFocus: Float, focal: Float, compatible: Boolean = true) {
        val ch = ShadowCameraCharacteristics.newCameraCharacteristics()
        val sh = shadowOf(ch) as ShadowCameraCharacteristics
        sh.set(CameraCharacteristics.LENS_FACING, facing)
        sh.set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, Size(w, h))
        sh.set(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE, minFocus)
        sh.set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(focal))
        sh.set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            if (compatible) intArrayOf(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
            else intArrayOf(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT))
        shadowOf(ctx.getSystemService(CameraManager::class.java)).addCamera(id, ch)
    }

    /** A phone like the N200: main, front, a 2 MP fixed-focus module, and a depth sensor CameraX can't bind. */
    private fun n200() {
        addCamera("0", CameraCharacteristics.LENS_FACING_BACK, 4096, 3072, 10f, 4.7f)
        addCamera("1", CameraCharacteristics.LENS_FACING_FRONT, 4608, 3456, 0f, 3.0f)
        addCamera("3", CameraCharacteristics.LENS_FACING_BACK, 1600, 1200, 0f, 2.2f)
        addCamera("4", CameraCharacteristics.LENS_FACING_BACK, 640, 480, 0f, 1.8f, compatible = false)
    }

    @Test fun readsTheLensesCameraXCanBind() {
        n200()
        val lenses = CameraCatalog.lenses(ctx)
        assertEquals("the depth-only sensor is left out", listOf("0", "1", "3"), lenses.map { it.id })
        assertEquals(CameraChoice.Lens("0", CameraChoice.Facing.BACK, 4096.0 * 3072 / 1e6, true, 4.7f), lenses[0])
        assertEquals(false, lenses[2].autofocus)
        assertEquals("0", CameraChoice.automatic(lenses))
    }

    @Test fun theBrowserIsOfferedTheBackCamerasAndCanPickOne() {
        n200()
        val settings = AppSettings(ctx)
        val api = DeviceApi(DeviceBridge(settings) { CameraCatalog.lenses(ctx) })
        assertEquals(listOf("0", "3"), DeviceBridge(settings) { CameraCatalog.lenses(ctx) }.cameras().map { it.first })
        val body = """{"camera":"3"}""".toByteArray()
        assertEquals(200, api.handle(io.github.darylno.cardscanner.core.server.ApiRequest("PATCH", "/api/device", body = body))!!.status)
        assertEquals("3", settings.cameraId)
        val front = """{"camera":"1"}""".toByteArray()
        assertEquals("the front camera is not offered", 400,
            api.handle(io.github.darylno.cardscanner.core.server.ApiRequest("PATCH", "/api/device", body = front))!!.status)
        assertEquals("3", settings.cameraId)
        settings.cameraId = null
    }

    @Test fun aPhoneWithNoCamerasListsNothingAndNeverThrows() {
        assertEquals(emptyList<CameraChoice.Lens>(), CameraCatalog.lenses(ctx))
    }
}
