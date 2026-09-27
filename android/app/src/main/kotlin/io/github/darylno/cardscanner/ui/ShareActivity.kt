package io.github.darylno.cardscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.darylno.cardscanner.App

/**
 * Guest gateway: people without Tailscale join the phone's local network
 * (shop Wi-Fi or this phone's hotspot), scan the QR / type the 6-digit code,
 * and get the FULL review UI proxied to the home server (the user's choice —
 * the warning below says exactly what that means).
 */
class ShareActivity : AppCompatActivity() {
    private lateinit var app: App
    private lateinit var body: LinearLayout
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0f1115"))
            addView(body)
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun text(t: String, size: Float = 15f, color: Int = Color.parseColor("#e5e7eb")) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
    }

    private fun render() {
        body.removeAllViews()
        val gw = app.gateway
        body.addView(text("Share the review pages", 20f, Color.WHITE))
        body.addView(text(
            "Guests have full access: they can pick, edit, delete, clear, export and update — " +
                "only share the code with people you trust.", 15f, Color.parseColor("#fbbf24"),
        ))
        gw.error?.let { body.addView(text("Couldn't start sharing: $it", 15f, Color.parseColor("#f87171"))) }
        if (!gw.running) {
            body.addView(text("Guests on the same Wi-Fi (or on this phone's hotspot) open a link and enter a 6-digit code. No Tailscale needed."))
            body.addView(Button(this).apply {
                text = "Start sharing"
                isAllCaps = false
                setOnClickListener { start() }
            })
        } else {
            val addrs = gw.localAddresses()
            val code = gw.code()
            if (addrs.isEmpty()) {
                body.addView(text(
                    "No Wi-Fi address — turn on this phone's hotspot (Settings → Hotspot) and have guests join it, then come back here.",
                    15f, Color.parseColor("#f87171"),
                ))
            } else {
                val url = gw.joinUrl(addrs.first())
                val size = dp(260)
                body.addView(ImageView(this).apply {
                    setImageBitmap(gw.qr(url, size))
                    contentDescription = url
                }, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL })
                body.addView(text("Scan the QR, or open one of these and enter the code:"))
                for (a in addrs) body.addView(text("http://$a:${gw.port}", 16f, Color.WHITE))
            }
            body.addView(text("Join code", 14f))
            body.addView(TextView(this).apply {
                text = code.chunked(3).joinToString(" ")
                textSize = 48f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_HORIZONTAL
                letterSpacing = 0.1f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(Button(this).apply {
                text = "Stop sharing (new code next time)"
                isAllCaps = false
                setOnClickListener { gw.stop(); render() }
            })
        }
        body.addView(Button(this).apply {
            text = "Back"
            isAllCaps = false
            setOnClickListener { finish() }
        })
    }

    private fun start() {
        // The foreground-service notification (with its Stop action) needs this on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            app.gateway.start()
        } catch (e: Exception) {
            body.addView(text("Couldn't start sharing: ${e.message}", 15f, Color.parseColor("#f87171")), 1)
            return
        }
        body.postDelayed({ render() }, 500)
        body.postDelayed({ render() }, 2000)
    }
}
