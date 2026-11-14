package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MainConnectionStateTest {
    private fun sample(time: Long = 4_000, session: String = "one", start: Long = 1_000) =
        ConnectionSnapshot(session, "guid-$session", start, time, trafficAvailable = true,
            sampleReady = true, downloadRate = 100, downloaded = 300)

    @Test fun `initial and unsupported connection have no fabricated traffic`() {
        assertTrue(MainConnectionState().samples.isEmpty())
        assertEquals(0L, MainConnectionState().elapsedSeconds(50_000))
        val state = MainConnectionState().accept(sample().copy(trafficAvailable = false, sampleReady = false))
        assertTrue(state.samples.isEmpty())
        assertEquals(49L, state.elapsedSeconds(50_000))
    }

    @Test fun `reopening uses daemon origin instead of view open time`() {
        val state = MainConnectionState().accept(sample(time = 40_000))
        assertEquals(39L, state.elapsedSeconds(40_500))
        assertEquals(0L, state.elapsedSeconds(999))
    }

    @Test fun `duplicate and old samples do not grow history or overwrite state`() {
        val current = MainConnectionState().accept(sample(7_000))
        assertSame(current, current.accept(sample(7_000)))
        assertSame(current, current.accept(sample(4_000)))
        assertSame(current, current.accept(sample(10_000, "old", start = 0)))
    }

    @Test fun `stopped session rejects delayed running samples and timer clears`() {
        val current = MainConnectionState().accept(sample())
            .accept(sample(7_000).copy(running = false))
        assertSame(current, current.accept(sample(10_000)))
        assertEquals(0L, current.elapsedSeconds(100_000))
        assertFalse(current.snapshot!!.running)
    }

    @Test fun `restart and profile change reset history and uptime`() {
        val previous = MainConnectionState().accept(sample())
        val fresh = previous.accept(sample(20_000, "two", start = 20_000).copy(sampleReady = false))
        assertTrue(fresh.samples.isEmpty())
        assertEquals("guid-two", fresh.snapshot!!.profileGuid)
        assertEquals(2L, fresh.elapsedSeconds(22_000))
    }

    @Test fun `session snapshot supersedes uncorrelated legacy state messages`() {
        assertTrue(MainConnectionState().runningOr(true))
        assertFalse(MainConnectionState().runningOr(false))
        val running = MainConnectionState().accept(sample())
        assertTrue(running.runningOr(false)) // Late Stop from a previous engine cannot stop this UI session.
        val stopped = running.accept(sample(7_000).copy(running = false))
        assertFalse(stopped.runningOr(true)) // Late Start cannot resurrect a stopped session.
    }

    @Test fun `resume after daemon death clears cached uptime without allowing late samples to resurrect it`() {
        val observed = MainConnectionState().accept(sample()).observeStopped(10_000)
        assertFalse(observed.runningOr(true))
        assertEquals(0L, observed.elapsedSeconds(20_000))
        assertSame(observed, observed.accept(sample(11_000)))
        val recovered = observed.accept(sample(12_000, "recovered", start = 12_000))
        assertTrue(recovered.runningOr(false))
    }

    @Test fun `history is bounded by both time and count and stopped samples never add zero points`() {
        var state = MainConnectionState()
        for (i in 1..100) state = state.accept(sample(1_000 + i * 3_000L))
        assertEquals(MainConnectionState.MAX_SAMPLES, state.samples.size)
        state = state.accept(sample(400_000))
        assertEquals(1, state.samples.size)
        val stopped = state.accept(sample(400_001).copy(running = false, downloadRate = 0))
        assertEquals(state.samples, stopped.samples)
    }
}
