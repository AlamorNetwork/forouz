package com.v2ray.ang.core.cdn

import com.v2ray.ang.core.NativeDelayFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CdnBaselineControlsTest {
    @Test fun `network guard rejects changed absent VPN and interrupted networks`() {
        assertTrue(CdnBaselineControls.networkAllowed(7, 7, true, true, false, false))
        assertFalse(CdnBaselineControls.networkAllowed(7, 8, true, true, false, false))
        assertFalse(CdnBaselineControls.networkAllowed(7, null, true, true, false, false))
        assertFalse(CdnBaselineControls.networkAllowed(7, 7, false, true, false, false))
        assertFalse(CdnBaselineControls.networkAllowed(7, 7, true, false, true, false))
        assertFalse(CdnBaselineControls.networkAllowed(7, 7, true, true, false, true))
    }

    @Test fun `modes are sequential restore binding and persist separate results`() = runBlocking {
        val events = mutableListOf<String>()
        val results = mutableListOf<CdnControlResult>()
        var bound = true
        CdnBaselineControls.run(guard = { events += "guard" }, setBound = { bound = it; events += "bind=$it" },
            preflight = { assertTrue(bound); events += "tcp"; 5L },
            measure = { events += "measure=$bound"; CdnControlSample(if (bound) -1 else 424, if (bound) NativeDelayFailure.EOF else null) },
            publish = { assertTrue(bound); results += it })
        assertEquals(CdnControlMode.entries, results.map { it.mode })
        assertEquals(listOf(-1L, 424L, -1L), results.map { it.millis })
        assertEquals(listOf(null, null, 5L), results.map { it.tcpMillis })
        assertEquals(listOf("measure=true", "bind=false", "measure=false", "bind=true", "tcp", "measure=true"), events.filter { it != "guard" })
    }

    @Test fun `failed preflight cannot become an HTTPS success`() = runBlocking {
        var measures = 0
        val results = mutableListOf<CdnControlResult>()
        CdnBaselineControls.run({}, {}, { -1L }, { measures++; CdnControlSample(10) }, { results += it })
        assertEquals(2, measures)
        assertEquals(-1L, results.last().millis)
        assertEquals(-1L, results.last().tcpMillis)
        assertEquals(NativeDelayFailure.CONNECT, results.last().failure)
    }

    @Test fun `network guard failure after unbind restores binding before propagating`() {
        var bound = true
        var measures = 0
        assertThrows(IllegalStateException::class.java) { runBlocking {
            CdnBaselineControls.run(guard = { check(bound) }, setBound = { bound = it }, preflight = { 1L },
                measure = { measures++; CdnControlSample(10) }, publish = {})
        } }
        assertTrue(bound); assertEquals(1, measures)
    }

    @Test fun `cancellation during unbound native call restores binding and publishes no stale result`() {
        var bound = true
        val results = mutableListOf<CdnControlResult>()
        assertThrows(CancellationException::class.java) { runBlocking {
            CdnBaselineControls.run({}, { bound = it }, { 1L }, {
                if (!bound) currentCoroutineContext().job.cancel()
                CdnControlSample(10)
            }, { results += it })
        } }
        assertTrue(bound)
        assertEquals(listOf(CdnControlMode.BOUND), results.map { it.mode })
    }

    @Test fun `control success never promotes failed baseline and old history stays readable`() {
        val request = CdnRequest("controls", "guid", "a".repeat(64), "cloudflare", listOf("8.8.8.8"), false)
        val row = CdnRow(null, failures = 1, issue = com.v2ray.ang.core.lab.LabIssue.IO,
            nativeSamples = emptyList(), nativeFailure = NativeDelayFailure.EOF,
            baselineControls = listOf(CdnControlResult(CdnControlMode.DEFAULT_NETWORK, 424)))
        val run = CdnRun(request, 1, 1, CdnStatus.COMPLETE, listOf(row), 7)
        val json = CdnRecords.encode(run)
        assertEquals(run, CdnRecords.decode(json))
        assertNull(run.best()); assertFalse(row.usable(false))
        assertNull(CdnRecords.decode(CdnRecords.encode(run.copy(rows = listOf(row.copy(baselineControls = null)))) )?.rows?.single()?.baselineControls)
        for (bad in listOf(row.copy(address = "8.8.8.8"), row.copy(baselineControls = listOf(
            CdnControlResult(CdnControlMode.BOUND, 10), CdnControlResult(CdnControlMode.BOUND, 20))),
            row.copy(baselineControls = listOf(CdnControlResult(CdnControlMode.BOUND, 10, NativeDelayFailure.EOF))),
            row.copy(baselineControls = listOf(CdnControlResult(CdnControlMode.BOUND, 10, tcpMillis = 5))))) {
            assertThrows(Exception::class.java) { CdnRecords.encode(run.copy(rows = listOf(bad))) }
        }
    }
}
