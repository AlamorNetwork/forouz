package com.v2ray.ang.ui.main

import com.v2ray.ang.core.privacy.*

data class PrivacyTarget(val profileGuid: String? = null, val sessionId: String? = null,
    val route: PrivacyRoute = PrivacyRoute.UNAVAILABLE, val proxyPort: Int? = null) {
    val vpn: Boolean get() = profileGuid != null && sessionId != null
}

data class MainPrivacyState(
    val baseline: PrivacyObservation? = null,
    val sample: PrivacyObservation? = null,
    val sampleTarget: PrivacyTarget? = null,
    val requestId: String? = null,
    val requestTarget: PrivacyTarget? = null,
    val issue: PrivacyIssue? = null,
    val cancelled: Boolean = false,
    val showBaselineConfirmation: Boolean = false,
) {
    fun begin(id: String, target: PrivacyTarget) = copy(requestId = id, requestTarget = target,
        sample = null, sampleTarget = null, issue = null, cancelled = false, showBaselineConfirmation = false)

    fun cancel() = copy(requestId = null, requestTarget = null, cancelled = requestId != null || cancelled)

    fun observeTarget(target: PrivacyTarget?): MainPrivacyState {
        val keep = sampleTarget == target && target != null
        val state = if (requestId != null && requestTarget != target) cancel() else this
        return if (keep) state else state.copy(sample = null, sampleTarget = null)
    }

    fun complete(id: String, expected: PrivacyTarget, current: PrivacyTarget?, reply: PrivacyProbeReply): MainPrivacyState {
        if (requestId != id || requestTarget != expected || current != expected) return this
        val cleared = copy(requestId = null, requestTarget = null)
        return when (reply) {
            is PrivacyProbeReply.Unavailable -> cleared.copy(issue = reply.issue)
            is PrivacyProbeReply.Observed -> if (reply.observation.vpn != expected.vpn) cleared.copy(issue = PrivacyIssue.NETWORK_CHANGED)
                else if (expected.vpn) cleared.copy(sample = reply.observation, sampleTarget = expected)
                else cleared.copy(baseline = reply.observation, sample = reply.observation, sampleTarget = expected)
        }
    }
}

internal fun MainUiState.privacyTarget(): PrivacyTarget? = when {
    !connectionKnown || pendingCommand != null -> null
    !isRunning -> PrivacyTarget()
    connection.snapshot?.running == true -> connection.snapshot.let { PrivacyTarget(it.profileGuid, it.sessionId, it.privacyRoute, it.privacyProxyPort) }
    else -> null
}
