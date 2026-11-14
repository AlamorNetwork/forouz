package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Update-only transport: fixed HTTPS endpoint, bounded body, cancellable call, no origin credentials.
 * General HttpUtil follows user URL redirects and attaches proxy headers to origin requests, so it
 * cannot own this stricter release-catalog boundary without changing unrelated subscription flows.
 */
internal fun forouzUpdateClient(port: Int = 0, username: String? = null, password: String? = null): OkHttpClient {
    require(port in 0..65535)
    val builder = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    if (port == 0) builder.proxy(Proxy.NO_PROXY)
    else {
        builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) builder.proxyAuthenticator { _, response ->
            if (response.request.method != "CONNECT" || response.request.header("Proxy-Authorization") != null) null
            else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(username, password)).build()
        }
    }
    return builder.build()
}

internal fun forouzUpdateRequest(): Request = Request.Builder().url(ForouzReleasePolicy.API)
    .header("Accept", "application/vnd.github+json").header("Cache-Control", "no-cache")
    .header("User-Agent", "FOROUZ-Android").build()

internal suspend fun fetchForouzReleases(client: OkHttpClient): String? {
    try {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(forouzUpdateRequest())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        LogUtil.e(AppConfig.TAG, "FOROUZ release transport", IllegalStateException(e.javaClass.simpleName))
                        continuation.resumeWith(Result.success(null))
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = try {
                        response.use {
                            if (!it.isSuccessful) {
                                LogUtil.w(AppConfig.TAG, "FOROUZ release transport HTTP=${it.code}")
                                null
                            } else it.body?.source()?.let { source ->
                                source.request(2_000_001)
                                if (source.buffer.size > 2_000_000) null else source.readUtf8(source.buffer.size)
                            }
                        }
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "FOROUZ release body", IllegalStateException(e.javaClass.simpleName)); null
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(body))
                }
            })
        }
    } finally {
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
    }
}
