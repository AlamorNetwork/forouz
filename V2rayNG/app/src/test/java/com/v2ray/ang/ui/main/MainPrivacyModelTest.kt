package com.v2ray.ang.ui.main

import com.v2ray.ang.core.privacy.*
import com.v2ray.ang.dto.ConnectionSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MainPrivacyModelTest {
    private val target = PrivacyTarget("profile-a", "session-a")
    private val sample = PrivacyObservation(true, 10L, 100L, 100L, ipv4 = "203.0.113.7", resolvers = setOf("1.1.1.1"))

    @Test fun `initial state has no evidence and does not run automatically`() {
        val state = MainPrivacyState()
        assertNull(state.baseline); assertNull(state.sample); assertNull(state.requestId)
        assertFalse(state.showBaselineConfirmation)
        assertNull(MainUiState().privacyTarget())
    }
    @Test fun `connected result belongs only to the exact requested session`() {
        val state = MainPrivacyState().begin("request", target)
        val reply = PrivacyProbeReply.Observed(sample)
        assertEquals(state, state.complete("other", target, target, reply))
        assertEquals(state, state.complete("request", target, target.copy(sessionId = "session-b"), reply))
        val complete = state.complete("request", target, target, reply)
        assertEquals(sample, complete.sample); assertNull(complete.baseline); assertNull(complete.requestId)
    }
    @Test fun `late reply cannot restore cancelled or superseded evidence`() {
        val state = MainPrivacyState().begin("old", target).cancel()
        assertEquals(state, state.complete("old", target, target, PrivacyProbeReply.Observed(sample)))
        assertTrue(state.cancelled)
        val newer = state.begin("new", target)
        assertEquals(newer, newer.complete("old", target, target, PrivacyProbeReply.Observed(sample)))
    }
    @Test fun `direct sample is stored only for explicitly requested disconnected target`() {
        val direct = PrivacyTarget()
        val reply = PrivacyProbeReply.Observed(sample.copy(vpn = false))
        val state = MainPrivacyState().begin("base", direct).complete("base", direct, direct, reply)
        assertNotNull(state.baseline)
        assertNull(state.observeTarget(target).sample)
        assertEquals(state.baseline, state.observeTarget(target).baseline)
    }
    @Test fun `wrong network scope cannot become a baseline or VPN result`() {
        val direct = PrivacyTarget()
        val result = MainPrivacyState().begin("base", direct).complete("base", direct, direct, PrivacyProbeReply.Observed(sample))
        assertNull(result.baseline); assertEquals(PrivacyIssue.NETWORK_CHANGED, result.issue)
    }
    @Test fun `failure ends loading without fake measurements`() {
        for (issue in PrivacyIssue.entries) {
            val result = MainPrivacyState().begin("id", target).complete("id", target, target, PrivacyProbeReply.Unavailable(issue))
            assertNull(result.requestId); assertNull(result.sample); assertEquals(issue, result.issue)
        }
    }
    @Test fun `observed stop clears samples and invalidates work but retains voluntary baseline`() {
        val initial = MainPrivacyState(baseline = sample.copy(vpn = false)).begin("id", target)
        val stopped = initial.observeTarget(PrivacyTarget())
        assertNull(stopped.requestId); assertNull(stopped.sample); assertNotNull(stopped.baseline)
    }
    @Test fun `selected profile does not determine probe identity`() {
        val snapshot = ConnectionSnapshot("session-a", "profile-a", 10, 20)
        val state = MainUiState(connection = MainConnectionState(snapshot = snapshot), isRunning = true, selectedGuid = "profile-b")
        assertEquals(target, state.privacyTarget())
        assertNull(state.copy(connectionKnown = false).privacyTarget())
        assertNull(state.copy(pendingCommand = MainPendingCommand(MainCommandKind.START, "profile-b", null, 20)).privacyTarget())
    }
    @Test fun `runtime route changes invalidate an in-flight result even for the same profile`() {
        val socks = target.copy(route = PrivacyRoute.XRAY_SOCKS, proxyPort = 10808)
        val changed = socks.copy(proxyPort = 10809)
        val state = MainPrivacyState().begin("id", socks)
        assertNull(state.observeTarget(changed).requestId)
        assertEquals(state, state.complete("id", socks, changed, PrivacyProbeReply.Observed(sample)))
        val snapshot = ConnectionSnapshot("session-a", "profile-a", 10, 20,
            privacyRoute = PrivacyRoute.XRAY_SOCKS, privacyProxyPort = 10808)
        assertEquals(socks, MainUiState(connection = MainConnectionState(snapshot = snapshot), isRunning = true).privacyTarget())
    }
}
