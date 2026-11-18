package com.v2ray.ang.core.cdn

import com.v2ray.ang.core.NativeDelayFailure
import com.v2ray.ang.core.lab.LabDownloadResult
import com.v2ray.ang.core.lab.LabHttpProbe
import com.v2ray.ang.core.lab.LabIssue
import com.v2ray.ang.core.lab.LabPlan
import com.v2ray.ang.core.lab.LabTlsFailure
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object CdnMeasurements {
    /** Same native latency contract as Profiles. Throughput cannot invalidate a successful delay. */
    suspend fun measureNative(address: String?, url: String, delay: suspend (String) -> Long,
        download: (suspend () -> LabDownloadResult)? = null, failure: () -> NativeDelayFailure? = { null }): CdnRow {
        val samples = mutableListOf<Long>()
        repeat(CdnPolicy.SAMPLES) {
            currentCoroutineContext().ensureActive()
            val value = delay(url)
            currentCoroutineContext().ensureActive()
            if (value < 0) return CdnRow(address, failures = 1, issue = LabIssue.IO,
                nativeSamples = samples.toList(), nativeFailure = failure() ?: NativeDelayFailure.UNKNOWN)
            samples += value
        }
        val payload = download?.let {
            currentCoroutineContext().ensureActive()
            it().also { currentCoroutineContext().ensureActive() }
        }
        val payloadIssue = payload?.let {
            if (it.issue == LabIssue.NONE && (it.bytes != LabPlan.DOWNLOAD_BYTES || (it.elapsedMillis ?: 0) <= 0)) LabIssue.BODY else it.issue
        }
        return CdnRow(address, nativeSamples = samples.toList(), bytes = payload?.bytes ?: 0,
            elapsedMillis = payload?.elapsedMillis, downloadIssue = payloadIssue, downloadTlsFailure = payload?.failure?.tls)
    }

    /** A successful control never replaces failed measurement samples or qualifies an Address. */
    suspend fun compareBaseline(row: CdnRow, http1: suspend () -> LabIssue, native: suspend () -> Long): CdnRow {
        if (row.address != null || row.issue !in setOf(LabIssue.TLS, LabIssue.TIMEOUT) ||
            row.http1Control != null || row.nativeControlMillis != null) return row
        val http1Issue = http1()
        val nativeMillis = native().coerceAtLeast(-1)
        return row.copy(http1Control = http1Issue, nativeControlMillis = nativeMillis)
    }

    suspend fun measure(address: String?, download: Boolean,
        probe: suspend (String, Long) -> LabDownloadResult): CdnRow {
        val samples = mutableListOf<Long>()
        var failures = 0
        var issue = LabIssue.NONE
        var tlsFailure: LabTlsFailure? = null
        for (attempt in 0 until CdnPolicy.SAMPLES) {
            val result = probe(CdnPolicy.LATENCY_URL, CdnPolicy.LATENCY_BYTES)
            if (result.issue == LabIssue.NONE && result.bytes == CdnPolicy.LATENCY_BYTES && result.responseMillis != null)
                samples.add(result.responseMillis)
            else {
                failures++; issue = if (result.issue == LabIssue.NONE) LabIssue.BODY else result.issue
                tlsFailure = result.failure?.tls
                break
            }
        }
        val payload = if (download && failures == 0) probe(LabHttpProbe.DEFAULT_URL, LabPlan.DOWNLOAD_BYTES) else null
        if (payload != null && payload.issue != LabIssue.NONE) { issue = payload.issue; tlsFailure = payload.failure?.tls }
        return CdnRow(address, samples.toList(), failures, payload?.bytes ?: 0, payload?.elapsedMillis, issue, tlsFailure)
    }
}
