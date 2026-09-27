package io.github.darylno.cardscanner.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinAndConfigTest {
    private val hex = "15f9fa" + "0".repeat(52) + "abf977"

    @Test
    fun parsePinFromQrUrl() {
        assertEquals(hex, Pin.parsePinFromQrUrl("https://192.168.1.42:8443/phone#pin=$hex"))
        assertEquals(hex, Pin.parsePinFromQrUrl("https://h:8443/phone#x=1&pin=${hex.uppercase()}"))
        assertNull(Pin.parsePinFromQrUrl("https://192.168.1.42:8443/phone"))
        assertNull(Pin.parsePinFromQrUrl("https://h/phone#pin=${hex.dropLast(1)}"))   // truncated read
        assertNull(Pin.parsePinFromQrUrl("https://h/phone#pin=${hex.dropLast(1)}z"))
        assertNull(Pin.parsePinFromQrUrl(null))
    }

    @Test
    fun formatFingerprint() {
        val f = Pin.formatFingerprint(hex)
        assertEquals(95, f.length)
        assertEquals("15:F9:FA:00", f.substring(0, 11))
        assertEquals(hex, Pin.normalize(f))
    }

    @Test
    fun normalizeBaseUrl() {
        assertEquals("https://192.168.1.5:8443", normalizeBaseUrl("192.168.1.5"))
        assertEquals("https://host:9000", normalizeBaseUrl("host:9000"))
        assertEquals("https://host:8443", normalizeBaseUrl(" HOST:8443/ "))
        assertEquals("https://192.168.1.42:8443", normalizeBaseUrl("https://192.168.1.42:8443/phone#pin=$hex"))
        assertEquals("https://192.168.1.42:8443", normalizeBaseUrl("http://192.168.1.42:8443/phone?x=1"))
        assertEquals("https://rig.tail123.ts.net:8443", normalizeBaseUrl("rig.tail123.ts.net"))
        assertEquals("https://[fd7a:115c::1]:8443", normalizeBaseUrl("[fd7a:115c::1]"))
        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("ftp://host"))
        assertNull(normalizeBaseUrl("host:notaport"))
        assertNull(normalizeBaseUrl("host:70000"))
        assertNull(normalizeBaseUrl("bad host"))
    }
}
