package io.github.darylno.cardscanner.ui

import android.app.Activity
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import io.github.darylno.cardscanner.App
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Proxy

/**
 * The page's `confirm()` reaches the owner as a native dialog (an Android WebView
 * drops JavaScript dialogs unless a WebChromeClient shows them — phone.html's
 * "Clear all" asked and was silently told "no" on the phone), and every answer
 * is delivered exactly once.
 */
@RunWith(RobolectricTestRunner::class)
class JsDialogsTest {
    private val controllers = mutableListOf<ActivityController<*>>()

    @After fun tearDown() { controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } } }

    private fun panel(): PanelActivity {
        val app = ApplicationProvider.getApplicationContext<App>()
        val c = Robolectric.buildActivity(PanelActivity::class.java, PanelActivity.intent(app))
        controllers += c
        c.setup(); ShadowLooper.idleMainLooper()
        return c.get()
    }

    private fun findWebView(v: View): WebView? {
        if (v is WebView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun latestDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    private fun dialogText(d: AlertDialog, text: String): Boolean {
        fun walk(v: View): Boolean = (v is TextView && v.text.toString() == text) ||
            (v is ViewGroup && (0 until v.childCount).any { walk(v.getChildAt(it)) })
        return walk(d.window!!.decorView)
    }

    /** A real JsResult (its constructor is a system API) whose answer the test can read back. */
    private fun jsResult(): Pair<JsResult, () -> Boolean?> {
        val receiverCls = Class.forName("android.webkit.JsResult\$ResultReceiver")
        var answered: JsResult? = null
        // The receiver's one method is onJsResult (older SDKs) / onJsResultComplete (newer).
        val receiver = Proxy.newProxyInstance(receiverCls.classLoader, arrayOf(receiverCls)) { _, m, args ->
            if (m.name.startsWith("onJsResult")) answered = args!![0] as JsResult
            null
        }
        val r = JsResult::class.java.getDeclaredConstructor(receiverCls).apply { isAccessible = true }.newInstance(receiver)
        val getResult = JsResult::class.java.getDeclaredMethod("getResult").apply { isAccessible = true }
        // Robolectric shadows cancel() (ShadowJsResult.wasCancelled) without calling the receiver;
        // confirm() runs for real and does. Null = the page was never answered.
        return r to { if (shadowOf(r).wasCancelled()) false else answered?.let { getResult.invoke(it) as Boolean } }
    }

    @Test
    fun thePanelInstallsAChromeClient_andConfirmReachesTheOwner() {
        val a = panel()
        val web = findWebView(a.window.decorView)
        assertNotNull(web); web!!
        val chrome = shadowOf(web).webChromeClient
        assertNotNull("PanelActivity must show the page's JavaScript dialogs", chrome)

        // confirm() → a dialog with the page's words; OK answers true to the page.
        val (ok, okAnswer) = jsResult()
        assertTrue(chrome!!.onJsConfirm(web, "http://127.0.0.1/phone", "Delete ALL 3 scans? This cannot be undone.", ok))
        ShadowLooper.idleMainLooper()
        val d = latestDialog()
        assertTrue(d.isShowing)
        assertTrue(dialogText(d, "Delete ALL 3 scans? This cannot be undone."))
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(true, okAnswer())

        // Cancel answers false; so does dismissing the dialog without a button.
        val (no, noAnswer) = jsResult()
        chrome.onJsConfirm(web, "http://127.0.0.1/phone", "Delete 2 unpicked scans?", no); ShadowLooper.idleMainLooper()
        latestDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(false, noAnswer())
        val (gone, goneAnswer) = jsResult()
        chrome.onJsConfirm(web, "http://127.0.0.1/phone", "Delete 1 flagged scan?", gone); ShadowLooper.idleMainLooper()
        latestDialog().dismiss(); ShadowLooper.idleMainLooper()
        assertEquals(false, goneAnswer())

        // alert() → a dialog; OK (or any dismissal) lets the page continue.
        val (al, alAnswer) = jsResult()
        assertTrue(chrome.onJsAlert(web, "http://127.0.0.1/phone", "Nothing to delete.", al)); ShadowLooper.idleMainLooper()
        assertTrue(dialogText(latestDialog(), "Nothing to delete."))
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        assertEquals(true, alAnswer())
    }

    @Test
    fun anAnswerIsDeliveredExactlyOnce() {
        val a: Activity = panel()
        var answers = mutableListOf<Boolean>()
        val d = JsDialogs.confirm(a, "Sure?") { answers += it }
        assertNotNull(d); d!!
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper()
        d.dismiss(); ShadowLooper.idleMainLooper()                       // the dismiss after OK must not answer again
        assertEquals(listOf(true), answers)

        answers = mutableListOf()
        val d2 = JsDialogs.confirm(a, "Sure?") { answers += it }
        assertNotNull(d2); d2!!
        d2.cancel(); ShadowLooper.idleMainLooper()                       // Back
        assertEquals(listOf(false), answers)

        // A finishing activity can't show a dialog: the page is told "cancel" at once.
        a.finish()
        answers = mutableListOf()
        assertNull(JsDialogs.confirm(a, "Sure?") { answers += it })
        assertEquals(listOf(false), answers)
    }
}
