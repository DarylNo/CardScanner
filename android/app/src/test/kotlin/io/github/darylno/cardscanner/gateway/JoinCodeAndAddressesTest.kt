package io.github.darylno.cardscanner.gateway

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.darylno.cardscanner.gateway.LocalAddresses.Iface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinCodeAndAddressesTest {

    @Test
    fun codeIsSixDigitsAndRotates() {
        val j = JoinCode()
        val seen = HashSet<String>()
        repeat(50) {
            assertTrue(j.current, Regex("^[0-9]{6}$").matches(j.current))
            seen += j.current
            j.rotate()
        }
        assertTrue(seen.size > 40)
    }

    @Test
    fun matchesOnlyTheCurrentCode() {
        val j = JoinCode()
        val c = j.current
        assertTrue(j.matches(c))
        assertTrue(j.matches(" ${c.take(3)} ${c.drop(3)} "))
        assertTrue(j.matches("${c.take(3)}-${c.drop(3)}"))
        assertFalse(j.matches(null))
        assertFalse(j.matches(""))
        assertFalse(j.matches(c.dropLast(1)))
        assertFalse(j.matches(c + "0"))
        val other = ((c.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertFalse(j.matches(other))
        j.rotate()
        if (j.current != c) assertFalse(j.matches(c))
    }

    @Test
    fun addressesExcludeLoopbackTailscaleCellularAndOrderWifiFirst() {
        val got = LocalAddresses.select(listOf(
            Iface("lo", up = true, loopback = true, ipv4 = listOf("127.0.0.1")),
            Iface("rmnet_data0", up = true, loopback = false, ipv4 = listOf("10.45.3.2")),
            Iface("tun0", up = true, loopback = false, ipv4 = listOf("100.101.5.6")),
            Iface("ap0", up = true, loopback = false, ipv4 = listOf("192.168.43.1")),
            Iface("wlan1", up = false, loopback = false, ipv4 = listOf("192.168.9.9")),
            Iface("eth0", up = true, loopback = false, ipv4 = listOf("169.254.1.1", "10.0.0.8")),
            Iface("wlan0", up = true, loopback = false, ipv4 = listOf("192.168.1.23", "100.64.0.1")),
        ))
        assertEquals(listOf("192.168.1.23", "192.168.43.1", "10.0.0.8"), got)
    }

    @Test
    fun tailscaleRangeIsExactlySlash10() {
        assertTrue(LocalAddresses.isTailscale("100.64.0.0"))
        assertTrue(LocalAddresses.isTailscale("100.127.255.255"))
        assertFalse(LocalAddresses.isTailscale("100.63.255.255"))
        assertFalse(LocalAddresses.isTailscale("100.128.0.0"))
        assertFalse(LocalAddresses.isTailscale("192.168.1.2"))
    }

    @Test
    fun joinUrlAndQrRoundTrip() {
        val u = LocalAddresses.joinUrl("192.168.1.23", 8080, "042917")
        assertEquals("http://192.168.1.23:8080/join?code=042917", u)
        val m = QrBitmap.matrix(u, 300)
        assertEquals(300, m.width)
        val px = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) 0xff000000.toInt() else -1 }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, px))))
        assertEquals(u, decoded.text)
    }
}
