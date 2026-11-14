package com.v2ray.ang.core.health

import java.util.concurrent.atomic.AtomicLong

/** Daemon stop intent wins even when native stop/start cannot be cancelled. No service is started. */
internal object HealthRecoveryFence {
    private val generation = AtomicLong()
    fun token(): Long = generation.get()
    fun invalidate() { generation.incrementAndGet() }
    fun permits(token: Long): Boolean = token == generation.get()
}

internal enum class HealthPhase {
    WAITING_VPN, WAITING_NETWORK, GRACE, HEALTHY, FAILED_CHECK, UNSUPPORTED,
    CHANGED, RECOVERY, RECOVERED, RECOVERY_SKIPPED, RECOVERY_FAILED, FINISHED
}

/** Monotonic clock only. Failure means an HTTPS round failed, never proof of DPI or a VPN drop. */
internal class HealthPolicy(private val startedAt: Long, private val intervalMillis: Long) {
    companion object {
        const val DURATION = 7_200_000L
        const val MAX_CHECKS = 240
        const val MAX_ATTEMPTS = 3
        const val GRACE = 30_000L
    }
    var attempts = 0; private set
    var checks = 0; private set
    var failures = 0; private set
    private var identity: String? = null
    private var lastAt = startedAt
    private var graceUntil = startedAt + GRACE
    private var recoveryAt = startedAt

    fun expired(now: Long): Boolean = now < startedAt || now - startedAt >= DURATION || checks >= MAX_CHECKS

    fun observe(key: String?, now: Long): Boolean {
        if (key == null || key != identity || now - lastAt > intervalMillis * 2 + 10_000 || now < lastAt) {
            identity = key
            failures = 0
            graceUntil = now + GRACE
        }
        lastAt = now
        return key != null && now >= graceUntil
    }

    fun result(success: Boolean) {
        checks++
        failures = if (success) 0 else failures + 1
    }

    fun canRecover(now: Long, enabled: Boolean, validated: Boolean): Boolean =
        enabled && validated && !expired(now) && now >= graceUntil && failures >= 3 &&
            attempts < MAX_ATTEMPTS && now >= recoveryAt

    fun reserveRecovery(now: Long) {
        check(attempts < MAX_ATTEMPTS)
        recoveryAt = now + listOf(60_000L, 180_000L, 600_000L)[attempts]
        attempts++
        failures = 0
        graceUntil = now + GRACE
    }
}

internal enum class HealthRecoveryResult { RECOVERED, SKIPPED, STOPPED, FAILED }

/** Once stopped, restore even if observation is cancelled. Only a VPN stop intent may veto restore. */
internal fun guardedWarmRecovery(allowed: () -> Boolean, stop: () -> Unit, start: () -> Unit,
    running: () -> Boolean): HealthRecoveryResult {
    if (!allowed()) return HealthRecoveryResult.STOPPED
    return try {
        stop()
        if (!allowed()) return HealthRecoveryResult.STOPPED
        start()
        if (!allowed()) {
            stop()
            HealthRecoveryResult.STOPPED
        } else if (running()) HealthRecoveryResult.RECOVERED
        else { stop(); HealthRecoveryResult.FAILED }
    } catch (error: Exception) {
        try { stop() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        throw error
    }
}
