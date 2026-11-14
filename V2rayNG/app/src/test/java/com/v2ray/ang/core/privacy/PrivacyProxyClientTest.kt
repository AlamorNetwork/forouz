package com.v2ray.ang.core.privacy

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.net.ServerSocket
import java.net.InetAddress
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import okhttp3.Request

class PrivacyProxyClientTest {
    @Test fun `destination is sent as a SOCKS hostname without local DNS and rejection cannot fall back`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val received = FutureTask(Callable {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = java.io.DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    check(input.readUnsignedByte() == 5)
                    val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                    output.write(byteArrayOf(5, 0)); output.flush()
                    check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
                    check(input.readUnsignedByte() == 0 && input.readUnsignedByte() == 3)
                    val domain = ByteArray(input.readUnsignedByte()); input.readFully(domain)
                    check(input.readUnsignedShort() == 443)
                    output.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
                    String(domain, Charsets.US_ASCII)
                }
            })
            val worker = Thread(received, "privacy-test-socks").apply { isDaemon = true; start() }
            val client = privacyProxyClient(server.localPort)
            try {
                assertThrows(IOException::class.java) {
                    client.newCall(Request.Builder().url("https://privacy-probe.invalid/").build()).execute().use { }
                }
                assertEquals("privacy-probe.invalid", received.get(6, TimeUnit.SECONDS))
            } finally {
                server.close(); received.cancel(true); worker.join(1_000)
                client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
            }
        }
    }
    @Test fun `proxy is explicit loopback SOCKS with no physical DNS or redirect fallback`() {
        val client = privacyProxyClient(10808)
        assertEquals(Proxy.Type.SOCKS, client.proxy?.type())
        val address = client.proxy?.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.address.hostAddress)
        assertEquals(10808, address.port)
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertEquals(8_000, client.callTimeoutMillis)
        assertThrows(UnknownHostException::class.java) { client.dns.lookup("privacy-probe.invalid") }
        assertThrows(IllegalArgumentException::class.java) { privacyProxyClient(0) }
        assertThrows(IllegalArgumentException::class.java) { privacyProxyClient(65536) }
    }
}
