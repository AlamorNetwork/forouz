package com.v2ray.ang.core.cdn

import com.v2ray.ang.core.lab.LabIssue
import com.v2ray.ang.core.lab.LabTlsFailure
import com.v2ray.ang.core.lab.labProbeFailure
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.*

internal data class CdnEdgeCheck(val millis: Long? = null, val issue: LabIssue = LabIssue.NONE,
    val tls: LabTlsFailure? = null, val skipped: Boolean = false) {
    fun passed() = !skipped && issue == LabIssue.NONE && millis != null
    fun validate(tlsStage: Boolean) {
        require(issue in if (tlsStage) setOf(LabIssue.NONE, LabIssue.TIMEOUT, LabIssue.IO, LabIssue.TLS)
            else setOf(LabIssue.NONE, LabIssue.TIMEOUT, LabIssue.IO))
        require(if (skipped) tlsStage && millis == null && issue == LabIssue.NONE && tls == null
            else if (issue == LabIssue.NONE) millis != null && millis >= 0 && tls == null else millis == null)
        require(tls == null || (tlsStage && issue == LabIssue.TLS && tls in LabTlsFailure.entries))
    }
}
internal data class CdnEdgeResult(val tcp80: CdnEdgeCheck, val tcp443: CdnEdgeCheck, val tls: CdnEdgeCheck) {
    fun reachable() = tcp80.passed() || tcp443.passed()
    fun validate() {
        tcp80.validate(false); tcp443.validate(false); tls.validate(true)
        require(tls.skipped == !tcp443.passed())
    }
}

/** Owns only direct sockets for one edge. No proxy, HTTP request, native core or profile payload. */
internal class CdnEdgeProbe(private val sockets: SocketFactory,
    private val ssl: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val onFailure: (String, IOException) -> Unit) {
    private class OwnedSockets(private val onFailure: (String, IOException) -> Unit) {
        private val lock = Any()
        private var closed = false
        private val sockets = mutableListOf<Socket>()
        fun <T : Socket> own(socket: T): T = synchronized(lock) {
            if (closed) { close(socket); throw CancellationException("Edge probe closed") }
            sockets += socket
            socket
        }
        fun close(socket: Socket) {
            try { socket.close() } catch (e: IOException) { onFailure("close", e) }
        }
        fun closeAll() {
            val active = synchronized(lock) { closed = true; sockets.toList().also { sockets.clear() } }
            active.forEach(::close)
        }
    }

    suspend fun measure(address: String, serverName: String): CdnEdgeResult = coroutineScope {
        require(CdnPolicy.publicAddress(address))
        val name = sni(serverName)
        val ip = InetAddress.getByAddress(address.split('.').map { it.toInt().toByte() }.toByteArray())
        val owned = OwnedSockets(onFailure)
        val worker = async(Dispatchers.IO) {
            suspend fun timed(stage: String, operation: () -> Unit): CdnEdgeCheck {
                currentCoroutineContext().ensureActive()
                val start = System.nanoTime()
                val result = try {
                    operation()
                    CdnEdgeCheck(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                } catch (e: IOException) {
                    onFailure(stage, e)
                    val failure = labProbeFailure(e)
                    CdnEdgeCheck(issue = failure.issue, tls = failure.tls)
                }
                currentCoroutineContext().ensureActive()
                return result
            }
            val tcp80 = timed("tcp80") {
                val socket = owned.own(sockets.createSocket())
                try { socket.connect(InetSocketAddress(ip, 80), 3000) } finally { owned.close(socket) }
            }
            var transport: Socket? = null
            val tcp443 = timed("tcp443") {
                transport = owned.own(sockets.createSocket()).also { it.connect(InetSocketAddress(ip, 443), 3000) }
            }
            val tls = if (!tcp443.passed()) CdnEdgeCheck(skipped = true) else timed("tls") {
                val secure = owned.own(ssl.createSocket(requireNotNull(transport), name, 443, true) as SSLSocket)
                secure.soTimeout = 5000
                // System trust plus HTTPS peer-name verification are mandatory; never install a permissive verifier.
                secure.sslParameters = secure.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(name))
                }
                secure.startHandshake()
            }
            CdnEdgeResult(tcp80, tcp443, tls).also { it.validate() }
        }
        // await is cancellable even while Socket.connect/startHandshake blocks on the child IO thread.
        // Close first, then coroutineScope joins that child; a cancelled probe cannot publish a row.
        try { worker.await() } finally { owned.closeAll() }
    }

    companion object {
        fun sni(value: String): String {
            require(value.isNotBlank() && value.length <= 253 && value.none { it.isWhitespace() })
            val name = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            require(name.length <= 253 && CdnPrefix.ipv4(name) == null && name.contains('.'))
            require(name.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) })
            return name
        }
    }
}
