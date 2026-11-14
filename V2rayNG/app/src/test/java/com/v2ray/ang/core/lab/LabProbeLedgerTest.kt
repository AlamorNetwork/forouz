package com.v2ray.ang.core.lab

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LabProbeLedgerTest {
    private fun token(id: String = "request") = LabProbeToken(id, "run",
        LabTarget("guid", "group", LabTransport.XHTTP), "network", "revision", null, 1_000)
    private fun success() = LabProbeOutcome.Success(LabTransferMetrics(100, 1_000, 10_000_000, 10_000_000))

    @Test fun `response delay stays separate from complete download throughput`() {
        val metrics = success().metrics
        assertEquals(100L, metrics.responseDelayMillis)
        assertEquals(80.0, metrics.downloadMbps!!, 0.001)
        assertNull(metrics.copy(expectedBytes = null).downloadMbps)
        assertNull(metrics.copy(downloadedBytes = 100).downloadMbps)
        assertThrows(IllegalArgumentException::class.java) { LabProbeOutcome.Success(metrics.copy(downloadedBytes = 100)) }
        assertThrows(IllegalArgumentException::class.java) { metrics.copy(responseDelayMillis = 2_000) }
        assertThrows(IllegalArgumentException::class.java) { metrics.copy(downloadedBytes = -1) }
    }

    @Test fun `only matching request is consumed and duplicate or superseded replies cannot append`() {
        val ledger = LabProbeLedger(); ledger.activate("run", "network")
        val old = token(); ledger.begin(old)
        val current = token("new"); ledger.begin(current)
        assertFalse(ledger.finish(old, success(), 2_000))
        assertFalse(ledger.finish(current.copy(sessionId = "other"), success(), 2_000))
        assertFalse(ledger.finish(current.copy(configRevision = "other"), success(), 2_000))
        assertTrue(ledger.finish(current, success(), 2_000))
        assertFalse(ledger.finish(current, success(), 2_000))
        assertEquals(1, ledger.history("guid").size)
    }

    @Test fun `network change stop and profile invalidation reject results without deleting history`() {
        val ledger = LabProbeLedger(); ledger.activate("run", "network")
        val t = token(); ledger.begin(t); assertTrue(ledger.finish(t, success(), 2_000))
        ledger.begin(token("pending")); ledger.activate("run", "next-network")
        assertFalse(ledger.finish(token("pending"), success(), 2_000))
        assertFalse(ledger.eligible("guid", "revision", null, 2_100, 5_000))
        val next = token("next").copy(networkEpoch = "next-network")
        ledger.begin(next); ledger.invalidate("guid")
        assertFalse(ledger.finish(next, success(), 2_000))
        ledger.begin(next); ledger.stop()
        assertFalse(ledger.finish(next, success(), 2_000))
        assertEquals(1, ledger.history("guid").size)
    }

    @Test fun `fresh eligibility is scoped to run network config session and latest real outcome`() {
        val ledger = LabProbeLedger(); ledger.activate("run", "network")
        ledger.begin(token()); ledger.finish(token(), success(), 2_000)
        assertTrue(ledger.eligible("guid", "revision", null, 2_100, 100))
        assertFalse(ledger.eligible("guid", "revision", null, 2_101, 100))
        assertFalse(ledger.eligible("guid", "revision", null, 1_999, 100))
        assertFalse(ledger.eligible("guid", "changed", null, 2_100, 100))
        assertFalse(ledger.eligible("guid", "revision", "active-session", 2_100, 100))
        ledger.begin(token("skip")); ledger.finish(token("skip"), LabProbeOutcome.Unsupported, 2_100)
        assertFalse(ledger.eligible("guid", "revision", null, 2_100, 100))
        ledger.begin(token("fail")); ledger.finish(token("fail"), LabProbeOutcome.Failure(LabFailureStage.TLS), 2_100)
        assertFalse(ledger.eligible("guid", "revision", null, 2_100, 100))
        ledger.activate("different-run", "network")
        assertFalse(ledger.eligible("guid", "revision", null, 2_100, 100))
    }

    @Test fun `history is bounded and invalid clocks or wrong owners cannot complete`() {
        val ledger = LabProbeLedger(2); ledger.activate("run", "network")
        assertThrows(IllegalArgumentException::class.java) { ledger.begin(token().copy(runId = "other")) }
        for (id in listOf("a", "b", "c")) {
            val t = token(id); ledger.begin(t)
            assertFalse(ledger.finish(t, success(), 999))
            assertFalse(ledger.finish(t, success(), 1_100))
            assertTrue(ledger.finish(t, success(), 2_000))
        }
        assertEquals(listOf("b", "c"), ledger.history("guid").map { it.token.requestId })
        assertThrows(IllegalArgumentException::class.java) { LabProbeLedger(0) }
        assertThrows(IllegalArgumentException::class.java) { ledger.eligible("guid", "revision", null, -1, 1) }
    }
}
