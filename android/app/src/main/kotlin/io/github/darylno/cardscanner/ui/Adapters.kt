package io.github.darylno.cardscanner.ui

import android.content.Context
import io.github.darylno.cardscanner.net.PrefsConfigStore
import io.github.darylno.cardscanner.net.ServerClient
import io.github.darylno.cardscanner.net.UploadQueue
import java.io.File

/**
 * The one place the UI constructs the other agents' classes. App.kt calls
 * these once at process start.
 */
object Adapters {
    @Volatile private var serverAdapter: ServerAdapter? = null

    fun server(ctx: Context): ServerAdapter = serverAdapter ?: synchronized(this) {
        serverAdapter ?: ServerAdapter(PrefsConfigStore(ctx), ServerClient(PrefsConfigStore(ctx))).also { serverAdapter = it }
    }

    /** Persistent queue in filesDir/queue, uploading through the failover client; started now. */
    fun uploads(ctx: Context): UploadPort =
        UploadAdapter(UploadQueue(File(ctx.filesDir, "queue"), server(ctx).client))

    fun gateway(ctx: Context): GatewayPort {
        val s = server(ctx)
        return GatewayAdapter(ctx.applicationContext) { s.upstream() }
    }

    fun camera(ctx: Context, settings: AppSettings): CameraPort = CameraAdapter(ctx, settings)
}
