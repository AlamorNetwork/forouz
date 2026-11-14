package com.v2ray.ang.core.health

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.Serializable

internal data class HealthRequest(val id: String, val intervalSeconds: Int, val recover: Boolean) : Serializable {
    fun valid(): Boolean = id.matches(Regex("[a-zA-Z0-9-]{1,64}")) && intervalSeconds in listOf(30, 60, 120)
}
internal enum class HealthStatus { RUNNING, ENDED, CANCELLED, INTERRUPTED, ERROR }
internal data class HealthEvent(val at: Long, val phase: HealthPhase, val network: String = "unknown",
    val profileGuid: String = "", val sessionId: String = "", val delayMillis: Long? = null)
/** Deliberately excludes configuration, URLs, network names and exception messages. */
internal data class HealthRun(val id: String, val createdAt: Long, val updatedAt: Long,
    val intervalSeconds: Int, val recover: Boolean, val status: HealthStatus = HealthStatus.RUNNING,
    val checks: Int = 0, val failedChecks: Int = 0, val requests: Int = 0, val attempts: Int = 0,
    val events: List<HealthEvent> = emptyList()) {
    fun event(event: HealthEvent): HealthRun = copy(updatedAt = maxOf(createdAt, event.at), events = (events + event).takeLast(120))
}

internal object HealthRecords {
    private val gson = Gson()
    private data class Document(val version: Int, val runs: List<HealthRun>)
    fun encode(runs: List<HealthRun>): String { validate(runs); return gson.toJson(Document(1, runs)) }
    fun decode(raw: String?): List<HealthRun> {
        if (raw == null) return emptyList()
        require(raw.length <= 1_000_000)
        val json = JsonParser.parseString(raw).asJsonObject
        require(json.get("version")?.asInt == 1 && json.get("runs")?.isJsonArray == true)
        return gson.fromJson(json, Document::class.java).runs.also(::validate)
    }
    fun replace(runs: List<HealthRun>, run: HealthRun): List<HealthRun> =
        (listOf(run) + runs.filterNot { it.id == run.id }.map {
            if (run.status == HealthStatus.RUNNING && it.status == HealthStatus.RUNNING) it.copy(status = HealthStatus.INTERRUPTED) else it
        }).take(5).also(::validate)

    private fun validate(runs: List<HealthRun>) {
        require(runs.size <= 5 && runs.map { it.id }.distinct().size == runs.size)
        runs.forEach { r ->
            require(HealthRequest(r.id, r.intervalSeconds, r.recover).valid())
            require(r.createdAt >= 0 && r.updatedAt >= r.createdAt && r.status in HealthStatus.entries)
            require(r.checks in 0..240 && r.failedChecks in 0..r.checks && r.requests in 0..480 && r.attempts in 0..3)
            require(r.events.size <= 120)
            r.events.forEach {
                require(it.at >= r.createdAt && it.phase in HealthPhase.entries)
                require(it.network in listOf("unknown", "wifi", "cellular", "ethernet", "other"))
                require(it.profileGuid.length <= 128 && it.sessionId.length <= 128)
                require(it.delayMillis == null || it.delayMillis >= 0)
            }
        }
    }
}
