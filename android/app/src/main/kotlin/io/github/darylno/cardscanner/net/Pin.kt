package io.github.darylno.cardscanner.net

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * The server's identity = the SHA-256 of its certificate's DER bytes, as
 * lowercase hex (exactly what `mtg_card_scanner.launch.cert_sha256` computes
 * and what the desktop's phone QR carries as `#pin=…`).
 *
 * Why a pin and not a CA: the server's cert is self-signed, CN-only (no
 * subjectAlternativeName — see launch.py `ensure_certs`), and answers on
 * several names (LAN IP, Tailscale MagicDNS, tailnet IPs). Nothing a CA or a
 * hostname check could vouch for; the pin vouches for all of them at once.
 */
object Pin {
    /** Length of a SHA-256 in hex. */
    const val HEX_LENGTH = 64

    private val HEX = Regex("^[0-9a-f]{64}$")

    fun sha256(der: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(der)

    fun sha256Hex(der: ByteArray): String = toHex(sha256(der))

    /** Lowercase hex SHA-256 of [cert]'s DER encoding — the pin format. */
    fun sha256Hex(cert: X509Certificate): String = sha256Hex(cert.encoded)

    /**
     * Canonical pin form (64 lowercase hex chars), or null if [input] isn't
     * one. Tolerates what a human might paste: upper case, `AB:CD:…` colons,
     * spaces.
     */
    fun normalize(input: String?): String? {
        if (input == null) return null
        val s = input.filterNot { it == ':' || it.isWhitespace() }.lowercase()
        return if (HEX.matches(s)) s else null
    }

    fun isValid(pin: String?): Boolean = pin != null && HEX.matches(pin)

    /**
     * Does [cert] hash to [pin]? Constant-time over the digest bytes
     * ([MessageDigest.isEqual]), so a probing peer learns nothing from timing.
     * An unparseable pin never matches.
     */
    fun matches(cert: X509Certificate, pin: String?): Boolean {
        val want = normalize(pin)?.let(::fromHex) ?: return false
        val got = try {
            sha256(cert.encoded)
        } catch (_: Exception) {
            return false
        }
        return MessageDigest.isEqual(got, want)
    }

    /** "AB:CD:EF:…" — how browsers/OS cert viewers display a fingerprint. */
    fun formatFingerprint(hex: String): String =
        (normalize(hex) ?: hex.lowercase()).uppercase().chunked(2).joinToString(":")

    /**
     * The pin from a desktop QR URL: `https://<ip>:8443/phone#pin=<64 hex>`.
     * The fragment may carry other `&`-separated params; only a well-formed
     * 64-hex `pin` is accepted (a truncated/garbled QR read must never become
     * a pin — the caller then falls back to the probe + compare flow).
     */
    fun parsePinFromQrUrl(url: String?): String? {
        if (url == null) return null
        val hash = url.indexOf('#')
        if (hash < 0) return null
        for (param in url.substring(hash + 1).split('&')) {
            val eq = param.indexOf('=')
            if (eq < 0) continue
            if (param.substring(0, eq).trim().equals("pin", ignoreCase = true)) {
                val v = param.substring(eq + 1).trim().lowercase()
                return if (HEX.matches(v)) v else null
            }
        }
        return null
    }

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0f])
        }
        return out.toString()
    }

    private fun fromHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private const val DIGITS = "0123456789abcdef"
}
