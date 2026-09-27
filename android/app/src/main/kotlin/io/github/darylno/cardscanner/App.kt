package io.github.darylno.cardscanner

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.darylno.cardscanner.ui.AppSettings
import io.github.darylno.cardscanner.ui.Adapters
import io.github.darylno.cardscanner.ui.CameraPort
import io.github.darylno.cardscanner.ui.GatewayPort
import io.github.darylno.cardscanner.ui.ServerPort
import io.github.darylno.cardscanner.ui.UploadPort
import org.opencv.android.OpenCVLoader

/**
 * App-wide singletons. The upload queue starts HERE (not in an activity) so
 * jobs persisted before a process death resume as soon as the process is back,
 * whatever screen comes up first.
 */
class App : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var server: ServerPort
        private set
    lateinit var uploads: UploadPort
        private set
    lateinit var gateway: GatewayPort
        private set

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e(TAG, "OpenCV native init failed — capture will not work")
        settings = AppSettings(this)
        server = Adapters.server(this)
        uploads = Adapters.uploads(this)
        gateway = Adapters.gateway(this)
    }

    fun newCamera(): CameraPort = Adapters.camera(this, settings)

    companion object {
        private const val TAG = "CardScanner"
        fun of(ctx: Context): App = ctx.applicationContext as App
    }
}
