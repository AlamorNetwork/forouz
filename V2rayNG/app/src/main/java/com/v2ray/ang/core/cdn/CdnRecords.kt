package com.v2ray.ang.core.cdn

import com.google.gson.Gson
import com.v2ray.ang.core.NativeDelayFailure
import com.v2ray.ang.core.lab.LabIssue
import com.v2ray.ang.core.lab.LabTlsFailure
import java.io.Serializable

internal enum class CdnStatus { RUNNING, COMPLETE, CANCELLED, INTERRUPTED, FAILED }
internal data class CdnRequest(val id: String, val guid: String, val revision: String, val provider: String,
    val addresses: List<String>, val download: Boolean, val budget: Int = 64,
    val prefixes: List<String> = addresses.map { "$it/32" }, val networkHandle: Long = 0,
    val edgeScan: Boolean = false, val manual: Boolean = false) : Serializable {
    fun validate() {
        require(id.length in 1..100 && guid.length in 1..100 && revision.matches(Regex("[a-f0-9]{64}")))
        require(if (manual) edgeScan && provider == "manual" && addresses.size in 1..512
            else provider in setOf("cloudflare", "cloudfront") && addresses.size in 1..CdnPolicy.MAX_ADDRESSES)
        require(addresses.distinct().size == addresses.size && addresses.all(CdnPolicy::publicAddress))
        require(budget in 1..512 && prefixes.size in 1..2048)
        require(networkHandle >= 0)
        require(!edgeScan || !download)
        require(!manual || (budget == addresses.size && prefixes == addresses.map { "$it/32" }))
        val parsed = prefixes.map(CdnPrefix::parse).sortedBy { it.first }
        require(parsed.all(CdnPolicy::public) && parsed.zipWithNext().all { (a, b) -> a.last < b.first })
        require(addresses.all { ip -> parsed.any { it.contains(ip) } })
    }
}
internal data class CdnRow(val address: String?, val samples: List<Long> = emptyList(), val failures: Int = 0,
    val bytes: Long = 0, val elapsedMillis: Long? = null, val issue: LabIssue = LabIssue.NONE,
    val tlsFailure: LabTlsFailure? = null, val http1Control: LabIssue? = null, val nativeControlMillis: Long? = null,
    // Null identifies preceding HTTPS-TTFB reports; never reinterpret their samples as native delay.
    val nativeSamples: List<Long>? = null, val downloadIssue: LabIssue? = null, val downloadTlsFailure: LabTlsFailure? = null,
    val nativeFailure: NativeDelayFailure? = null, val baselineControls: List<CdnControlResult>? = null,
    val edge: CdnEdgeResult? = null) {
    private val latencySamples get() = nativeSamples ?: samples
    val median: Long? get() = latencySamples.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
    val variation: Long? get() = latencySamples.takeIf { it.size > 1 }?.let { it.max() - it.min() }
    fun downloadPassed() = bytes == 10_000_000L && (elapsedMillis ?: 0) > 0 &&
        (if (nativeSamples != null) downloadIssue == LabIssue.NONE else issue == LabIssue.NONE)
    fun usable(download: Boolean) = edge == null && latencySamples.size == CdnPolicy.SAMPLES && failures == 0 && issue == LabIssue.NONE &&
        (nativeSamples != null || !download || downloadPassed())
}
internal data class CdnRun(val request: CdnRequest, val createdAt: Long, val updatedAt: Long,
    val status: CdnStatus, val rows: List<CdnRow> = emptyList(), val networkHandle: Long = 0,
    val issue: LabIssue = LabIssue.NONE) {
    fun best(): CdnRow? = if (request.edgeScan) null else rows.filter { it.usable(request.download) }.minWithOrNull(
        compareBy<CdnRow> { request.download && !it.downloadPassed() }
            .thenBy { if (request.download && it.downloadPassed()) it.elapsedMillis else 0L }.thenBy { it.median }
            .thenBy { it.variation }.thenBy { it.address != null })
    fun canSave(now: Long, network: Long): Boolean = status == CdnStatus.COMPLETE &&
        now >= updatedAt && now - updatedAt <= 900_000 && network != 0L && network == networkHandle && best()?.address != null
    fun edgeRows(): List<CdnRow> = if (!request.edgeScan) emptyList() else rows.sortedWith(
        compareByDescending<CdnRow> { it.edge?.reachable() == true }
            .thenByDescending { it.edge?.tls?.passed() == true }.thenByDescending { it.edge?.tcp443?.passed() == true }
            .thenBy { it.edge?.let { e -> listOfNotNull(e.tls.millis, e.tcp443.millis, e.tcp80.millis).minOrNull() } ?: Long.MAX_VALUE }
            .thenBy { it.address })
    fun edgeAddresses(): List<String> = edgeRows().filter { it.edge?.reachable() == true }.mapNotNull { it.address }
        .filter(CdnPolicy::publicAddress).distinct()
}
internal object CdnRecords {
    private val gson = Gson()
    private data class Document(val version: Int, val run: CdnRun)
    fun encode(run: CdnRun): String { validate(run); return gson.toJson(Document(1, run)) }
    fun decode(raw: String?): CdnRun? {
        if (raw == null) return null
        require(raw.length <= 1_000_000)
        val doc = gson.fromJson(raw, Document::class.java)
        require(doc.version == 1)
        validate(doc.run)
        return doc.run
    }
    private fun validate(run: CdnRun) {
        run.request.validate()
        require(run.createdAt >= 0 && run.updatedAt >= run.createdAt && run.status in CdnStatus.entries && run.issue in LabIssue.entries)
        require(run.rows.size <= run.request.budget + 1 && run.rows.map { it.address }.distinct().size == run.rows.size)
        require(!run.request.edgeScan || run.rows.size <= run.request.budget)
        val prefixes = run.request.prefixes.map(CdnPrefix::parse)
        run.rows.forEach { row ->
            require((row.edge != null) == run.request.edgeScan)
            row.edge?.let { edge ->
                require(row.address != null && row.samples.isEmpty() && row.nativeSamples == null && row.failures == 0 &&
                    row.bytes == 0L && row.elapsedMillis == null && row.issue == LabIssue.NONE && row.tlsFailure == null &&
                    row.http1Control == null && row.nativeControlMillis == null && row.nativeFailure == null &&
                    row.downloadIssue == null && row.downloadTlsFailure == null && row.baselineControls == null)
                edge.validate()
            }
            require(row.address == null || prefixes.any { it.contains(row.address) })
            require(row.samples.size <= CdnPolicy.SAMPLES && row.samples.all { it >= 0 } && row.failures in 0..CdnPolicy.SAMPLES)
            require(row.samples.size + row.failures <= CdnPolicy.SAMPLES && row.bytes in 0..10_000_000 && row.issue in LabIssue.entries)
            require(row.elapsedMillis == null || row.elapsedMillis >= 0)
            require(row.tlsFailure == null || (row.issue == LabIssue.TLS && row.tlsFailure in LabTlsFailure.entries))
            require((row.http1Control == null && row.nativeControlMillis == null) ||
                (row.address == null && row.issue in setOf(LabIssue.TLS, LabIssue.TIMEOUT)))
            require(row.http1Control == null || row.http1Control in LabIssue.entries)
            require(row.nativeControlMillis == null || row.nativeControlMillis >= -1)
            row.nativeSamples?.let { samples ->
                require(row.samples.isEmpty() && samples.size + row.failures <= CdnPolicy.SAMPLES && samples.all { it >= 0 })
                require(row.tlsFailure == null && row.http1Control == null && row.nativeControlMillis == null)
            }
            require(row.downloadIssue == null || (run.request.download && row.nativeSamples != null &&
                row.nativeSamples.size == CdnPolicy.SAMPLES && row.failures == 0 && row.issue == LabIssue.NONE && row.downloadIssue in LabIssue.entries))
            require(row.downloadTlsFailure == null || (row.downloadIssue == LabIssue.TLS && row.downloadTlsFailure in LabTlsFailure.entries))
            require(row.nativeSamples == null || row.bytes == 0L || row.downloadIssue != null)
            require(row.downloadIssue != LabIssue.NONE || row.downloadPassed())
            require(row.nativeFailure == null || (row.nativeFailure in NativeDelayFailure.entries &&
                row.nativeSamples != null && row.failures > 0 && row.issue == LabIssue.IO))
            row.baselineControls?.let { results ->
                require(row.address == null && row.nativeSamples != null && !run.request.download)
                require(results.size in 1..CdnControlMode.entries.size && results.map { it.mode }.distinct().size == results.size)
                results.forEach { result ->
                    require(result.mode in CdnControlMode.entries && result.millis >= -1)
                    require(if (result.millis >= 0) result.failure == null else result.failure in NativeDelayFailure.entries)
                    require((result.mode == CdnControlMode.BOUND_TCP) == (result.tcpMillis != null))
                    require(result.tcpMillis == null || result.tcpMillis >= -1)
                    require(result.tcpMillis != -1L || (result.millis == -1L && result.failure == NativeDelayFailure.CONNECT))
                }
            }
        }
    }
}
