package com.v2ray.ang.core

import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.UnknownHostException
import java.net.SocketException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal enum class AwgProbeFailure { UNSUPPORTED_ANDROID, VPN_UNAVAILABLE, INVALID_URL, TIMEOUT, HTTP_RESPONSE, NETWORK, DNS, TLS, CONNECT }

internal fun awgIoFailure(error: IOException): AwgProbeFailure = when (error) {
    is UnknownHostException -> AwgProbeFailure.DNS
    is SSLException -> AwgProbeFailure.TLS
    is InterruptedIOException -> AwgProbeFailure.TIMEOUT
    is SocketException -> AwgProbeFailure.CONNECT
    else -> AwgProbeFailure.NETWORK
}

internal data class AwgProbeOutcome(val delayMillis: Long = -1, val failure: AwgProbeFailure? = null, val diagnosticCode: String? = null)

/** The caller supplies both factories from ONE owned VPN Network; no system/SOCKS fallback. */
internal fun awgProbeClient(sockets: SocketFactory, dns: Dns): OkHttpClient = OkHttpClient.Builder()
    .socketFactory(sockets)
    .dns(dns)
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .followSslRedirects(false)
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .writeTimeout(3, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

/** HTTP reachability including DNS/TCP/TLS, not ICMP ping or a bandwidth benchmark. */
internal class AwgConnectionProbe(
    private val calls: Call.Factory,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val budgetMillis: Long = 8_000,
    private val onFailure: (AwgProbeFailure, IOException) -> Unit = { _, _ -> },
) {
    suspend fun measure(primary: String, fallback: String): AwgProbeOutcome =
        withTimeoutOrNull(budgetMillis) {
            var failure = AwgProbeFailure.INVALID_URL
            for (candidate in listOf(primary, fallback).distinct()) {
                val url = candidate.toHttpUrlOrNull()
                if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) continue
                val start = clockMillis()
                try {
                    val status = headers(Request.Builder().url(url).get().build())
                    if (status in 200..299) return@withTimeoutOrNull AwgProbeOutcome((clockMillis() - start).coerceAtLeast(0))
                    failure = AwgProbeFailure.HTTP_RESPONSE
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    failure = awgIoFailure(e)
                    onFailure(failure, e)
                }
            }
            AwgProbeOutcome(failure = failure)
        } ?: AwgProbeOutcome(failure = AwgProbeFailure.TIMEOUT)

    private suspend fun headers(request: Request): Int = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // Cancel before closing the body, so even a large custom URL is never drained.
                call.cancel()
                response.close()
                if (continuation.isActive) continuation.resume(response.code)
            }
        })
    }
}
