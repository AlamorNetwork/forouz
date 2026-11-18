package com.v2ray.ang.core.cdn

import com.v2ray.ang.core.lab.LabIssue
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.SocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.*

class CdnEdgeTlsLoopbackTest {
    @Test fun `real TLS accepts trusted matching SAN rejects wrong name and untrusted certificate`(@TempDir directory: Path) = runBlocking {
        // Ephemeral fixture only: never installed into Android/system trust or included in an APK.
        val password = "fixture-only".toCharArray()
        val storeFile = directory.resolve("edge.p12")
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val keytool = Path.of(System.getProperty("java.home"), "bin", executable)
        val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "edge", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "3650", "-dname", "CN=edge.example", "-ext", "SAN=dns:edge.example",
            "-storetype", "PKCS12", "-keystore", storeFile.toString(), "-storepass", String(password), "-noprompt")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(storeFile).use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null); setCertificateEntry("fixture", store.getCertificate("edge"))
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        val trusted = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
        val cases = listOf(Triple("edge.example", trusted, true), Triple("wrong.example", trusted, false),
            Triple("edge.example", SSLSocketFactory.getDefault() as SSLSocketFactory, false))
        for ((name, factory, expected) in cases) coroutineScope {
            val server = serverContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
            server.soTimeout = 3000
            val done = CompletableDeferred<Unit>()
            val peer = async(Dispatchers.IO) {
                try {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 3000; socket.startHandshake(); done.await()
                    }
                } catch (_: IOException) { /* Expected rejection in negative fixtures. */ }
            }
            val direct = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    // Never dial the public fixture IP. All real packets are confined to loopback.
                    super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), timeout)
                }
            }
            val sockets = mock<SocketFactory>()
            whenever(sockets.createSocket()).thenReturn(mock<Socket>(), direct)
            val result = try { CdnEdgeProbe(sockets, factory, onFailure = { _, _ -> }).measure("8.8.8.8", name) }
                finally { done.complete(Unit); server.close() }
            peer.await()
            assertTrue(result.tcp443.passed())
            assertEquals(expected, result.tls.passed(), name)
            if (!expected) assertEquals(LabIssue.TLS, result.tls.issue)
        }
    }
}
