package io.github.darylno.cardscanner.f2f

import android.content.Context
import okhttp3.Call
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.CronetProvider
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Plain OkHttp — the app's normal HTTP stack, i.e. an OkHttp/Conscrypt TLS
 * fingerprint (the analogue of the rig's plain-`requests` fallback, which
 * Shopify gave a tiny bucket). Its own client: no pinning, no cache, no
 * silent retries, 8 s total per call like facetoface._TIMEOUT.
 */
class OkHttpTransport(client: OkHttpClient? = null) : F2fTransport {
    override val name = "okhttp"
    override val detail = "OkHttp ${OkHttp.VERSION}"
    private val client = client ?: OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .build()
    @Volatile private var current: Call? = null

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Long): TransportResponse {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        val call = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(rb.build())
        current = call
        try {
            call.execute().use { r ->
                return TransportResponse(r.code, r.body?.string() ?: "", r.protocol.toString(), r.header("Retry-After"))
            }
        } finally {
            current = null
        }
    }

    override fun cancel() {
        current?.cancel()
    }
}

/**
 * Cronet = Chromium's own network stack (BoringSSL with Chrome's ClientHello,
 * HTTP/2 + QUIC), i.e. a REAL Chrome TLS handshake — what curl_cffi's
 * impersonate="chrome" imitates on the rig. Uses the APP-PACKAGED provider
 * (cronet-embedded), never Play Services or the platform HttpEngine, so the
 * measured stack is the one we ship. HTTP cache disabled.
 */
class CronetTransport private constructor(
    private val engine: CronetEngine,
    private val providerDesc: String,
) : F2fTransport {
    override val name = "cronet"
    override val detail: String get() = "Cronet ${engine.versionString} ($providerDesc)"
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "cronet-probe") }
    @Volatile private var current: UrlRequest? = null

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Long): TransportResponse {
        val done = CountDownLatch(1)
        val body = ByteArrayOutputStream()
        var info: UrlResponseInfo? = null
        var failure: CronetException? = null
        var canceled = false
        val cb = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, i: UrlResponseInfo, newLocationUrl: String) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, i: UrlResponseInfo) {
                info = i
                request.read(ByteBuffer.allocateDirect(32 * 1024))
            }

            override fun onReadCompleted(request: UrlRequest, i: UrlResponseInfo, buf: ByteBuffer) {
                buf.flip()
                val chunk = ByteArray(buf.remaining())
                buf.get(chunk)
                body.write(chunk)
                buf.clear()
                request.read(buf)
            }

            override fun onSucceeded(request: UrlRequest, i: UrlResponseInfo) {
                info = i; done.countDown()
            }

            override fun onFailed(request: UrlRequest, i: UrlResponseInfo?, error: CronetException) {
                failure = error; done.countDown()
            }

            override fun onCanceled(request: UrlRequest, i: UrlResponseInfo?) {
                canceled = true; done.countDown()
            }
        }
        val b = engine.newUrlRequestBuilder(url, cb, executor).disableCache()
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        val req = b.build()
        current = req
        req.start()
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                req.cancel()
                done.await(2, TimeUnit.SECONDS)
                throw SocketTimeoutException("timeout after ${timeoutMs} ms")
            }
        } catch (e: InterruptedException) {
            req.cancel()
            throw InterruptedIOException("interrupted")
        } finally {
            current = null
        }
        if (canceled) throw InterruptedIOException("canceled")
        failure?.let { throw it }
        val i = info ?: throw IOException("no response info")
        val retryAfter = i.allHeaders.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.firstOrNull()
        return TransportResponse(i.httpStatusCode, body.toString(Charsets.UTF_8.name()), i.negotiatedProtocol.ifEmpty { null }, retryAfter)
    }

    override fun cancel() {
        current?.cancel()
    }

    override fun close() {
        runCatching { engine.shutdown() }
        executor.shutdown()
    }

    companion object {
        /** Build on the app-packaged provider; throws if cronet-embedded's natives can't load. */
        fun create(ctx: Context): CronetTransport {
            val providers = CronetProvider.getAllProviders(ctx)
            val p = providers.firstOrNull { it.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED && it.isEnabled }
                ?: throw IllegalStateException("no app-packaged Cronet provider (have: " +
                    providers.joinToString { "${it.name} ${it.version} enabled=${it.isEnabled}" } + ")")
            val engine = p.createBuilder()
                .setUserAgent(F2f.USER_AGENT)
                .enableHttp2(true)
                .enableQuic(true)          // as Chrome does; the report records the negotiated protocol
                .enableBrotli(true)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0)
                .build()
            return CronetTransport(engine, "${p.name} ${p.version}")
        }
    }
}
