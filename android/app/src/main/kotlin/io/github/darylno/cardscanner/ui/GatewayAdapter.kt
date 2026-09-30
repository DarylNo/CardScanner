package io.github.darylno.cardscanner.ui

import android.content.Context
import android.graphics.Bitmap
import io.github.darylno.cardscanner.gateway.GatewayService
import io.github.darylno.cardscanner.gateway.LocalAddresses
import io.github.darylno.cardscanner.gateway.QrBitmap

/** [GatewayPort] over the foreground service that serves the phone's scans (App wires its host). */
class GatewayAdapter(private val context: Context) : GatewayPort {
    private val st get() = GatewayService.status.value
    override val running: Boolean get() = st.running
    override val port: Int get() = st.port
    override val error: String? get() = st.error
    override fun code(): String = st.code
    override fun start() = GatewayService.start(context)
    override fun newGuestCode() = GatewayService.rotateGuestCode(context)
    override fun localAddresses(): List<String> = LocalAddresses.list()
    override fun joinUrl(ip: String): String = LocalAddresses.joinUrl(ip, port, code())
    override fun qr(text: String, sizePx: Int): Bitmap = QrBitmap.encode(text, sizePx)
}
