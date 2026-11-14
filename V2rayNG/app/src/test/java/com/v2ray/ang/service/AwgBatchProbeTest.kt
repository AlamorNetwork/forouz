package com.v2ray.ang.service

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.mayRemoveAfterDelayTest
import com.v2ray.ang.handler.resetDelayBeforeBatch
import com.v2ray.ang.helper.ConnectionProbeReply
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AwgBatchProbeTest {
    @Test fun `active AWG real delay uses the daemon measurement instead of hardcoded minus one`() {
        val event = awgBatchResult("active-guid", ConnectionProbeReply.Result(ConnectionTestResult(47)))
        assertEquals("active-guid", event.guid)
        assertEquals(47L, event.delayMillis)
        assertTrue(event.measured)
    }

    @Test fun `unavailable cancelled and timed out IPC are unmeasured rather than dead profiles`() {
        for (reply in listOf(ConnectionProbeReply.Unavailable, ConnectionProbeReply.TimedOut)) {
            val event = awgBatchResult("inactive", reply)
            assertFalse(event.measured)
            assertEquals(0L, event.delayMillis)
        }
    }

    @Test fun `a real failed HTTPS measurement remains a failed measurement`() {
        val event = awgBatchResult("active", ConnectionProbeReply.Result(ConnectionTestResult(-1, "DNS failed")))
        assertTrue(event.measured)
        assertEquals(-1L, event.delayMillis)
    }

    @Test fun `batch preserves valid native history and clears legacy unmeasurable minus one`() {
        assertFalse(resetDelayBeforeBatch(EConfigType.AMNEZIAWG, 47))
        assertFalse(resetDelayBeforeBatch(EConfigType.AMNEZIAWG, 0))
        assertTrue(resetDelayBeforeBatch(EConfigType.AMNEZIAWG, -1))
        assertTrue(resetDelayBeforeBatch(EConfigType.VLESS, 47))
        assertTrue(resetDelayBeforeBatch(EConfigType.VLESS, -1))
    }

    @Test fun `native probe failure cannot cause automatic deletion of a working AWG profile`() {
        assertFalse(mayRemoveAfterDelayTest(EConfigType.AMNEZIAWG, -1))
        assertFalse(mayRemoveAfterDelayTest(null, -1))
        assertTrue(mayRemoveAfterDelayTest(EConfigType.VLESS, -1))
        assertFalse(mayRemoveAfterDelayTest(EConfigType.VLESS, 47))
    }
}
