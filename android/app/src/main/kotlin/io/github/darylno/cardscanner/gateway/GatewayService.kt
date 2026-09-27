package io.github.darylno.cardscanner.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.PowerManager
import android.net.wifi.WifiManager

/**
 * Foreground service (type specialUse) that owns the [GatewayServer] while the owner is
 * sharing. Started by the Share screen via [start]; stopped by [stop] or the notification's
 * Stop action — stopping rotates the join code and ends every guest session.
 *
 * The app must set [upstreamProvider] (normally in Application.onCreate) to adapt the
 * app-wide ServerClient: `GatewayService.upstreamProvider = { Upstream(serverClient::proxy) }`.
 * The Share screen observes [status] for the URLs + code (and draws the QR with [QrBitmap]).
 */
class GatewayService : Service() {

    /** What the Share screen shows. [error] is set (and [running] false) when start failed. */
    data class Status(
        val running: Boolean,
        val port: Int = 0,
        val code: String = "",
        val urls: List<String> = emptyList(),
        val error: String? = null,
    ) {
        /** The best join URL (Wi-Fi first, then hotspot), or null when there is no LAN. */
        val primaryUrl: String? get() = urls.firstOrNull()
    }

    private var server: GatewayServer? = null
    // Guests keep browsing while the phone's screen is off (it may sit in a
    // pocket while sharing): without these the CPU and Wi-Fi radio doze and
    // every guest request stalls. Held only while the gateway is serving.
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                server?.let { publish(it) }
                return START_NOT_STICKY
            }
        }
        val port = intent?.getIntExtra(EXTRA_PORT, GatewayServer.DEFAULT_PORT) ?: GatewayServer.DEFAULT_PORT
        // startForeground must happen promptly after startForegroundService, even on failure.
        goForeground(buildNotification(null, null))
        val existing = server
        if (existing != null && existing.isAlive) {
            publish(existing)
            return START_NOT_STICKY
        }
        val provider = upstreamProvider
        if (provider == null) {
            fail("Gateway not wired: no upstream (app bug)")
            return START_NOT_STICKY
        }
        val s = GatewayServer(provider(), port)
        try {
            s.startServing()
        } catch (e: Exception) {
            fail("Could not open port $port: ${e.message}")
            return START_NOT_STICKY
        }
        server = s
        acquireLocks()
        publish(s)
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cardscanner:gateway")
                ?.apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "cardscanner:gateway")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        server?.let {
            it.stopSharing()
            it.stop()
        }
        server = null
        releaseLocks()
        _status.value = Status(running = false)
    }

    private fun fail(message: String) {
        server?.stop()
        server = null
        _status.value = Status(running = false, error = message)
        stopSelf()
    }

    private fun publish(s: GatewayServer) {
        val port = s.listeningPort
        val urls = LocalAddresses.list().map { LocalAddresses.joinUrl(it, port, s.code) }
        _status.value = Status(running = true, port = port, code = s.code, urls = urls)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(urls.firstOrNull(), s.code))
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(url: String?, code: String?): Notification {
        ensureChannel(this)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val title = if (code != null) "Sharing review pages — code $code" else "Starting sharing…"
        val text = when {
            code == null -> "Opening the local gateway"
            url != null -> url
            else -> "No Wi-Fi or hotspot address — connect to Wi-Fi or turn on the hotspot"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (code != null && url != null) "Guests open $url\nor go to ${url.substringBefore("/join")} and enter $code" else text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.darylno.cardscanner.gateway.START"
        const val ACTION_STOP = "io.github.darylno.cardscanner.gateway.STOP"
        /** Re-reads the phone's addresses (e.g. after joining Wi-Fi / enabling the hotspot). */
        const val ACTION_REFRESH = "io.github.darylno.cardscanner.gateway.REFRESH"
        const val EXTRA_PORT = "port"
        const val CHANNEL_ID = "gateway"
        const val NOTIFICATION_ID = 4201

        /** Supplies the upstream (the app-wide ServerClient adapted to [Upstream]). */
        @Volatile
        var upstreamProvider: (() -> Upstream)? = null

        private val _status = MutableStateFlow(Status(running = false))
        val status: StateFlow<Status> = _status.asStateFlow()

        fun start(context: Context, port: Int = GatewayServer.DEFAULT_PORT) {
            val i = Intent(context, GatewayService::class.java).setAction(ACTION_START).putExtra(EXTRA_PORT, port)
            ContextCompat.startForegroundService(context, i)
        }

        fun refresh(context: Context) {
            if (!_status.value.running) return
            context.startService(Intent(context, GatewayService::class.java).setAction(ACTION_REFRESH))
        }

        fun stop(context: Context) {
            if (!_status.value.running) {
                context.stopService(Intent(context, GatewayService::class.java))
                return
            }
            context.startService(Intent(context, GatewayService::class.java).setAction(ACTION_STOP))
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Sharing", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while guests on this network can open the review pages"
                    },
                )
            }
        }
    }
}
