package com.v2ray.ang.core.privacy

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Xray excludes its own UID from TUN. Explicit SOCKS uses remote DNS and never direct fallback. */
internal fun privacyProxyClient(port: Int): OkHttpClient {
    require(port in 1..65535)
    return OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        .dns(Dns { throw UnknownHostException("Local DNS forbidden for privacy SOCKS probe") })
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).build()
}
