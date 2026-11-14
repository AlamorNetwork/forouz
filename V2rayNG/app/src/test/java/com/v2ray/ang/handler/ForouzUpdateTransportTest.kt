package com.v2ray.ang.handler

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.Proxy

class ForouzUpdateTransportTest {
    @Test fun `release request stays HTTPS fixed origin and has no proxy credentials`() {
        val request = forouzUpdateRequest()
        assertEquals("https", request.url.scheme)
        assertEquals(ForouzReleasePolicy.API, request.url.toString())
        assertNull(request.header("Authorization")); assertNull(request.header("Proxy-Authorization"))
        val client = forouzUpdateClient()
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertEquals(10_000, client.callTimeoutMillis)
    }
    @Test fun `local proxy credentials are only supplied to CONNECT never origin GET`() {
        val client = forouzUpdateClient(10808, "test-user", "test-only-password")
        fun response(method: String, alreadyAuthenticated: Boolean = false): Response {
            val request = Request.Builder().url(ForouzReleasePolicy.API).method(method, null).apply {
                if (alreadyAuthenticated) header("Proxy-Authorization", "test")
            }.build()
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(407).message("Proxy auth").build()
        }
        assertNull(client.proxyAuthenticator.authenticate(null, response("GET")))
        assertNotNull(client.proxyAuthenticator.authenticate(null, response("CONNECT"))?.header("Proxy-Authorization"))
        assertNull(client.proxyAuthenticator.authenticate(null, response("CONNECT", true)))
    }
}
