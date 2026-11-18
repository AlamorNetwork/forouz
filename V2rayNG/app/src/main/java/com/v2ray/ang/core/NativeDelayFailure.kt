package com.v2ray.ang.core

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** Advisory signatures from Go/JNI errors; only this allowlisted code may leave the classifier. */
internal enum class NativeDelayFailure {
    CONFIG, DNS, TIMEOUT, CERTIFICATE, TLS, WEBSOCKET, HTTP_STATUS, CONNECT, EOF, UNKNOWN;

    companion object {
        fun classify(error: Throwable): NativeDelayFailure {
            val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var current: Throwable? = error
            repeat(8) {
                val cause = current ?: return UNKNOWN
                if (!visited.add(cause)) return UNKNOWN
                // net/url.Error quotes its URL. Remove it before matching so a URL query cannot
                // impersonate a TLS/DNS signature. Never retain text, captures, URLs or hostnames.
                val text = cause.message.orEmpty()
                    .replace(Regex("^\\w+\\s+\"[^\"]*\":\\s*"), "")
                    .replace(Regex("\"[^\"]*\""), "")
                    .lowercase(Locale.ROOT)
                val code = when {
                    listOf("config load error:", "instance creation failed:", "startup failed:").any(text::startsWith) -> CONFIG
                    "x509:" in text -> CERTIFICATE
                    "tls:" in text || "tls handshake timeout" in text -> TLS
                    "websocket: bad handshake" in text -> WEBSOCKET
                    "no such host" in text || "temporary failure in name resolution" in text -> DNS
                    "context deadline exceeded" in text || "i/o timeout" in text || "client.timeout exceeded" in text -> TIMEOUT
                    "connection refused" in text || "network is unreachable" in text || "no route to host" in text -> CONNECT
                    "invalid status:" in text -> HTTP_STATUS
                    text.trim() == "eof" || text.trim().endsWith(": eof") || "unexpected eof" in text -> EOF
                    else -> UNKNOWN
                }
                if (code != UNKNOWN) return code
                current = cause.cause
            }
            return UNKNOWN
        }
    }
}
