package com.v2ray.ang.handler

import com.v2ray.ang.core.cdn.CdnPolicy
import java.io.IOException
import java.net.Proxy
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import com.v2ray.ang.dto.ConnectionSnapshot
import com.v2ray.ang.core.privacy.PrivacyRoute
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun cdnFeedUrl(raw: String): String {
    require(raw.length in 1..2048 && raw.none { it.code < 32 || it.code == 127 }) { "Invalid catalog URL" }
    val url = requireNotNull(raw.trim().toHttpUrlOrNull()) { "Invalid catalog URL" }
    require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
        url.query == null && url.fragment == null) { "Invalid catalog URL" }
    return url.toString()
}

internal enum class CdnFeedIssue { NETWORK, TLS, TIMEOUT, HTTP, BODY }
internal class CdnFeedException(val issue: CdnFeedIssue, val httpCode: Int? = null) : IOException(issue.name)

/** Body reads can fail after headers arrive; those failures retain their transport category. */
private fun cdnFeedFailure(error: Throwable): CdnFeedException = error as? CdnFeedException ?: CdnFeedException(when (error) {
    is SSLException -> CdnFeedIssue.TLS
    is SocketTimeoutException, is java.io.InterruptedIOException -> CdnFeedIssue.TIMEOUT
    is IOException -> CdnFeedIssue.NETWORK
    else -> CdnFeedIssue.BODY
})

/** Runtime proof only: UI settings or a remembered "connected" flag cannot admit a proxy. */
internal fun cdnFeedProxyPort(snapshot: ConnectionSnapshot?, now: Long): Int? = snapshot?.takeIf {
    it.running && it.sessionId.isNotBlank() && it.profileGuid.isNotBlank() && it.privacyRoute == PrivacyRoute.XRAY_SOCKS &&
        it.sampledAt <= now && now - it.sampledAt <= 5_000 && it.privacyProxyPort in 1..65535
}?.privacyProxyPort

/** Fixed public feed; admitted SOCKS with remote target DNS, no credentials/redirect/direct fallback. */
internal fun cdnFeedClient(proxyPort: Int? = null): OkHttpClient {
    require(proxyPort == null || proxyPort in 1..65535)
    return OkHttpClient.Builder().proxy(if (proxyPort == null) Proxy.NO_PROXY else
        Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort)))
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
}

internal suspend fun fetchCdnFeed(calls: Call.Factory, url: String = CdnPolicy.FEED): String = suspendCancellableCoroutine { continuation ->
    val request = Request.Builder().url(cdnFeedUrl(url)).header("Cache-Control", "no-cache")
        .header("User-Agent", "FOROUZ-Android").build()
    val call = calls.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(cdnFeedFailure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use {
                    if (it.code != 200) throw CdnFeedException(CdnFeedIssue.HTTP, it.code)
                    val source = it.body.source()
                    source.request(2_000_001)
                    if (source.buffer.size > 2_000_000) throw CdnFeedException(CdnFeedIssue.BODY)
                    source.readUtf8(source.buffer.size)
                }
            }
            call.cancel()
            if (continuation.isActive) result.fold(continuation::resume,
                { continuation.resumeWithException(cdnFeedFailure(it)) })
        }
    })
}
