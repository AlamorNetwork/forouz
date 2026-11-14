package com.v2ray.ang.core.health

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthPolicyTest {
    @Test fun `new session needs grace and three failures with explicit consent and validated internet`() {
        val p = HealthPolicy(0, 60_000)
        assertFalse(p.observe("one", 0))
        assertTrue(p.observe("one", 60_000))
        repeat(2) { p.result(false) }
        assertFalse(p.canRecover(60_000, true, true))
        p.result(false)
        assertFalse(p.canRecover(60_000, false, true))
        assertFalse(p.canRecover(60_000, true, false))
        assertTrue(p.canRecover(60_000, true, true))
    }
    @Test fun `success and session network or config change discard prior failure streak`() {
        val p = HealthPolicy(0, 60_000)
        p.observe("session-network-revision", 0)
        repeat(3) { p.result(false) }
        p.result(true)
        assertEquals(0, p.failures)
        repeat(3) { p.result(false) }
        assertFalse(p.observe("new-key", 60_000))
        assertEquals(0, p.failures)
        assertFalse(p.canRecover(60_000, true, true))
    }
    @Test fun `sleep gap and loss reset streak without counting a successful measurement`() {
        val p = HealthPolicy(0, 30_000)
        p.observe("key", 0); repeat(3) { p.result(false) }
        assertFalse(p.observe("key", 200_000))
        assertEquals(3, p.checks)
        assertEquals(0, p.failures)
        assertFalse(p.observe(null, 230_000))
        assertFalse(p.observe("key", 260_000))
    }
    @Test fun `attempt budget survives session changes and backoff grows`() {
        val p = HealthPolicy(0, 30_000)
        p.observe("one", 0)
        repeat(3) { p.result(false) }
        assertTrue(p.canRecover(30_000, true, true))
        p.reserveRecovery(30_000)
        repeat(3) { p.result(false) }
        assertFalse(p.canRecover(89_999, true, true))
        assertTrue(p.canRecover(90_000, true, true))
        p.reserveRecovery(90_000)
        p.observe("two", 100_000); repeat(3) { p.result(false) }
        assertFalse(p.canRecover(269_999, true, true))
        assertTrue(p.canRecover(270_000, true, true))
        p.reserveRecovery(270_000)
        repeat(3) { p.result(false) }
        assertFalse(p.canRecover(1_000_000, true, true))
        assertEquals(3, p.attempts)
    }
    @Test fun `duration and finite check count bound the run`() {
        val p = HealthPolicy(10, 30_000)
        assertFalse(p.expired(7_200_009))
        assertTrue(p.expired(7_200_010))
        assertTrue(p.expired(9))
        repeat(240) { p.result(true) }
        assertTrue(p.expired(20))
    }
    @Test fun `stop before transaction prevents native calls`() {
        var calls = 0
        assertEquals(HealthRecoveryResult.STOPPED, guardedWarmRecovery({ false }, { calls++ }, { calls++ }, { true }))
        assertEquals(0, calls)
    }
    @Test fun `manual stop during native stop vetoes restore`() {
        val token = HealthRecoveryFence.token()
        var starts = 0
        val result = guardedWarmRecovery({ HealthRecoveryFence.permits(token) }, { HealthRecoveryFence.invalidate() }, { starts++ }, { true })
        assertEquals(HealthRecoveryResult.STOPPED, result)
        assertEquals(0, starts)
    }
    @Test fun `manual stop during native start immediately stops late core`() {
        val token = HealthRecoveryFence.token()
        var stops = 0
        val result = guardedWarmRecovery({ HealthRecoveryFence.permits(token) }, { stops++ }, { HealthRecoveryFence.invalidate() }, { true })
        assertEquals(HealthRecoveryResult.STOPPED, result)
        assertEquals(2, stops)
    }
    @Test fun `warm restore succeeds and failure always cleans native state`() {
        var running = true
        assertEquals(HealthRecoveryResult.RECOVERED, guardedWarmRecovery({ true }, { running = false }, { running = true }, { running }))
        var stops = 0
        assertThrows(IllegalStateException::class.java) {
            guardedWarmRecovery({ true }, { stops++ }, { error("fixture") }, { false })
        }
        assertEquals(2, stops)
        assertEquals(HealthRecoveryResult.FAILED, guardedWarmRecovery({ true }, {}, {}, { false }))
    }
}
