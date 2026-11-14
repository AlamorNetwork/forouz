package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReconnectActionTest {
    @Test fun `cold connect can start only after consent`() {
        assertEquals(ReconnectAction.START, ReconnectAction.decide(false, false))
        assertEquals(ReconnectAction.NEEDS_PERMISSION, ReconnectAction.decide(false, true))
    }

    @Test fun `repeated connect and stale notifications leave active daemon in charge`() {
        for (permission in listOf(false, true)) {
            assertEquals(ReconnectAction.ALREADY_HANDLED, ReconnectAction.decide(true, permission))
        }
    }
}
