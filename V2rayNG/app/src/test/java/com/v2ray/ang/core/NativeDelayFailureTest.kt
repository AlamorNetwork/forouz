package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeDelayFailureTest {
    @Test fun `recognizes native error signatures without returning error text`() {
        val cases = mapOf(
            "config load error: invalid JSON" to NativeDelayFailure.CONFIG,
            "instance creation failed: fixture" to NativeDelayFailure.CONFIG,
            "lookup edge.example: no such host" to NativeDelayFailure.DNS,
            "context deadline exceeded" to NativeDelayFailure.TIMEOUT,
            "net/http: request canceled (Client.Timeout exceeded while awaiting headers)" to NativeDelayFailure.TIMEOUT,
            "x509: certificate signed by unknown authority" to NativeDelayFailure.CERTIFICATE,
            "remote error: tls: handshake failure" to NativeDelayFailure.TLS,
            "websocket: bad handshake" to NativeDelayFailure.WEBSOCKET,
            "invalid status: 403 Forbidden" to NativeDelayFailure.HTTP_STATUS,
            "dial tcp: connect: connection refused" to NativeDelayFailure.CONNECT,
            "unexpected EOF" to NativeDelayFailure.EOF,
            "opaque failure credential-fixture" to NativeDelayFailure.UNKNOWN
        )
        cases.forEach { (message, expected) ->
            val result = NativeDelayFailure.classify(Exception("Get \"https://fixture.example/?secret=private-fixture\": $message"))
            assertEquals(expected, result)
            assertFalse(result.name.contains("fixture"))
        }
    }

    @Test fun `URL text cannot impersonate a failure and null messages are safe`() {
        assertEquals(NativeDelayFailure.UNKNOWN, NativeDelayFailure.classify(Exception(
            "Get \"https://fixture.example/?q=x509: certificate&timeout=context deadline exceeded\": opaque failure")))
        assertEquals(NativeDelayFailure.UNKNOWN, NativeDelayFailure.classify(Exception()))
        assertEquals(NativeDelayFailure.TIMEOUT, NativeDelayFailure.classify(Exception(null, Exception("i/o timeout"))))
    }

    @Test fun `cause cycles and long chains are bounded`() {
        val first = Exception()
        val second = Exception()
        first.initCause(second); second.initCause(first)
        assertEquals(NativeDelayFailure.UNKNOWN, NativeDelayFailure.classify(first))
        var chain: Throwable = Exception("x509: private fixture")
        repeat(9) { chain = Exception(null, chain) }
        assertEquals(NativeDelayFailure.UNKNOWN, NativeDelayFailure.classify(chain))
    }
}
