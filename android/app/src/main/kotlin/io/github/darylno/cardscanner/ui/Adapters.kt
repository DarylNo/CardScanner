package io.github.darylno.cardscanner.ui

import android.content.Context
import io.github.darylno.cardscanner.net.UploadQueue
import io.github.darylno.cardscanner.phoneserver.PhoneServer
import java.io.File

/**
 * The one place the UI constructs the other agents' classes. App.kt calls
 * these once at process start.
 */
object Adapters {
    /**
     * Persistent queue in filesDir/queue; its uploader is the phone itself
     * (identify + file into the phone's store). Started now.
     */
    fun uploads(ctx: Context, phone: PhoneServer): UploadPort =
        UploadAdapter(UploadQueue(File(ctx.filesDir, "queue"), phone.uploader))

    fun gateway(ctx: Context): GatewayPort = GatewayAdapter(ctx.applicationContext)

    fun camera(ctx: Context, settings: AppSettings): CameraPort = CameraAdapter(ctx, settings)
}
