package com.v2ray.ang.core.lab

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertPathBuilderException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

internal data class LabDownloadResult(val issue: LabIssue, val bytes: Long,
    val responseMillis: Long? = null, val elapsedMillis: Long? = null, val failure: LabProbeFailure? = null)

internal enum class LabTlsFailure { EXPIRED, NOT_YET_VALID, CHAIN, CERTIFICATE, PEER, HANDSHAKE, PROTOCOL, OTHER }
/** Safe categories and class names only. Exception messages, certificates and endpoints never enter reports. */
internal data class LabProbeFailure(val issue: LabIssue, val tls: LabTlsFailure?, val types: List<String>)
internal fun labProbeFailure(error: IOException): LabProbeFailure {
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = error
    while (chain.size < 8) {
        val node = current ?: break
        if (chain.any { it === node }) break
        chain += node
        current = node.cause
    }
    val tls = when {
        chain.any { it is CertificateExpiredException } -> LabTlsFailure.EXPIRED
        chain.any { it is CertificateNotYetValidException } -> LabTlsFailure.NOT_YET_VALID
        chain.any { it is CertPathValidatorException || it is CertPathBuilderException } -> LabTlsFailure.CHAIN
        chain.any { it is CertificateException } -> LabTlsFailure.CERTIFICATE
        chain.any { it is SocketTimeoutException } -> null
        chain.any { it is SSLPeerUnverifiedException } -> LabTlsFailure.PEER
        chain.any { it is SSLProtocolException } -> LabTlsFailure.PROTOCOL
        chain.any { it is SSLHandshakeException } -> LabTlsFailure.HANDSHAKE
        chain.any { it is SSLException } -> LabTlsFailure.OTHER
        else -> null
    }
    return LabProbeFailure(if (tls != null) LabIssue.TLS else if (chain.any { it is SocketTimeoutException }) LabIssue.TIMEOUT else LabIssue.IO,
        tls, chain.map { it.javaClass.simpleName.take(80) })
}

/** The caller MUST supply a client whose sole route is the candidate's isolated SOCKS listener. */
internal class LabHttpProbe(private val calls: Call.Factory,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        const val DEFAULT_URL = "https://speed.cloudflare.com/__down?bytes=10000000"
        fun validUrl(text: String): Boolean = text.length <= 4096 && text.toHttpUrlOrNull()?.let {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        } == true
    }

    suspend fun download(url: String, nonce: String, expectedBytes: Long = LabPlan.DOWNLOAD_BYTES): LabDownloadResult {
        require(expectedBytes in 1..LabPlan.DOWNLOAD_BYTES)
        if (!validUrl(url)) return LabDownloadResult(LabIssue.CONFIG, 0)
        val target = url.toHttpUrlOrNull()!!.newBuilder().addQueryParameter("lab_nonce", nonce).build()
        val request = Request.Builder().url(target).header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache, no-store").get().build()
        return suspendCancellableCoroutine { continuation ->
            val started = clock()
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val failure = labProbeFailure(e)
                    if (continuation.isActive) continuation.resume(LabDownloadResult(failure.issue, 0, failure = failure))
                }

                override fun onResponse(call: Call, response: Response) {
                    var bytes = 0L
                    var firstByte: Long? = null
                    var issue = LabIssue.BODY
                    var failure: LabProbeFailure? = null
                    try {
                        run validation@ {
                            val body = response.body
                            val encoding = response.header("Content-Encoding").orEmpty()
                            if (response.code != 200) { issue = LabIssue.HTTP; return@validation }
                            if ((encoding.isNotBlank() && !encoding.equals("identity", true)) ||
                                (body.contentLength() >= 0 && body.contentLength() != expectedBytes)) return@validation
                            val stream = body.byteStream()
                            val buffer = ByteArray(32 * 1024)
                            while (continuation.isActive && bytes < expectedBytes) {
                                val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), expectedBytes - bytes).toInt())
                                if (count < 0) break
                                if (count > 0 && firstByte == null) firstByte = (clock() - started).coerceAtLeast(0)
                                bytes += count
                            }
                            if (bytes == expectedBytes &&
                                (body.contentLength() == expectedBytes || stream.read() == -1)) issue = LabIssue.NONE
                        }
                    } catch (e: IOException) {
                        failure = labProbeFailure(e)
                        issue = failure.issue
                    } finally {
                        call.cancel() // Do not drain an unexpected body while closing or reuse a probe connection.
                        response.close()
                        if (continuation.isActive) continuation.resume(LabDownloadResult(issue, bytes,
                            firstByte, (clock() - started).coerceAtLeast(firstByte ?: 0).coerceAtLeast(1), failure))
                    }
                }
            })
        }
    }
}
