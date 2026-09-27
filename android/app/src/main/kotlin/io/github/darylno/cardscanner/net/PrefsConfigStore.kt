package io.github.darylno.cardscanner.net

import android.content.Context
import org.json.JSONArray

/**
 * [ConfigStore] on SharedPreferences. `commit()` (synchronous), not apply():
 * a pairing that "succeeded" must survive the process being killed right
 * after. The pin is public data — app-private storage gives the integrity
 * that matters; no encryption needed (research_4 §1.2).
 */
class PrefsConfigStore(context: Context) : ConfigStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("server_config", Context.MODE_PRIVATE)

    override fun load(): ServerConfig? {
        val pin = Pin.normalize(prefs.getString(KEY_PIN, null)) ?: return null
        val raw = prefs.getString(KEY_URLS, null) ?: return null
        val urls = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { normalizeBaseUrl(arr.optString(it)) }.distinct()
        } catch (_: Exception) {
            return null
        }
        if (urls.isEmpty()) return null
        return ServerConfig(urls, pin)
    }

    override fun save(c: ServerConfig) {
        prefs.edit()
            .putString(KEY_URLS, JSONArray(c.urls).toString())
            .putString(KEY_PIN, c.pin)
            .commit()
    }

    override fun lastGood(): String? = prefs.getString(KEY_LAST_GOOD, null)

    override fun setLastGood(url: String) {
        prefs.edit().putString(KEY_LAST_GOOD, url).commit()
    }

    /** Forget the pairing (re-pair from scratch). */
    fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_URLS = "urls"
        const val KEY_PIN = "pin_sha256"
        const val KEY_LAST_GOOD = "last_good"
    }
}
