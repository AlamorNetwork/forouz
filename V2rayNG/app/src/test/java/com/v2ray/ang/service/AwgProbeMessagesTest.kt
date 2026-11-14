package com.v2ray.ang.service

import com.v2ray.ang.R
import com.v2ray.ang.core.AwgProbeFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AwgProbeMessagesTest {
    @Test fun `all neutral failures map to localized resources rather than raw exceptions`() {
        val expected = mapOf(
            AwgProbeFailure.UNSUPPORTED_ANDROID to R.string.awg_test_unsupported,
            AwgProbeFailure.VPN_UNAVAILABLE to R.string.awg_test_no_vpn,
            AwgProbeFailure.INVALID_URL to R.string.awg_test_invalid_url,
            AwgProbeFailure.TIMEOUT to R.string.awg_test_timeout,
            AwgProbeFailure.HTTP_RESPONSE to R.string.awg_test_http_response,
            AwgProbeFailure.NETWORK to R.string.awg_test_network_error,
            AwgProbeFailure.DNS to R.string.awg_test_dns_error,
            AwgProbeFailure.TLS to R.string.awg_test_tls_error,
            AwgProbeFailure.CONNECT to R.string.awg_test_socket_error,
        )
        assertEquals(AwgProbeFailure.entries.toSet(), expected.keys)
        expected.forEach { (failure, resource) -> assertEquals(resource, awgProbeErrorResource(failure)) }
    }
}
