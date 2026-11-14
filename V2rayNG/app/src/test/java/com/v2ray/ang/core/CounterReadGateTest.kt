package com.v2ray.ang.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CounterReadGateTest {
    @Test fun `stop and repeated stop prevent all later reads from that session`() {
        val gate = CounterReadGate()
        val token = gate.activate()
        assertEquals(42, gate.read(token) { 42 })
        gate.invalidate()
        gate.invalidate()
        assertNull(gate.read(token) { fail<Int>("Stopped reader queried native counters") })
    }

    @Test fun `restart token rejects previous session without consuming its counters`() {
        val gate = CounterReadGate()
        val old = gate.activate()
        val current = gate.activate()
        assertNull(gate.read(old) { fail<Int>("Old session consumed new counters") })
        assertEquals(7, gate.read(current) { 7 })
    }

    @Test fun `stop while read is in flight is nonblocking discards result and orders teardown`() {
        val gate = CounterReadGate()
        val token = gate.activate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nativeStopped = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val reading = workers.submit<Int?> { gate.read(token) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                100
            } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            gate.invalidate() // Must return while the simulated native query is still blocked.
            val stopping = workers.submit { gate.exclusive { nativeStopped.countDown() } }
            assertFalse(nativeStopped.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertNull(reading.get(3, TimeUnit.SECONDS))
            stopping.get(3, TimeUnit.SECONDS)
            assertEquals(0L, nativeStopped.count)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
