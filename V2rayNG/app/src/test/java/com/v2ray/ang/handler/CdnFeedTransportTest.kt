package com.v2ray.ang.handler

import com.v2ray.ang.core.cdn.CdnPolicy
import com.v2ray.ang.core.privacy.PrivacyRoute
import com.v2ray.ang.dto.ConnectionSnapshot
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.Proxy
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class CdnFeedTransportTest {
    @Test fun `explicit mirror URL replaces GitHub without redirect or authentication`() = runBlocking {
        val call = mock<Call>()
        val factory = Call.Factory { request ->
            assertEquals("https://mirror.example/forouz/feed.json", request.url.toString())
            assertNull(request.header("Authorization")); assertNull(request.header("Proxy-Authorization"))
            whenever(call.enqueue(any())).thenAnswer { args ->
                args.getArgument<Callback>(0).onResponse(call, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body("catalog".toResponseBody()).build()); null
            }
            call
        }
        assertEquals("catalog", fetchCdnFeed(factory, "https://mirror.example/forouz/feed.json"))
    }
    @Test fun `timeout while reading response body is a network timeout rather than invalid catalog`() = runBlocking {
        val call = mock<Call>()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw SocketTimeoutException("fixture")
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }.buffer()
        }
        val factory = Call.Factory { request ->
            whenever(call.enqueue(any())).thenAnswer { args ->
                args.getArgument<Callback>(0).onResponse(call, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(body).build()); null
            }; call
        }
        val error = runCatching { fetchCdnFeed(factory) }.exceptionOrNull() as CdnFeedException
        assertEquals(CdnFeedIssue.TIMEOUT, error.issue)
    }
    @Test fun `only fresh running daemon Xray snapshots admit the actual SOCKS port`() {
        val snapshot = ConnectionSnapshot("session", "guid", 100, 1000,
            privacyRoute = PrivacyRoute.XRAY_SOCKS, privacyProxyPort = 12345)
        assertEquals(12345, cdnFeedProxyPort(snapshot, 6000))
        for (invalid in listOf(null, snapshot.copy(running = false), snapshot.copy(sessionId = ""),
            snapshot.copy(profileGuid = ""), snapshot.copy(sampledAt = 6001), snapshot.copy(sampledAt = 999),
            snapshot.copy(privacyRoute = PrivacyRoute.UNAVAILABLE), snapshot.copy(privacyProxyPort = null),
            snapshot.copy(privacyProxyPort = 0), snapshot.copy(privacyProxyPort = 65536)))
            assertNull(cdnFeedProxyPort(invalid, 6000))
    }
    @Test fun `admitted feed client uses loopback SOCKS without direct or redirect fallback`() {
        val client = cdnFeedClient(12345)
        assertEquals(Proxy.Type.SOCKS, client.proxy?.type())
        assertEquals(InetSocketAddress("127.0.0.1", 12345), client.proxy?.address())
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertThrows(IllegalArgumentException::class.java) { cdnFeedClient(0) }
        client.dispatcher.executorService.shutdown()
    }
    @Test fun `transport failures retain safe actionable issue without raw error content`() = runBlocking {
        for ((error, issue) in listOf(IOException("private endpoint") to CdnFeedIssue.NETWORK,
            SSLException("private certificate") to CdnFeedIssue.TLS, SocketTimeoutException("private address") to CdnFeedIssue.TIMEOUT)) {
            val call = mock<Call>()
            whenever(call.enqueue(any())).thenAnswer { args -> args.getArgument<Callback>(0).onFailure(call, error); null }
            val result = runCatching { fetchCdnFeed(Call.Factory { call }) }.exceptionOrNull() as CdnFeedException
            assertEquals(issue, result.issue); assertEquals(issue.name, result.message); assertNull(result.cause)
        }
    }
    @Test fun `feed endpoint has no credentials redirects or proxy fallback`() {
        val client = cdnFeedClient()
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        client.dispatcher.executorService.shutdown()
    }
    @Test fun `exact endpoint bounded body and unsuccessful status are enforced`() = runBlocking {
        for ((code, body, success) in listOf(Triple(200, "catalog", true), Triple(302, "redirect", false),
            Triple(200, "x".repeat(2_000_001), false), Triple(403, "denied", false))) {
            val call = mock<Call>()
            val factory = Call.Factory { request ->
                assertEquals(CdnPolicy.FEED, request.url.toString())
                assertNull(request.header("Authorization")); assertNull(request.header("Proxy-Authorization"))
                whenever(call.enqueue(any())).thenAnswer { args ->
                    args.getArgument<Callback>(0).onResponse(call, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(code).message("fixture").body(body.toResponseBody()).build()); null
                }; call
            }
            val result = runCatching { fetchCdnFeed(factory) }
            assertEquals(success, result.isSuccess)
            if (!success) {
                val error = result.exceptionOrNull() as CdnFeedException
                assertEquals(if (code == 200) CdnFeedIssue.BODY else CdnFeedIssue.HTTP, error.issue)
                assertEquals(code.takeUnless { it == 200 }, error.httpCode)
            }
            verify(call).cancel()
        }
    }
    @Test fun `cancellation cancels the actual in flight feed call`() = runBlocking {
        val ready = CompletableDeferred<Unit>(); val call = mock<Call>()
        whenever(call.enqueue(any())).thenAnswer { ready.complete(Unit); null }
        val child = launch { fetchCdnFeed(Call.Factory { call }) }
        ready.await(); child.cancelAndJoin(); verify(call).cancel()
    }
}
