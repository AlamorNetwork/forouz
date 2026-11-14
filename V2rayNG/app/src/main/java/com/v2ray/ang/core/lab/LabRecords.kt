package com.v2ray.ang.core.lab

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import java.io.Serializable
import java.security.MessageDigest

internal enum class LabRunStatus { RUNNING, COMPLETE, CANCELLED, INTERRUPTED, FAILED }
internal enum class LabRowStatus { WAITING, TESTING, SUCCESS, FAILED, SKIPPED, CANCELLED }
internal enum class LabIssue { NONE, UNSUPPORTED, CHANGED, NETWORK, CONFIG, HTTP, BODY, TLS, TIMEOUT, IO, STORAGE }

internal data class LabCandidate(val guid: String, val revision: String, val transport: LabTransport) : Serializable
internal data class LabStartRequest(val id: String, val groupId: String, val url: String,
    val candidates: List<LabCandidate>) : Serializable

internal data class LabRow(
    val guid: String,
    val revision: String,
    val transport: LabTransport,
    val status: LabRowStatus = LabRowStatus.WAITING,
    val issue: LabIssue = LabIssue.NONE,
    val responseMillis: Long? = null,
    val elapsedMillis: Long? = null,
    val bytes: Long = 0,
    val testedAt: Long? = null,
)

/** No endpoint, subscription URL, profile payload, credential or exception text is persisted. */
internal data class LabRunRecord(val id: String, val groupId: String, val createdAt: Long,
    val updatedAt: Long, val status: LabRunStatus, val rows: List<LabRow>, val issue: LabIssue = LabIssue.NONE,
    val networkKind: String = "unknown")

internal object LabRecords {
    const val MAX_TARGETS = 200
    const val MAX_RUNS = 20
    private val gson = Gson()
    private data class Document(val version: Int, val runs: List<LabRunRecord>)

    fun supported(profile: ProfileItem): Boolean = profile.configType !in setOf(
        EConfigType.AMNEZIAWG, EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN
    ) && profile.browserDialerMode.isNullOrBlank() && profile.insecure != true

    fun revision(profile: ProfileItem): String = MessageDigest.getInstance("SHA-256")
        .digest(JsonUtil.toJson(profile.duplicateIdentity()).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun encode(runs: List<LabRunRecord>): String {
        validate(runs)
        return gson.toJson(Document(1, runs))
    }

    fun decode(raw: String?): List<LabRunRecord> {
        if (raw == null) return emptyList()
        require(raw.length <= 8_000_000)
        val json = JsonParser.parseString(raw).asJsonObject
        require(json.get("version")?.asInt == 1 && json.get("runs")?.isJsonArray == true)
        json.getAsJsonArray("runs").forEach { if (!it.asJsonObject.has("networkKind")) it.asJsonObject.addProperty("networkKind", "unknown") }
        val document = gson.fromJson(json, Document::class.java)
        validate(document.runs)
        return document.runs.toList()
    }

    fun replace(runs: List<LabRunRecord>, record: LabRunRecord): List<LabRunRecord> =
        (listOf(record) + runs.filterNot { it.id == record.id }.map { old ->
            if (old.status == LabRunStatus.RUNNING && record.status == LabRunStatus.RUNNING && runs.none { it.id == record.id })
                old.copy(status = LabRunStatus.INTERRUPTED, rows = old.rows.map {
                    if (it.status in listOf(LabRowStatus.WAITING, LabRowStatus.TESTING)) it.copy(status = LabRowStatus.CANCELLED) else it
                }) else old
        }).take(MAX_RUNS).also(::validate)

    private fun validate(runs: List<LabRunRecord>) {
        require(runs.size <= MAX_RUNS && runs.map { it.id }.distinct().size == runs.size)
        runs.forEach { run ->
            require(run.id.isNotBlank() && run.createdAt >= 0 && run.updatedAt >= run.createdAt)
            require(run.networkKind in listOf("unknown", "wifi", "cellular", "ethernet", "other"))
            require(run.status in LabRunStatus.entries && run.issue in LabIssue.entries)
            require(run.rows.size <= MAX_TARGETS && run.rows.map { it.guid }.distinct().size == run.rows.size)
            run.rows.forEach { row ->
                require(row.guid.isNotBlank() && row.revision.matches(Regex("[a-f0-9]{64}")))
                require(row.status in LabRowStatus.entries && row.issue in LabIssue.entries && row.transport in LabTransport.entries)
                require(row.bytes in 0..LabPlan.DOWNLOAD_BYTES && (row.responseMillis == null || row.responseMillis >= 0))
                require(row.elapsedMillis == null || row.elapsedMillis >= (row.responseMillis ?: 0))
                require(row.testedAt == null || row.testedAt >= 0)
                if (row.status == LabRowStatus.SUCCESS) require(row.bytes == LabPlan.DOWNLOAD_BYTES &&
                    row.responseMillis != null && row.elapsedMillis != null && row.elapsedMillis > 0 && row.issue == LabIssue.NONE)
            }
        }
    }
}
