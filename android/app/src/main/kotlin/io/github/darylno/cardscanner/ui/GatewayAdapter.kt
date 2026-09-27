package io.github.darylno.cardscanner.ui

import android.content.Context
import android.graphics.Bitmap
import io.github.darylno.cardscanner.gateway.GatewayService
import io.github.darylno.cardscanner.gateway.LocalAddresses
import io.github.darylno.cardscanner.gateway.QrBitmap
import io.github.darylno.cardscanner.gateway.Upstream

/** [GatewayPort] over the GATEWAY agent's foreground service + helpers. */
class GatewayAdapter(private val context: Context, upstream: () -> Upstream) : GatewayPort {
    init {
        GatewayService.upstreamProvider = upstream
    }

    private val st get() = GatewayService.status.value
    override val running: Boolean get() = st.running
    override val port: Int get() = st.port
    override val error: String? get() = st.error
    override fun code(): String = st.code
    override fun start() = GatewayService.start(context)
    override fun stop() = GatewayService.stop(context)
    override fun localAddresses(): List<String> = LocalAddresses.list()
    override fun joinUrl(ip: String): String = LocalAddresses.joinUrl(ip, port, code())
    override fun qr(text: String, sizePx: Int): Bitmap = QrBitmap.encode(text, sizePx)
}
