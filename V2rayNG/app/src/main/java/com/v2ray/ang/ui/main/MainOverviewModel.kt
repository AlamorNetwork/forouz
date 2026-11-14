package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionSnapshot
import com.v2ray.ang.dto.ConnectionTestResult

enum class MainCommandKind { START, STOP, RESTART }
data class MainPendingCommand(val kind: MainCommandKind, val targetGuid: String?, val previousSession: String?, val issuedAt: Long) {
    fun expired(now: Long): Boolean = now < issuedAt || now - issuedAt >= 30_000
    fun confirmed(snapshot: ConnectionSnapshot?): Boolean = when (kind) {
        MainCommandKind.STOP -> snapshot?.running == false
        MainCommandKind.START -> snapshot?.running == true && snapshot.profileGuid == targetGuid
        MainCommandKind.RESTART -> snapshot?.running == true && snapshot.profileGuid == targetGuid && snapshot.sessionId != previousSession
    }
}
enum class MainConnectionIssue { PERMISSION, START_FAILED, REQUEST_UNCONFIRMED }
enum class OverviewPhase { OBSERVING, EMPTY, DISCONNECTED, CONNECTING, DISCONNECTING, RECONNECTING, ACTIVE, CHECKING, VERIFIED, CHECK_FAILED, ERROR }

/** This is a dated HTTP(S) observation, not proof of anonymity or continuous Internet access. */
data class MainConnectivityCheck(val guid: String, val session: String, val completedAt: Long,
    val elapsedAt: Long, val delayMillis: Long) {
    fun belongsTo(snapshot: ConnectionSnapshot?): Boolean = snapshot?.let {
        it.running && it.profileGuid == guid && it.sessionId == session
    } == true
    fun fresh(now: Long): Boolean = now >= elapsedAt && now - elapsedAt < 120_000
}

internal fun MainUiState.withConnectivityCheck(result: ConnectionTestResult, wall: Long, elapsed: Long): MainUiState {
    val snapshot = connection.snapshot?.takeIf { isRunning && it.running } ?: return this
    return copy(connectivity = MainConnectivityCheck(snapshot.profileGuid, snapshot.sessionId, wall, elapsed, result.delayMillis),
        observedAt = elapsed)
}

internal fun MainUiState.overviewPhase(): OverviewPhase {
    pendingCommand?.let { return when (it.kind) {
        MainCommandKind.START -> OverviewPhase.CONNECTING
        MainCommandKind.STOP -> OverviewPhase.DISCONNECTING
        MainCommandKind.RESTART -> OverviewPhase.RECONNECTING
    } }
    if (!connectionKnown) return OverviewPhase.OBSERVING
    if (!isRunning) return if (connectionIssue != null) OverviewPhase.ERROR
        else if (selectedGuid.isNullOrBlank()) OverviewPhase.EMPTY else OverviewPhase.DISCONNECTED
    if (isCurrentTesting) return OverviewPhase.CHECKING
    val check = connectivity?.takeIf { it.belongsTo(connection.snapshot) && it.fresh(observedAt) }
    return when {
        check == null -> OverviewPhase.ACTIVE
        check.delayMillis >= 0 -> OverviewPhase.VERIFIED
        else -> OverviewPhase.CHECK_FAILED
    }
}

internal fun MainUiState.observeOverviewTime(now: Long): MainUiState {
    val timedOut = pendingCommand?.expired(now) == true
    val resolved = unconfirmedCommand?.confirmed(connection.snapshot) == true
    return copy(observedAt = now, pendingCommand = pendingCommand.takeUnless { timedOut },
        unconfirmedCommand = if (timedOut) pendingCommand else unconfirmedCommand.takeUnless { resolved },
        connectionIssue = if (timedOut) MainConnectionIssue.REQUEST_UNCONFIRMED else if (resolved) null else connectionIssue)
}

internal fun MainUiState.withActiveHomeProfile(guid: String?, profile: MainHomeProfile?): MainUiState =
    if (activeTestProfileGuid() == guid) copy(activeHomeProfile = profile) else this

internal fun MainUiState.overviewProfile(): MainHomeProfile? =
    if (isRunning) activeHomeProfile?.takeIf { it.guid == activeTestProfileGuid() }
    else homeProfile?.takeIf { it.guid == selectedGuid }

/** Explicit cancel remains effective if it arrived before the daemon registered its receiver. */
internal fun MainUiState.issueServiceCommand(kind: MainCommandKind, now: Long): MainUiState {
    val deferStop = kind == MainCommandKind.STOP && pendingCommand?.kind in listOf(MainCommandKind.START, MainCommandKind.RESTART)
    return copy(pendingCommand = MainPendingCommand(kind, selectedGuid, connection.snapshot?.sessionId, now),
        unconfirmedCommand = null, connectionIssue = null, observedAt = now,
        deferredStopUntil = if (deferStop) now + 30_000 else if (kind != MainCommandKind.STOP) 0 else deferredStopUntil)
}
internal fun MainUiState.shouldRepeatExplicitStop(running: Boolean, now: Long): Boolean =
    running && now >= 0 && now < deferredStopUntil

/** A daemon absence observation can confirm stop even when no telemetry session ever existed. */
internal fun MainUiState.observeNoService(now: Long): MainUiState {
    val stoppedCommand = pendingCommand?.kind == MainCommandKind.STOP || unconfirmedCommand?.kind == MainCommandKind.STOP
    return copy(connection = connection.observeStopped(now), connectedSeconds = 0, isRunning = false, connectionKnown = true,
        pendingCommand = pendingCommand?.takeUnless { it.kind == MainCommandKind.STOP },
        unconfirmedCommand = unconfirmedCommand?.takeUnless { it.kind == MainCommandKind.STOP }, connectivity = null,
        connectionIssue = if (stoppedCommand && connectionIssue == MainConnectionIssue.REQUEST_UNCONFIRMED) null else connectionIssue)
}
