package com.v2ray.ang.core.lab

internal enum class LabFailureStage { CONFIG, DNS, TCP, TLS, TRANSPORT, HTTP, BODY, UNKNOWN }

/** Non-secret ownership keys. networkEpoch is an opaque generation, not an SSID or address. */
internal data class LabProbeToken(
    val requestId: String,
    val runId: String,
    val target: LabTarget,
    val networkEpoch: String,
    val configRevision: String,
    val sessionId: String?,
    val startedAt: Long,
) {
    init {
        require(requestId.isNotBlank() && runId.isNotBlank() && networkEpoch.isNotBlank())
        require(configRevision.isNotBlank() && startedAt >= 0)
    }
}

internal data class LabTransferMetrics(
    val responseDelayMillis: Long,
    val elapsedMillis: Long,
    val downloadedBytes: Long,
    val expectedBytes: Long? = null,
) {
    init {
        require(responseDelayMillis >= 0 && elapsedMillis >= responseDelayMillis)
        require(downloadedBytes >= 0 && (expectedBytes == null || expectedBytes > 0))
    }

    val completeDownload: Boolean get() = expectedBytes != null && downloadedBytes == expectedBytes

    // Whole-request throughput, including setup. This is not the response-delay measurement.
    val downloadMbps: Double?
        get() = if (completeDownload && elapsedMillis > 0) downloadedBytes.toDouble() * 8 / elapsedMillis / 1000
        else null
}

internal sealed interface LabProbeOutcome {
    data class Success(val metrics: LabTransferMetrics) : LabProbeOutcome {
        init { require(metrics.expectedBytes == null || metrics.completeDownload) }
    }
    data class Failure(val stage: LabFailureStage, val downloadedBytes: Long = 0) : LabProbeOutcome {
        init { require(downloadedBytes >= 0) }
    }
    data object Unsupported : LabProbeOutcome
    data object Cancelled : LabProbeOutcome
}

internal data class LabProbeSample(val token: LabProbeToken, val outcome: LabProbeOutcome, val finishedAt: Long)

/**
 * Pure state for a single lab run owner, separate from MainTestRequests' UI request lifetime.
 * No native calls, timers, persistence, connection changes or authority over daemon state.
 */
internal class LabProbeLedger(private val retainedPerProfile: Int = 60) {
    private var runId: String? = null
    private var networkEpoch: String? = null
    private val pending = mutableMapOf<String, LabProbeToken>()
    private val samples = mutableMapOf<String, List<LabProbeSample>>()

    init { require(retainedPerProfile in 1..1000) }

    fun activate(run: String, network: String) {
        require(run.isNotBlank() && network.isNotBlank())
        pending.clear()
        runId = run
        networkEpoch = network
    }

    fun begin(token: LabProbeToken) {
        require(token.runId == runId && token.networkEpoch == networkEpoch)
        require(pending.values.none { it.requestId == token.requestId })
        // Supersede a prior request for this GUID without accepting its later reply.
        pending[token.target.guid] = token
    }

    fun finish(token: LabProbeToken, outcome: LabProbeOutcome, now: Long): Boolean {
        if (token.runId != runId || token.networkEpoch != networkEpoch ||
            pending[token.target.guid] != token || now < token.startedAt) return false
        if (outcome is LabProbeOutcome.Success && outcome.metrics.elapsedMillis > now - token.startedAt) return false
        pending.remove(token.target.guid)
        val old = samples[token.target.guid].orEmpty()
        samples[token.target.guid] = (old + LabProbeSample(token, outcome, now)).takeLast(retainedPerProfile)
        return true
    }

    fun invalidate(guid: String) { pending.remove(guid) }

    fun stop() {
        pending.clear()
        runId = null
        networkEpoch = null
    }

    fun history(guid: String): List<LabProbeSample> = samples[guid].orEmpty().toList()

    /** Unsupported/cancelled attempts are not failures, but cannot nominate a candidate either. */
    fun eligible(guid: String, revision: String, sessionId: String?, now: Long, maxAgeMillis: Long): Boolean {
        require(now >= 0 && maxAgeMillis >= 0)
        val latest = samples[guid].orEmpty().lastOrNull {
            it.token.networkEpoch == networkEpoch && it.token.configRevision == revision &&
            it.token.sessionId == sessionId
        } ?: return false
        return runId != null && latest.token.runId == runId && now >= latest.finishedAt &&
            now - latest.finishedAt <= maxAgeMillis && latest.outcome is LabProbeOutcome.Success
    }
}
