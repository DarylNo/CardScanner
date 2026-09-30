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
 * Foreground service (type specialUse) that keeps the phone's server on the LAN — the
 * [GatewayServer] in front of the phone's own store/API ([Host], the app's PhoneServer).
 * Stage 4: the phone IS the server, so the app starts this whenever it runs (the scan
 * screen, the review screen), and it keeps serving with the screen off; the notification's
 * Stop ends it (rotating the guest code and ending every guest session; the paired
 * computer stays paired).
 *
 * The app must set [hostProvider] (Application.onCreate). The Share screen observes
 * [status] for the URLs + code (and draws the QR with [QrBitmap]).
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
    private var host: Host? = null
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
            ACTION_ROTATE -> {
                server?.let { it.stopSharing(); publish(it) }   // guests out, new code; serving carries on
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
        val host = hostProvider?.invoke()
        if (host == null) {
            fail("Server not wired: no host (app bug)")
            return START_NOT_STICKY
        }
        val s = host.newServer(port)
        // A brute-force budget trip rotates the code on a request thread:
        // re-post the notification/status so the owner shows the new one.
        s.onCodeRotated = { android.os.Handler(android.os.Looper.getMainLooper()).post { if (server === s) publish(s) } }
        try {
            s.startServing()
        } catch (e: Exception) {
            fail("Could not open port $port: ${e.message}")
            return START_NOT_STICKY
        }
        server = s
        this.host = host
        startedAt = System.currentTimeMillis()
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        runCatching { host.onServing() }
        acquireLocks()
        publish(s)
        return START_NOT_STICKY
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            val s = server ?: return
            if (shouldIdleStop(System.currentTimeMillis(), maxOf(s.lastRemoteAt, startedAt), lastVisibleAt, visibleScreens)) {
                io.github.darylno.cardscanner.core.DebugLog.global.i("server", "idle 30 min with the app in the background — stopping")
                shutdown()
                stopSelf()
                return
            }
            main.postDelayed(this, IDLE_CHECK_MS)
        }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var startedAt = 0L

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
        main.removeCallbacks(idleCheck)
        if (server != null) io.github.darylno.cardscanner.core.DebugLog.global.i("server", "stopped")
        server?.let {
            it.stopSharing()
            it.stop()
        }
        server = null
        host?.let { runCatching { it.onStopped() } }
        host = null
        releaseLocks()
        _status.value = Status(running = false)
    }

    private fun fail(message: String) {
        io.github.darylno.cardscanner.core.DebugLog.global.e("server", "couldn't start: $message")
        server?.stop()
        server = null
        _status.value = Status(running = false, error = message)
        stopSelf()
    }

    private fun publish(s: GatewayServer) {
        val port = s.listeningPort
        val urls = LocalAddresses.list().map { LocalAddresses.joinUrl(it, port, s.code) }
        io.github.darylno.cardscanner.core.DebugLog.global.i("server", "serving on :$port at ${LocalAddresses.list().joinToString().ifEmpty { "no Wi-Fi address" }}")
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
        val title = if (code != null) "Scanner serving — guest code $code" else "Starting the scanner's server…"
        val text = when {
            code == null -> "Opening the local server"
            url != null -> url
            else -> "No Wi-Fi or hotspot address — connect to Wi-Fi or turn on the hotspot"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (code != null && url != null) "Your scans are served at ${url.substringBefore("/join")}\nGuests open $url or enter $code" else text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // The phone sits on a mount with its lock screen showing: never the guest code there.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("Scanner serving")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    /** The server the service keeps up: builds the gateway, and hears when it starts/stops. */
    interface Host {
        fun newServer(port: Int): GatewayServer
        fun onServing() {}
        fun onStopped() {}
    }

    companion object {
        /**
         * The server stops itself (wake + Wi-Fi locks released) once no app screen has been
         * visible and no other device has used it for this long; opening the app starts it
         * again. Keeps "always serving" from draining a phone left in a pocket.
         */
        const val IDLE_STOP_MS = 30 * 60_000L
        private const val IDLE_CHECK_MS = 60_000L

        @Volatile var visibleScreens = 0
            private set
        @Volatile var lastVisibleAt = 0L
            private set

        /** The scan and review screens report when they are on screen (onStart / onStop). */
        @Synchronized
        fun screenVisible(visible: Boolean) {
            visibleScreens = maxOf(0, visibleScreens + if (visible) 1 else -1)
            lastVisibleAt = System.currentTimeMillis()
        }

        /** Pure: stop when nothing is on screen and nothing — local or remote — used it for [idleMs]. */
        fun shouldIdleStop(now: Long, lastRemote: Long, lastVisible: Long, visible: Int, idleMs: Long = IDLE_STOP_MS): Boolean =
            visible == 0 && now - maxOf(lastRemote, lastVisible) >= idleMs

        const val ACTION_START = "io.github.darylno.cardscanner.gateway.START"
        const val ACTION_STOP = "io.github.darylno.cardscanner.gateway.STOP"
        /** Re-reads the phone's addresses (e.g. after joining Wi-Fi / enabling the hotspot). */
        const val ACTION_REFRESH = "io.github.darylno.cardscanner.gateway.REFRESH"
        /** New guest code: ends every guest session, keeps serving (the paired computer stays paired). */
        const val ACTION_ROTATE = "io.github.darylno.cardscanner.gateway.ROTATE"
        const val EXTRA_PORT = "port"
        const val CHANNEL_ID = "gateway"
        const val NOTIFICATION_ID = 4201

        /** What the service serves (the app's PhoneServer). */
        @Volatile
        var hostProvider: (() -> Host)? = null

        private val _status = MutableStateFlow(Status(running = false))
        val status: StateFlow<Status> = _status.asStateFlow()

        fun start(context: Context, port: Int = GatewayServer.DEFAULT_PORT) {
            // A new attempt: an earlier start's error must not read as this one's.
            if (!_status.value.running && _status.value.error != null) _status.value = Status(running = false)
            val i = Intent(context, GatewayService::class.java).setAction(ACTION_START).putExtra(EXTRA_PORT, port)
            ContextCompat.startForegroundService(context, i)
        }

        fun refresh(context: Context) {
            if (!_status.value.running) return
            context.startService(Intent(context, GatewayService::class.java).setAction(ACTION_REFRESH))
        }

        fun rotateGuestCode(context: Context) {
            if (!_status.value.running) return
            context.startService(Intent(context, GatewayService::class.java).setAction(ACTION_ROTATE))
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
                    NotificationChannel(CHANNEL_ID, "Scanner server", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while the phone serves your scans to this network"
                    },
                )
            }
        }
    }
}
