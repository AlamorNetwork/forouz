package com.v2ray.ang.core.health

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthRecordsTest {
    private fun run(id: String) = HealthRun(id, 1_000, 1_000, 60, false)
    @Test fun `new document is empty and v1 round trip retains historical IDs and metrics`() {
        assertEquals(emptyList<HealthRun>(), HealthRecords.decode(null))
        val old = run("old").copy(status = HealthStatus.ENDED, checks = 4, requests = 5, failedChecks = 1,
            events = listOf(HealthEvent(1_000, HealthPhase.HEALTHY, "cellular", "profile", "session", 35)))
        assertEquals(listOf(old), HealthRecords.decode(HealthRecords.encode(listOf(old))))
    }
    @Test fun `new run interrupts orphan and keeps only five reports`() {
        var runs = listOf(run("orphan"))
        runs = HealthRecords.replace(runs, run("new"))
        assertEquals(HealthStatus.INTERRUPTED, runs[1].status)
        repeat(6) { runs = HealthRecords.replace(runs, run("run-$it")) }
        assertEquals(5, runs.size)
        assertEquals("run-5", runs.first().id)
    }
    @Test fun `existing heartbeat does not duplicate or reorder selected ID unexpectedly`() {
        val r = run("id")
        val next = r.event(HealthEvent(1_001, HealthPhase.GRACE))
        assertEquals(listOf(next), HealthRecords.replace(listOf(r), next))
    }
    @Test fun `event retention is bounded independently of total counts`() {
        var r = run("id")
        repeat(150) { r = r.event(HealthEvent(1_000L + it, HealthPhase.HEALTHY)) }
        assertEquals(120, r.events.size)
        assertEquals(1_030L, r.events.first().at)
        assertEquals(r, HealthRecords.decode(HealthRecords.encode(listOf(r))).single())
    }
    @Test fun `malformed unsupported version null enums and impossible budgets fail closed`() {
        val raw = HealthRecords.encode(listOf(run("id")))
        for (bad in listOf("", "{}", raw.replace("\"version\":1", "\"version\":2"),
            raw.replace("RUNNING", "BAD"), raw.replace("\"checks\":0", "\"checks\":241"),
            raw.replace("\"requests\":0", "\"requests\":481"), raw.replace("\"intervalSeconds\":60", "\"intervalSeconds\":0"))) {
            assertThrows(Exception::class.java) { HealthRecords.decode(bad) }
        }
    }
    @Test fun `invalid request and secret bearing unbounded identifiers are rejected`() {
        assertFalse(HealthRequest("", 60, true).valid())
        assertFalse(HealthRequest("https://secret", 60, true).valid())
        assertFalse(HealthRequest("ok", 1, true).valid())
        assertThrows(IllegalArgumentException::class.java) { HealthRecords.encode(listOf(run("id"), run("id"))) }
    }
}
