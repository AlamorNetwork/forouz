package com.v2ray.ang.core.cdn

import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Protocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CdnProbeClientTest {
    @Test fun `HTTP1 control changes only ALPN preference and shares the owned route security and cleanup`() {
        val base = cdnProbeClient(12345)
        try {
            val h1 = cdnHttp1Control(base)
            assertEquals(listOf(Protocol.HTTP_1_1), h1.protocols)
            assertEquals(Proxy.Type.SOCKS, h1.proxy?.type())
            assertEquals(InetSocketAddress("127.0.0.1", 12345), h1.proxy?.address())
            assertSame(base.sslSocketFactory, h1.sslSocketFactory)
            assertSame(base.hostnameVerifier, h1.hostnameVerifier)
            assertEquals(base.connectionSpecs, h1.connectionSpecs)
            assertSame(base.dispatcher, h1.dispatcher); assertSame(base.connectionPool, h1.connectionPool)
            assertEquals(base.callTimeoutMillis, h1.callTimeoutMillis)
            assertFalse(h1.followRedirects); assertFalse(h1.followSslRedirects); assertFalse(h1.retryOnConnectionFailure)
        } finally { base.dispatcher.executorService.shutdownNow(); base.connectionPool.evictAll() }
    }
}
