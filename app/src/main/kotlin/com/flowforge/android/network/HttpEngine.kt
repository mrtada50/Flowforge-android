package com.flowforge.android.network

import com.flowforge.android.core.common.Redactor
import com.flowforge.android.core.security.DomainAllowlist
import com.flowforge.android.domain.interfaces.HttpClientPort
import com.flowforge.android.domain.models.BodyType
import com.flowforge.android.domain.models.HttpEngineException
import com.flowforge.android.domain.models.HttpEngineException.Kind
import com.flowforge.android.domain.models.HttpRequestSpec
import com.flowforge.android.domain.models.HttpResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

data class HttpLimits(
    val maxResponseBytes: Long = 2L * 1024 * 1024,
    val maxRedirects: Int = 5,
)

/** In-memory cookie session. One jar per HttpEngine; clear() ends the session. */
class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, CopyOnWriteArrayList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { CopyOnWriteArrayList() }
        for (c in cookies) {
            list.removeAll { it.name == c.name && it.path == c.path }
            if (c.expiresAt > System.currentTimeMillis()) list.add(c)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host]?.filter { it.expiresAt > now && it.matches(url) } ?: emptyList()
    }

    fun clear() = store.clear()
}

/**
 * OkHttp-based engine. Every hop (including redirects) is checked against the allowlist before
 * a connection is made. Responses are capped; cancellation aborts the in-flight call.
 */
class HttpEngine(
    private val allowlistProvider: () -> DomainAllowlist,
    private val limits: HttpLimits = HttpLimits(),
    baseClient: OkHttpClient = OkHttpClient(),
    val cookieJar: MemoryCookieJar = MemoryCookieJar(),
) : HttpClientPort {

    private val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookieJar)
        .build()

    override suspend fun execute(spec: HttpRequestSpec): HttpResult {
        val method = spec.method.uppercase()
        if (method !in METHODS) throw HttpEngineException(Kind.INVALID_REQUEST, "Unsupported method: ${spec.method}")
        val url = buildUrl(spec)
        val allowlist = allowlistProvider()
        return coroutineScope {
            val active = AtomicReference<Call?>(null)
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    active.get()?.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) { executeBlocking(spec, method, url, allowlist, active) }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun executeBlocking(
        spec: HttpRequestSpec,
        initialMethod: String,
        initialUrl: HttpUrl,
        allowlist: DomainAllowlist,
        active: AtomicReference<Call?>,
    ): HttpResult {
        val started = System.nanoTime()
        var method = initialMethod
        var url = initialUrl
        var withBody = true
        var hops = 0
        while (true) {
            if (!allowlist.isAllowed(url.toString())) {
                throw HttpEngineException(Kind.BLOCKED, "Host is not in the allowlist: ${url.host}")
            }
            val request = buildRequest(spec, method, url, withBody)
            val callClient = client.newBuilder()
                .connectTimeout(spec.connectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(spec.readTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .callTimeout(spec.callTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            val call = callClient.newCall(request)
            active.set(call)
            val response = try {
                call.execute()
            } catch (e: InterruptedIOException) {
                if (call.isCanceled()) throw CancellationException("HTTP call cancelled")
                throw HttpEngineException(Kind.TIMEOUT, "Timed out: ${e.message ?: "no response"}", e)
            } catch (e: IOException) {
                if (call.isCanceled()) throw CancellationException("HTTP call cancelled")
                throw HttpEngineException(Kind.NETWORK, e.message ?: e::class.java.simpleName, e)
            }
            url = response.use { r ->
                if (spec.followRedirects && r.isRedirect) {
                    val location = r.header("Location")
                        ?: throw HttpEngineException(Kind.NETWORK, "Redirect without Location header")
                    val next = r.request.url.resolve(location)
                        ?: throw HttpEngineException(Kind.NETWORK, "Invalid redirect target: $location")
                    if (++hops > limits.maxRedirects) {
                        throw HttpEngineException(Kind.NETWORK, "Too many redirects (max ${limits.maxRedirects})")
                    }
                    if (r.code == 303 || ((r.code == 301 || r.code == 302) && method == "POST")) {
                        method = "GET"
                        withBody = false
                    }
                    next
                } else {
                    return readResult(r, url, started)
                }
            }
        }
    }

    private fun readResult(r: okhttp3.Response, url: HttpUrl, started: Long): HttpResult {
        val limit = limits.maxResponseBytes
        val buf = Buffer()
        var truncated = false
        val body = r.body
        var total = 0L
        try {
            val src = body.source()
            while (true) {
                val n = src.read(buf, 8192L)
                if (n == -1L) break
                total += n
                if (total > limit) {
                    truncated = true
                    break
                }
            }
        } catch (e: InterruptedIOException) {
            throw HttpEngineException(Kind.TIMEOUT, "Timed out while reading the response", e)
        } catch (e: IOException) {
            throw HttpEngineException(Kind.NETWORK, e.message ?: "Read failed", e)
        }
        val bytes = if (buf.size > limit) buf.readByteArray(limit) else buf.readByteArray()
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return HttpResult(
            status = r.code,
            headers = r.headers.map { it.first to it.second },
            body = String(bytes, charset),
            bodyTruncated = truncated,
            durationMs = (System.nanoTime() - started) / 1_000_000,
            finalUrl = url.toString(),
        )
    }

    private fun buildRequest(spec: HttpRequestSpec, method: String, url: HttpUrl, withBody: Boolean): Request {
        val b = Request.Builder().url(url)
        spec.headers.forEach { (k, v) ->
            try {
                b.addHeader(k, v)
            } catch (e: IllegalArgumentException) {
                throw HttpEngineException(Kind.INVALID_REQUEST, "Invalid header '$k'")
            }
        }
        val needsBody = method == "POST" || method == "PUT" || method == "PATCH"
        val canHaveBody = method != "GET" && method != "HEAD" && method != "OPTIONS" && withBody
        val body: RequestBody? = when {
            !canHaveBody -> null
            spec.bodyType == BodyType.JSON -> spec.body.toRequestBody("application/json; charset=utf-8".toMediaType())
            spec.bodyType == BodyType.RAW -> spec.body.toRequestBody(null)
            spec.bodyType == BodyType.URL_ENCODED -> FormBody.Builder().also { fb -> spec.fields.forEach { fb.add(it.first, it.second) } }.build()
            spec.bodyType == BodyType.FORM ->
                if (spec.fields.isEmpty()) FormBody.Builder().build()
                else MultipartBody.Builder().setType(MultipartBody.FORM).also { mb ->
                    spec.fields.forEach { mb.addFormDataPart(it.first, it.second) }
                }.build()
            needsBody -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        return b.method(method, if (needsBody && body == null) ByteArray(0).toRequestBody(null) else body).build()
    }

    companion object {
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        fun buildUrl(spec: HttpRequestSpec): HttpUrl {
            val base = spec.url.trim().toHttpUrlOrNull()
                ?: throw HttpEngineException(Kind.INVALID_REQUEST, "Invalid URL (http/https required): ${spec.url}")
            if (spec.query.isEmpty()) return base
            return base.newBuilder().also { b -> spec.query.forEach { b.addQueryParameter(it.first, it.second) } }.build()
        }
    }
}

/** Human-readable preview of what will be sent. Secrets are masked. */
object HttpRequestPreview {
    fun build(spec: HttpRequestSpec): String {
        val sb = StringBuilder()
        val url = try {
            HttpEngine.buildUrl(spec).toString()
        } catch (e: HttpEngineException) {
            spec.url
        }
        sb.append(spec.method.uppercase()).append(' ').append(Redactor.redact(url)).append('\n')
        spec.headers.forEach { (k, v) -> sb.append(k).append(": ").append(Redactor.headerValue(k, v)).append('\n') }
        val body = when (spec.bodyType) {
            BodyType.NONE -> ""
            BodyType.JSON, BodyType.RAW -> spec.body
            BodyType.FORM, BodyType.URL_ENCODED -> spec.fields.joinToString("&") { "${it.first}=${it.second}" }
        }
        if (body.isNotEmpty()) sb.append('\n').append(Redactor.redact(body)).append('\n')
        return sb.toString().trimEnd()
    }
}
