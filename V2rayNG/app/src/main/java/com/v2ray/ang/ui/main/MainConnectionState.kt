package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionSnapshot

/** Bounded presentation history. Session identity and monotonic time reject late IPC samples. */
data class MainConnectionState(
    val snapshot: ConnectionSnapshot? = null,
    val samples: List<ConnectionSnapshot> = emptyList(),
) {
    fun accept(next: ConnectionSnapshot): MainConnectionState {
        val current = snapshot
        if (current != null) {
            if (next.sessionId == current.sessionId) {
                if (!current.running || next.sampledAt < current.sampledAt) return this
                if (next == current) return this
            } else if (next.startedAt <= current.startedAt) return this
        }
        val history = if (current?.sessionId == next.sessionId) samples else emptyList()
        val updated = if (next.running && next.trafficAvailable && next.sampleReady &&
            history.lastOrNull()?.sampledAt != next.sampledAt) history + next else history
        return MainConnectionState(next, updated.filter { it.sampledAt >= next.sampledAt - WINDOW_MS }.takeLast(MAX_SAMPLES))
    }

    fun elapsedSeconds(now: Long): Long = snapshot?.takeIf { it.running }?.let {
        ((now - it.startedAt).coerceAtLeast(0) / 1000)
    } ?: 0

    /** Generic legacy state messages have no session ID; a snapshot supersedes them. */
    fun runningOr(fallback: Boolean): Boolean = snapshot?.running ?: fallback

    /** A fresh daemon acknowledgement/absence observation terminates cached UI state. */
    fun observeStopped(now: Long): MainConnectionState {
        val current = snapshot ?: return this
        return accept(current.copy(running = false, sampledAt = maxOf(now, current.sampledAt),
            uploadRate = 0, downloadRate = 0, proxyRate = 0, directRate = 0))
    }

    companion object {
        const val WINDOW_MS = 90_000L
        const val MAX_SAMPLES = 30
    }
}
