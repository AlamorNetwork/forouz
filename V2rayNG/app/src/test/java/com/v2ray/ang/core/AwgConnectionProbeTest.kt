package com.v2ray.ang.core

import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import javax.net.SocketFactory
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AwgConnectionProbeTest {
    @Test fun `DNS TLS socket and timeout failures are distinguishable without raw URL messages`() {
        assertEquals(AwgProbeFailure.DNS, awgIoFailure(java.net.UnknownHostException("secret host")))
        assertEquals(AwgProbeFailure.TLS, awgIoFailure(javax.net.ssl.SSLHandshakeException("private certificate")))
        assertEquals(AwgProbeFailure.CONNECT, awgIoFailure(java.net.ConnectException("private endpoint")))
        assertEquals(AwgProbeFailure.TIMEOUT, awgIoFailure(InterruptedIOException("private path")))
        assertEquals(AwgProbeFailure.NETWORK, awgIoFailure(IOException("private details")))
    }
    private class Body : ResponseBody() {
        var reads = 0
        var closed = false
        private val tracked = object : ForwardingSource(Buffer().writeUtf8("must not be downloaded")) {
            override fun read(sink: Buffer, byteCount: Long): Long { reads++; return super.read(sink, byteCount) }
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = 22
        override fun source(): BufferedSource = tracked
    }

    private class FakeCall(private val req: Request, private val action: (FakeCall, Callback) -> Unit) : Call {
        var cancelled = false
        var executed = false
        lateinit var callback: Callback
        val body = Body()
        fun response(status: Int) = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
            .code(status).message("test").body(body).build()
        override fun request(): Request = req
        override fun execute(): Response = error("Synchronous network execution is forbidden")
        override fun enqueue(responseCallback: Callback) {
            executed = true; callback = responseCallback; action(this, responseCallback)
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout()
        override fun clone(): Call = FakeCall(req, action)
        override fun addEventListener(eventListener: EventListener) = Unit
        override fun <T : Any> tag(type: KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }

    @Test fun `successful primary measures headers only and cancels before reading response body`() = runBlocking {
        var clock = 1_000L
        val calls = mutableListOf<FakeCall>()
        val factory = Call.Factory { request -> FakeCall(request) { call, callback ->
            clock += 37
            callback.onResponse(call, call.response(204))
        }.also { calls += it } }
        val result = AwgConnectionProbe(factory, { clock }).measure("https://first.test/generate_204", "https://second.test/")
        assertEquals(AwgProbeOutcome(37), result)
        assertEquals(1, calls.size)
        assertTrue(calls.single().cancelled)
        assertTrue(calls.single().body.closed)
        assertEquals(0, calls.single().body.reads)
    }

    @Test fun `failed primary uses the fallback through the same supplied transport with its own measured duration`() = runBlocking {
        var clock = 0L
        val hosts = mutableListOf<String>()
        val factory = Call.Factory { request -> FakeCall(request) { call, callback ->
            hosts += request.url.host
            clock += if (hosts.size == 1) 120 else 24
            callback.onResponse(call, call.response(if (hosts.size == 1) 503 else 200))
        } }
        val result = AwgConnectionProbe(factory, { clock }).measure("https://first.test/", "https://second.test/")
        assertEquals(listOf("first.test", "second.test"), hosts)
        assertEquals(AwgProbeOutcome(24), result)
    }

    @Test fun `HTTP redirects and error responses cannot report success or duplicate a fallback URL`() = runBlocking {
        for (status in listOf(301, 403, 500)) {
            var count = 0
            val factory = Call.Factory { request -> FakeCall(request) { call, callback ->
                count++; callback.onResponse(call, call.response(status))
            } }
            assertEquals(AwgProbeFailure.HTTP_RESPONSE,
                AwgConnectionProbe(factory).measure("https://example.test/", "https://example.test/").failure)
            assertEquals(1, count)
        }
    }

    @Test fun `malformed plaintext and credential-bearing URLs never reach transport`() = runBlocking {
        val factory = Call.Factory { error("Invalid URL reached transport") }
        for (url in listOf("", "broken", "http://example.test/", "ftp://example.test/",
            "https://user:password@example.test/")) {
            assertEquals(AwgProbeOutcome(failure = AwgProbeFailure.INVALID_URL),
                AwgConnectionProbe(factory).measure(url, url))
        }
    }

    @Test fun `network error messages cannot leak into the neutral outcome`() = runBlocking {
        val factory = Call.Factory { request -> FakeCall(request) { call, callback ->
            callback.onFailure(call, IOException("secret endpoint and credentials"))
        } }
        val result = AwgConnectionProbe(factory).measure("https://example.test/", "")
        assertEquals(AwgProbeOutcome(failure = AwgProbeFailure.NETWORK), result)
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun `transport timeout has an explicit outcome`() = runBlocking {
        val factory = Call.Factory { request -> FakeCall(request) { call, callback ->
            callback.onFailure(call, InterruptedIOException("timeout"))
        } }
        assertEquals(AwgProbeFailure.TIMEOUT, AwgConnectionProbe(factory).measure("https://example.test/", "").failure)
    }

    @Test fun `overall deadline cancels a request whose transport never responds`() = runBlocking {
        lateinit var call: FakeCall
        val factory = Call.Factory { request -> FakeCall(request) { _, _ -> }.also { call = it } }
        val result = withTimeout(2_000) {
            AwgConnectionProbe(factory, budgetMillis = 40).measure("https://example.test/", "https://fallback.test/")
        }
        assertEquals(AwgProbeFailure.TIMEOUT, result.failure)
        assertTrue(call.cancelled)
    }

    @Test fun `stop cancellation closes transport and late success cannot publish a result`() = runBlocking {
        lateinit var call: FakeCall
        var result: AwgProbeOutcome? = null
        val factory = Call.Factory { request -> FakeCall(request) { _, _ -> }.also { call = it } }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            result = AwgConnectionProbe(factory).measure("https://example.test/", "")
        }
        job.cancelAndJoin()
        assertTrue(call.cancelled)
        call.callback.onResponse(call, call.response(204))
        assertNull(result)
        assertTrue(call.body.closed)
    }

    @Test fun `parent timeout is propagated instead of being converted to an owned timeout result`() {
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                val factory = Call.Factory { request -> FakeCall(request) { _, _ -> } }
                withTimeout(40) { AwgConnectionProbe(factory).measure("https://example.test/", "") }
            }
        }
    }

    @Test fun `client retains both supplied VPN factories and disables proxy redirects and idle reuse`() {
        val sockets = SocketFactory.getDefault()
        val dns = Dns { listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }
        val client = awgProbeClient(sockets, dns)
        try {
            assertSame(sockets, client.socketFactory)
            assertSame(dns, client.dns)
            assertSame(Proxy.NO_PROXY, client.proxy)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertEquals(0, client.connectionPool.idleConnectionCount())
        } finally { client.dispatcher.executorService.shutdownNow() }
    }
}
