package com.v2ray.ang.core.lab

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

internal data class LabDownloadResult(val issue: LabIssue, val bytes: Long,
    val responseMillis: Long? = null, val elapsedMillis: Long? = null)

/** The caller MUST supply a client whose sole route is the candidate's isolated SOCKS listener. */
internal class LabHttpProbe(private val calls: Call.Factory,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        const val DEFAULT_URL = "https://speed.cloudflare.com/__down?bytes=10000000"
        fun validUrl(text: String): Boolean = text.length <= 4096 && text.toHttpUrlOrNull()?.let {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        } == true
    }

    suspend fun download(url: String, nonce: String): LabDownloadResult {
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
                    val issue = when (e) {
                        is SSLException -> LabIssue.TLS
                        is SocketTimeoutException -> LabIssue.TIMEOUT
                        else -> LabIssue.IO
                    }
                    if (continuation.isActive) continuation.resume(LabDownloadResult(issue, 0))
                }

                override fun onResponse(call: Call, response: Response) {
                    var bytes = 0L
                    var firstByte: Long? = null
                    var issue = LabIssue.BODY
                    try {
                        run validation@ {
                            val body = response.body
                            val encoding = response.header("Content-Encoding").orEmpty()
                            if (response.code != 200) { issue = LabIssue.HTTP; return@validation }
                            if ((encoding.isNotBlank() && !encoding.equals("identity", true)) ||
                                (body.contentLength() >= 0 && body.contentLength() != LabPlan.DOWNLOAD_BYTES)) return@validation
                            val stream = body.byteStream()
                            val buffer = ByteArray(32 * 1024)
                            while (continuation.isActive && bytes < LabPlan.DOWNLOAD_BYTES) {
                                val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), LabPlan.DOWNLOAD_BYTES - bytes).toInt())
                                if (count < 0) break
                                if (count > 0 && firstByte == null) firstByte = (clock() - started).coerceAtLeast(0)
                                bytes += count
                            }
                            if (bytes == LabPlan.DOWNLOAD_BYTES &&
                                (body.contentLength() == LabPlan.DOWNLOAD_BYTES || stream.read() == -1)) issue = LabIssue.NONE
                        }
                    } catch (e: IOException) {
                        issue = if (e is SocketTimeoutException) LabIssue.TIMEOUT else LabIssue.IO
                    } finally {
                        call.cancel() // Do not drain an unexpected body while closing or reuse a probe connection.
                        response.close()
                        if (continuation.isActive) continuation.resume(LabDownloadResult(issue, bytes,
                            firstByte, (clock() - started).coerceAtLeast(firstByte ?: 0).coerceAtLeast(1)))
                    }
                }
            })
        }
    }
}
