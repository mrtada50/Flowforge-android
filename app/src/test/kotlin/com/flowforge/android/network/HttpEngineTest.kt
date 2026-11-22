package com.flowforge.android.network

import com.flowforge.android.core.security.DomainAllowlist
import com.flowforge.android.domain.models.BodyType
import com.flowforge.android.domain.models.HttpEngineException
import com.flowforge.android.domain.models.HttpEngineException.Kind
import com.flowforge.android.domain.models.HttpRequestSpec
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var host: String

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        host = server.url("/").host
    }

    @After fun tearDown() = server.shutdown()

    private fun engine(allow: List<String> = listOf(host), limits: HttpLimits = HttpLimits()) =
        HttpEngine({ DomainAllowlist(allow) }, limits)

    private fun url(path: String = "/") = server.url(path).toString()

    private fun kindOf(block: suspend () -> Unit): Kind? = try {
        runBlocking { block() }
        null
    } catch (e: HttpEngineException) {
        e.kind
    }

    @Test fun successfulGet() = runBlocking {
        server.enqueue(MockResponse().setBody("hello").setHeader("X-A", "1"))
        val r = engine().execute(HttpRequestSpec(url = url("/p"), query = listOf("a" to "b c")))
        assertEquals(200, r.status)
        assertEquals("hello", r.body)
        assertEquals("1", r.header("x-a"))
        assertEquals("/p?a=b%20c", server.takeRequest().path)
    }

    @Test fun postJsonSendsBodyAndContentType() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        engine().execute(
            HttpRequestSpec(method = "POST", url = url(), bodyType = BodyType.JSON, body = """{"a":1}""",
                headers = listOf("X-Test" to "yes")),
        )
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("""{"a":1}""", req.body.readUtf8())
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("yes", req.getHeader("X-Test"))
    }

    @Test fun urlEncodedForm() = runBlocking {
        server.enqueue(MockResponse())
        engine().execute(HttpRequestSpec(method = "POST", url = url(), bodyType = BodyType.URL_ENCODED, fields = listOf("k" to "v w")))
        assertEquals("k=v%20w", server.takeRequest().body.readUtf8())
    }

    @Test fun headRequestWorks() = runBlocking {
        server.enqueue(MockResponse().setHeader("X-H", "1"))
        val r = engine().execute(HttpRequestSpec(method = "HEAD", url = url()))
        assertEquals("1", r.header("X-H"))
    }

    @Test fun hostOutsideAllowlistIsBlockedBeforeAnyConnection() {
        assertEquals(Kind.BLOCKED, kindOf { engine(allow = emptyList()).execute(HttpRequestSpec(url = url())) })
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectToAnotherHostIsBlocked() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.invalid/x"))
        assertEquals(Kind.BLOCKED, kindOf { engine().execute(HttpRequestSpec(url = url())) })
        assertEquals(1, server.requestCount)
    }

    @Test fun allowedRedirectIsFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(MockResponse().setBody("done"))
        val r = engine().execute(HttpRequestSpec(url = url()))
        assertEquals("done", r.body)
        assertTrue(r.finalUrl.endsWith("/final"))
    }

    @Test fun redirectsCanBeDisabled() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        val r = engine().execute(HttpRequestSpec(url = url(), followRedirects = false))
        assertEquals(302, r.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectLoopIsStopped() {
        repeat(10) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again")) }
        assertEquals(Kind.NETWORK, kindOf { engine(limits = HttpLimits(maxRedirects = 3)).execute(HttpRequestSpec(url = url())) })
    }

    @Test fun invalidUrlIsRejected() {
        assertEquals(Kind.INVALID_REQUEST, kindOf { engine().execute(HttpRequestSpec(url = "not a url")) })
        assertEquals(Kind.INVALID_REQUEST, kindOf { engine().execute(HttpRequestSpec(method = "TRACE", url = url())) })
    }

    @Test fun readTimeout() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(Kind.TIMEOUT, kindOf { engine().execute(HttpRequestSpec(url = url(), readTimeoutMs = 300)) })
    }

    @Test fun connectionFailureIsANetworkError() {
        // OkHttp retries a failed connection once or twice, so queue several failures.
        repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        assertEquals(Kind.NETWORK, kindOf { engine().execute(HttpRequestSpec(url = url())) })
    }

    @Test fun largeResponseIsTruncated() = runBlocking {
        server.enqueue(MockResponse().setBody("a".repeat(5000)))
        val r = engine(limits = HttpLimits(maxResponseBytes = 1000)).execute(HttpRequestSpec(url = url()))
        assertTrue(r.bodyTruncated)
        assertEquals(1000, r.body.length)
    }

    @Test fun smallResponseIsNotTruncated() = runBlocking {
        server.enqueue(MockResponse().setBody("abc"))
        assertFalse(engine().execute(HttpRequestSpec(url = url())).bodyTruncated)
    }

    @Test fun cookiesAreKeptWithinTheSession() = runBlocking {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "sid=abc; Path=/"))
        server.enqueue(MockResponse())
        val e = engine()
        e.execute(HttpRequestSpec(url = url()))
        server.takeRequest()
        e.execute(HttpRequestSpec(url = url()))
        assertEquals("sid=abc", server.takeRequest().getHeader("Cookie"))
    }

    @Test fun cancellationAbortsTheInFlightRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { engine().execute(HttpRequestSpec(url = url(), readTimeoutMs = 20_000, callTimeoutMs = 30_000)) }
        delay(300)
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test fun previewMasksSecrets() {
        val p = HttpRequestPreview.build(
            HttpRequestSpec(method = "POST", url = "https://a.test/x", headers = listOf("Authorization" to "Bearer zzz"),
                bodyType = BodyType.JSON, body = """{"password":"p"}"""),
        )
        assertFalse(p.contains("zzz"))
        assertFalse(p.contains("\"p\""))
        assertTrue(p.startsWith("POST https://a.test/x"))
    }
}
