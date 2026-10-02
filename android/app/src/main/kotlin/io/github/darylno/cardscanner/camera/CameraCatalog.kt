package io.github.darylno.cardscanner.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import io.github.darylno.cardscanner.core.CameraChoice

/**
 * The phone's cameras for the Camera setting, read from Camera2 — no camera is
 * opened and no permission is needed. Only cameras CameraX can bind are listed
 * (BACKWARD_COMPATIBLE: a depth or IR sensor can't run a preview). Never throws:
 * a phone that refuses the query lists nothing, and Automatic still works.
 */
object CameraCatalog {
    fun lenses(context: Context): List<CameraChoice.Lens> = runCatching {
        val cm = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        cm.cameraIdList.mapNotNull { id ->
            runCatching {
                val ch = cm.getCameraCharacteristics(id)
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (caps != null && CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) return@runCatching null
                val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> CameraChoice.Facing.BACK
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraChoice.Facing.FRONT
                    else -> CameraChoice.Facing.EXTERNAL
                }
                val px = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                CameraChoice.Lens(
                    id = id, facing = facing,
                    megapixels = px?.let { it.width.toDouble() * it.height / 1e6 },
                    autofocus = minFocus?.let { it > 0f },
                    focalMm = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
