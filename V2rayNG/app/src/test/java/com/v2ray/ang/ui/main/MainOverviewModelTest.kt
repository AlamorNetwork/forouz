package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionSnapshot
import com.v2ray.ang.dto.ConnectionTestResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MainOverviewModelTest {
    private fun active(guid: String = "active", session: String = "session") = MainUiState(isRunning = true, selectedGuid = guid,
        connection = MainConnectionState(ConnectionSnapshot(session, guid, 10, 10)), observedAt = 1_000)

    @Test fun `no profile disconnected service and active tunnel are distinct`() {
        assertEquals(OverviewPhase.EMPTY, MainUiState(connectionKnown = true).overviewPhase())
        assertEquals(OverviewPhase.EMPTY, MainUiState(connectionKnown = true, selectedGuid = "").overviewPhase())
        assertEquals(OverviewPhase.DISCONNECTED, MainUiState(connectionKnown = true, selectedGuid = "chosen").overviewPhase())
        assertEquals(OverviewPhase.ACTIVE, active().overviewPhase())
    }
    @Test fun `running service alone never verifies Internet access`() {
        assertEquals(OverviewPhase.ACTIVE, MainUiState(connectionKnown = true, isRunning = true).overviewPhase())
        assertNull(MainUiState(isRunning = true).withConnectivityCheck(ConnectionTestResult(1), 10, 10).connectivity)
    }
    @Test fun `successful request including zero delay verifies only its own current session`() {
        val checked = active().withConnectivityCheck(ConnectionTestResult(0), 50_000, 1_000)
        assertEquals(OverviewPhase.VERIFIED, checked.overviewPhase())
        val changed = checked.copy(connection = MainConnectionState(ConnectionSnapshot("new-session", "active", 20, 20)))
        assertEquals(OverviewPhase.ACTIVE, changed.overviewPhase())
        assertEquals(OverviewPhase.ACTIVE, checked.copy(connection = MainConnectionState(ConnectionSnapshot("session", "other", 10, 10))).overviewPhase())
    }
    @Test fun `failed request never says disconnected while the service is still active`() {
        val failed = active().withConnectivityCheck(ConnectionTestResult(-1), 1, 1_000)
        assertEquals(OverviewPhase.CHECK_FAILED, failed.overviewPhase())
        assertTrue(failed.isRunning)
    }
    @Test fun `dated success becomes neutral after two minutes or a backwards clock`() {
        val checked = active().withConnectivityCheck(ConnectionTestResult(35), 50_000, 1_000)
        assertEquals(OverviewPhase.VERIFIED, checked.observeOverviewTime(120_999).overviewPhase())
        assertEquals(OverviewPhase.ACTIVE, checked.observeOverviewTime(121_000).overviewPhase())
        assertEquals(OverviewPhase.ACTIVE, checked.observeOverviewTime(999).overviewPhase())
    }
    @Test fun `bulk testing does not impersonate an active connection check`() {
        assertEquals(OverviewPhase.ACTIVE, active().copy(isTesting = true).overviewPhase())
        assertEquals(OverviewPhase.CHECKING, active().copy(isTesting = true, isCurrentTesting = true).overviewPhase())
    }
    @Test fun `active card resolves the real active profile even when another is selected`() {
        val running = MainHomeProfile("active", "running", "VLESS / ws / tls", false, 10)
        val next = MainHomeProfile("next", "next", "AmneziaWG", true, null)
        val state = active().copy(selectedGuid = "next", homeProfile = next, activeHomeProfile = running)
        assertEquals(running, state.overviewProfile())
        assertEquals(next, state.copy(isRunning = false).overviewProfile())
        assertEquals(running, state.withActiveHomeProfile("old", next).activeHomeProfile)
    }
    @Test fun `pending start restart and stop have separate observable presentation`() {
        assertEquals(OverviewPhase.CONNECTING, active().issueServiceCommand(MainCommandKind.START, 100).overviewPhase())
        assertEquals(OverviewPhase.RECONNECTING, active().issueServiceCommand(MainCommandKind.RESTART, 100).overviewPhase())
        assertEquals(OverviewPhase.DISCONNECTING, active().issueServiceCommand(MainCommandKind.STOP, 100).overviewPhase())
        assertFalse(active().issueServiceCommand(MainCommandKind.STOP, 100).canTestConnection())
    }
    @Test fun `start acknowledgement must match GUID and restart requires a new session`() {
        val old = ConnectionSnapshot("old", "target", 10, 10)
        val fresh = ConnectionSnapshot("new", "target", 20, 20)
        assertTrue(MainPendingCommand(MainCommandKind.START, "target", null, 1).confirmed(old))
        assertFalse(MainPendingCommand(MainCommandKind.START, "different", null, 1).confirmed(old))
        assertFalse(MainPendingCommand(MainCommandKind.RESTART, "target", "old", 1).confirmed(old))
        assertTrue(MainPendingCommand(MainCommandKind.RESTART, "target", "old", 1).confirmed(fresh))
        assertFalse(MainPendingCommand(MainCommandKind.STOP, "target", null, 1).confirmed(old))
        assertTrue(MainPendingCommand(MainCommandKind.STOP, "target", null, 1).confirmed(old.copy(running = false)))
    }
    @Test fun `unconfirmed request times out and late real acknowledgement clears the issue`() {
        val pending = MainUiState(selectedGuid = "active").issueServiceCommand(MainCommandKind.START, 100)
        val expired = pending.observeOverviewTime(30_100)
        assertNull(expired.pendingCommand)
        assertEquals(MainConnectionIssue.REQUEST_UNCONFIRMED, expired.connectionIssue)
        val late = expired.copy(isRunning = true, connection = active().connection).observeOverviewTime(30_101)
        assertNull(late.connectionIssue)
        assertNull(late.unconfirmedCommand)
    }
    @Test fun `explicit cancel before receiver registration is remembered for a finite window`() {
        val starting = MainUiState(selectedGuid = "next").issueServiceCommand(MainCommandKind.START, 100)
        val cancelled = starting.issueServiceCommand(MainCommandKind.STOP, 200)
        assertFalse(cancelled.shouldRepeatExplicitStop(false, 300))
        assertTrue(cancelled.shouldRepeatExplicitStop(true, 300))
        assertFalse(cancelled.shouldRepeatExplicitStop(true, 30_200))
        assertFalse(cancelled.copy(deferredStopUntil = 0).shouldRepeatExplicitStop(true, 300))
    }
    @Test fun `new user start supersedes an older explicit cancel and normal stop never queues a future start`() {
        val cancelled = active().issueServiceCommand(MainCommandKind.RESTART, 100).issueServiceCommand(MainCommandKind.STOP, 200)
        assertTrue(cancelled.shouldRepeatExplicitStop(true, 250))
        assertEquals(0L, cancelled.issueServiceCommand(MainCommandKind.START, 300).deferredStopUntil)
        assertEquals(0L, active().issueServiceCommand(MainCommandKind.STOP, 100).deferredStopUntil)
    }
    @Test fun `request tracker exposes current testing independently of bulk ownership`() {
        val requests = MainTestRequests()
        requests.beginBulk("group")
        assertTrue(requests.isTesting)
        assertFalse(requests.isCurrentTesting)
        val id = requests.beginCurrent("profile", "session")
        assertTrue(requests.isCurrentTesting)
        assertFalse(requests.completeCurrent("old"))
        assertTrue(requests.isCurrentTesting)
        assertTrue(requests.completeCurrent(id))
        assertFalse(requests.isCurrentTesting)
        assertTrue(requests.isTesting)
    }

    @Test fun `unobserved daemon is neither disconnected nor verified and cannot start a test`() {
        assertEquals(OverviewPhase.OBSERVING, MainUiState().overviewPhase())
        val old = active().withConnectivityCheck(ConnectionTestResult(12), 1, 1_000).copy(connectionKnown = false)
        assertEquals(OverviewPhase.OBSERVING, old.overviewPhase())
        assertFalse(old.canTestConnection())
        assertEquals(OverviewPhase.VERIFIED, old.copy(connectionKnown = true).overviewPhase())
    }

    @Test fun `confirmed absence completes stop without a snapshot but keeps late-start cancel protection`() {
        val starting = MainUiState(selectedGuid = "target").issueServiceCommand(MainCommandKind.START, 100)
        assertNotNull(starting.observeNoService(150).pendingCommand)
        val cancelled = starting.issueServiceCommand(MainCommandKind.STOP, 200).observeNoService(250)
        assertNull(cancelled.pendingCommand)
        assertTrue(cancelled.connectionKnown)
        assertFalse(cancelled.isRunning)
        assertTrue(cancelled.shouldRepeatExplicitStop(true, 300))
    }

}
