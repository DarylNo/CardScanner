package io.github.darylno.cardscanner.net

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/** Thrown by the probe trust manager once it has recorded the leaf: aborts the handshake. */
class ProbeCapturedException(val fingerprintHex: String) :
    CertificateException("unpinned probe: server certificate SHA-256 $fingerprintHex")

/** Thrown inside the handshake when the leaf does not hash to the pin. */
class PinMismatchCertificateException(val seenFingerprintHex: String) :
    CertificateException("pin mismatch: server presented $seenFingerprintHex")

/**
 * The server's certificate is not the one we paired with. NEVER failover-able
 * and never auto-accepted: the UI must send the user to re-pair (certs deleted,
 * reinstall, another PC — or someone in the middle; the app can't tell which).
 * An [IOException] so generic transport handling (the upload queue's retry)
 * keeps the scan queued until the user re-pairs.
 */
class PinMismatchException(val url: String, val seenFingerprintHex: String?) :
    IOException(
        "Server certificate changed at $url" +
            (seenFingerprintHex?.let { " (now ${Pin.formatFingerprint(it)})" } ?: "") +
            " — re-pair in Settings",
    )

/**
 * Trust = "the leaf's SHA-256 equals the pin", nothing else (no CA path, no
 * validity dates: the server keeps serving its cert past expiry and the pin is
 * the identity — research_4 §1.1).
 *
 * [pin] == null is the PROBE mode: record the leaf and throw, so the handshake
 * dies before a single request byte is sent.
 *
 * [getAcceptedIssuers] returns the pinned cert once seen: OkHttp's
 * `Handshake.peerCertificates` runs a chain cleaner that needs an accepted
 * issuer, and silently yields an empty list without one (measured, §1.3 case 4).
 */
@SuppressLint("CustomX509TrustManager") // sole trust anchor = user-confirmed leaf hash (TOFU / QR pin)
class PinnedTrustManager(pin: String?) : X509TrustManager {
    val pin: String? = pin?.let { Pin.normalize(it) ?: throw IllegalArgumentException("malformed pin") }

    /** The last leaf any handshake presented (probe result / mismatch UI). */
    @Volatile
    var lastSeen: X509Certificate? = null
        private set

    @Volatile
    private var accepted: X509Certificate? = null

    /**
     * Per-thread mismatch marker. OkHttp's synchronous calls handshake on the
     * calling thread, and Android/Conscrypt may wrap our exception differently
     * than SunJSSE, so [ServerClient] reads this instead of trusting the cause
     * chain alone.
     */
    internal val mismatchOnThread = ThreadLocal<String?>()

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        lastSeen = leaf
        val want = pin ?: throw ProbeCapturedException(Pin.sha256Hex(leaf))
        if (!Pin.matches(leaf, want)) {
            val seen = Pin.sha256Hex(leaf)
            mismatchOnThread.set(seen)
            throw PinMismatchCertificateException(seen)
        }
        accepted = leaf
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
        throw CertificateException("client authentication is not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        accepted?.let { arrayOf(it) } ?: emptyArray()
}

/**
 * Host-INDEPENDENT verifier: the server's cert is CN-only (no SAN), and
 * OkHttp's default verifier checks SANs only with no CN fallback, so it can
 * never pass (research_4 §1.3 case 3). The pin already names the server, on
 * every address it answers (LAN IP, MagicDNS, tailnet IPs, DHCP moves).
 */
class PinnedHostnameVerifier(private val pin: String?) : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean = try {
        val leaf = session?.peerCertificates?.firstOrNull() as? X509Certificate
        leaf != null && pin != null && Pin.matches(leaf, pin)
    } catch (_: Exception) {
        false
    }
}

/** connect 4 s (a dead address must fail over fast), read 120 s (sync price calls), write 60 s. */
data class Timeouts(
    val connectMs: Long = 4_000,
    val readMs: Long = 120_000,
    val writeMs: Long = 60_000,
)

/** A client together with the trust manager it was built on (the same instance — required). */
class PinnedClient(val client: OkHttpClient, val trustManager: PinnedTrustManager)

/**
 * Build an OkHttp client that trusts exactly the certificate hashing to [pin]
 * (or, with pin == null, a probe client that records the leaf and aborts).
 * Never uses OkHttp's CertificatePinner (SPKI pins + chain cleaner; §1.3).
 */
fun pinnedClient(pin: String?, timeouts: Timeouts = Timeouts()): PinnedClient {
    val tm = PinnedTrustManager(pin)
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    val client = OkHttpClient.Builder()
        .sslSocketFactory(ctx.socketFactory, tm)
        .hostnameVerifier(PinnedHostnameVerifier(tm.pin))
        .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.writeMs, TimeUnit.MILLISECONDS)
        .build()
    return PinnedClient(client, tm)
}

/** Was [e] (any depth) caused by a pin mismatch? Returns the seen fingerprint, or null. */
internal fun pinMismatchIn(e: Throwable, tm: PinnedTrustManager?): String? {
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth < 16) {
        if (t is PinMismatchCertificateException) return t.seenFingerprintHex
        if (t is PinMismatchException) return t.seenFingerprintHex ?: ""
        t = t.cause
        depth++
    }
    return tm?.mismatchOnThread?.get()
}
