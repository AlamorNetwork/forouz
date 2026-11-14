package com.v2ray.ang.ui.main

import com.v2ray.ang.R
import com.v2ray.ang.core.amnezia.AmneziaGatewayFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AmneziaImportErrorTest {
    @Test fun `provider availability and quota select their localized messages`() {
        assertEquals(R.string.awg_no_compatible_server, amneziaImportErrorResource(
            AmneziaGatewayFailure(422, AmneziaGatewayFailure.Reason.NO_COMPATIBLE_SERVERS)))
        assertEquals(R.string.awg_configuration_limit, amneziaImportErrorResource(
            AmneziaGatewayFailure(409, AmneziaGatewayFailure.Reason.CONFIGURATION_LIMIT)))
    }

    @Test fun `unknown empty and unrelated failures retain the generic import error`() {
        for (error in listOf(IllegalStateException(), IllegalArgumentException(""),
            IllegalStateException("untrusted provider message"),
            AmneziaGatewayFailure(422, AmneziaGatewayFailure.Reason.UNKNOWN))) {
            assertEquals(R.string.toast_failure, amneziaImportErrorResource(error))
        }
    }
}
