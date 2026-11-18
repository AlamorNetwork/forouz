package com.v2ray.ang.core.cdn

import com.v2ray.ang.core.lab.LabDownloadResult
import com.v2ray.ang.core.NativeDelayFailure
import com.v2ray.ang.core.lab.LabIssue
import com.v2ray.ang.core.lab.LabProbeFailure
import com.v2ray.ang.core.lab.LabTlsFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CdnNativeMeasurementsTest {
    private fun request(download: Boolean) = CdnRequest("run", "guid", "a".repeat(64), "cloudflare", listOf("8.8.8.8"), download)
    private fun run(row: CdnRow, download: Boolean) = CdnRun(request(download), 1000, 1000, CdnStatus.COMPLETE, listOf(row), 7)

    @Test fun `native failure code survives history without changing latency semantics`() = runBlocking {
        val row = CdnMeasurements.measureNative(null, "https://private-fixture.example/?key=private-fixture", { -1L },
            failure = { NativeDelayFailure.WEBSOCKET })
        assertEquals(NativeDelayFailure.WEBSOCKET, row.nativeFailure)
        assertEquals(LabIssue.IO, row.issue)
        assertFalse(row.usable(false))
        val raw = CdnRecords.encode(run(row, false))
        assertFalse(raw.contains("private-fixture"))
        assertEquals(row, CdnRecords.decode(raw)?.rows?.single())
        val historical = raw.replace(",\"nativeFailure\":\"WEBSOCKET\"", "")
        assertNull(CdnRecords.decode(historical)?.rows?.single()?.nativeFailure)
    }

    @Test fun `successful samples never acquire a failure diagnostic`() = runBlocking {
        val row = CdnMeasurements.measureNative(null, "https://fixture.example/", { 10L },
            failure = { error("must not read stale failure after success") })
        assertNull(row.nativeFailure)
        assertThrows(Exception::class.java) { CdnRecords.encode(run(row.copy(nativeFailure = NativeDelayFailure.UNKNOWN), false)) }
        val failed = CdnMeasurements.measureNative(null, "https://fixture.example/", { -1L })
        assertEquals(NativeDelayFailure.UNKNOWN, failed.nativeFailure)
    }

    @Test fun `native latency uses the exact configured URL three times and has separate samples`() = runBlocking {
        val urls = mutableListOf<String>()
        val values = listOf(50L, 90L, 70L).iterator()
        val row = CdnMeasurements.measureNative("8.8.8.8", "https://test.example/check?private=fixture", {
            urls += it; values.next()
        })
        assertEquals(List(3) { "https://test.example/check?private=fixture" }, urls)
        assertTrue(row.samples.isEmpty()); assertEquals(listOf(50L, 90L, 70L), row.nativeSamples)
        assertEquals(70L, row.median); assertEquals(40L, row.variation); assertTrue(row.usable(false))
        val raw = CdnRecords.encode(run(row, false))
        assertEquals(run(row, false), CdnRecords.decode(raw)); assertFalse(raw.contains("test.example")); assertFalse(raw.contains("private"))
    }

    @Test fun `native failure stops sampling and does not start a download or claim certificate failure`() = runBlocking {
        var calls = 0
        val row = CdnMeasurements.measureNative(null, "https://test.example/", { if (++calls == 1) 50L else -1L }, { error("unexpected download") })
        assertEquals(2, calls); assertEquals(listOf(50L), row.nativeSamples); assertEquals(1, row.failures)
        assertEquals(LabIssue.IO, row.issue); assertNull(row.tlsFailure); assertFalse(row.usable(false)); assertNull(row.downloadIssue)
        assertEquals(run(row, true), CdnRecords.decode(CdnRecords.encode(run(row, true))))
    }

    @Test fun `TLS or timeout in optional payload never invalidates native connectivity`() = runBlocking {
        for (issue in listOf(LabIssue.TLS, LabIssue.TIMEOUT, LabIssue.CONFIG)) {
            val row = CdnMeasurements.measureNative("8.8.8.8", "https://test.example/", { 80L }, {
                LabDownloadResult(issue, 0, failure = if (issue == LabIssue.TLS)
                    LabProbeFailure(issue, LabTlsFailure.HANDSHAKE, listOf("SSLHandshakeException")) else null)
            })
            assertTrue(row.usable(true)); assertEquals(LabIssue.NONE, row.issue); assertEquals(issue, row.downloadIssue)
            assertFalse(row.downloadPassed()); assertNotNull(run(row, true).best())
            assertEquals(run(row, true), CdnRecords.decode(CdnRecords.encode(run(row, true))))
        }
    }

    @Test fun `throughput success needs all bytes and positive duration and ranks before download failure`() = runBlocking {
        val complete = CdnMeasurements.measureNative("8.8.8.8", "https://test.example/", { 80L }, {
            LabDownloadResult(LabIssue.NONE, 10_000_000, 50, 200)
        })
        assertTrue(complete.downloadPassed()); assertTrue(complete.usable(true))
        assertEquals(run(complete, true), CdnRecords.decode(CdnRecords.encode(run(complete, true))))
        for (payload in listOf(LabDownloadResult(LabIssue.NONE, 100, 50, 200), LabDownloadResult(LabIssue.NONE, 10_000_000, 50, 0))) {
            val short = CdnMeasurements.measureNative(null, "https://test.example/", { 20L }, { payload })
            assertEquals(LabIssue.BODY, short.downloadIssue); assertFalse(short.downloadPassed()); assertTrue(short.usable(true))
            assertEquals(complete, run(short, true).copy(rows = listOf(short, complete)).best())
        }
    }

    @Test fun `cancellation after blocking native completion cannot publish samples or start payload`() {
        var calls = 0
        assertThrows(CancellationException::class.java) { runBlocking {
            CdnMeasurements.measureNative(null, "https://test.example/", {
                calls++; currentCoroutineContext().job.cancel(); 10L
            }, { error("unexpected download") })
        } }
        assertEquals(1, calls)
    }

    @Test fun `native report rejects mixed metrics invalid download status and false throughput success`() {
        val row = CdnRow("8.8.8.8", nativeSamples = listOf(50, 70, 90))
        for (bad in listOf(row.copy(samples = listOf(10)), row.copy(nativeSamples = listOf(-1)), row.copy(failures = 1),
            row.copy(downloadIssue = LabIssue.NONE), row.copy(downloadTlsFailure = LabTlsFailure.CHAIN),
            row.copy(nativeSamples = emptyList(), issue = LabIssue.IO, downloadIssue = LabIssue.TLS)))
            assertThrows(Exception::class.java) { CdnRecords.encode(run(bad, true)) }
    }
}
