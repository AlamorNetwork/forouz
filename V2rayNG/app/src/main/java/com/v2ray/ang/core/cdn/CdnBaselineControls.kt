package com.v2ray.ang.core.cdn

import com.v2ray.ang.core.NativeDelayFailure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class CdnControlMode { BOUND, DEFAULT_NETWORK, BOUND_TCP }
internal data class CdnControlSample(val millis: Long, val failure: NativeDelayFailure? = null)
internal data class CdnControlResult(val mode: CdnControlMode, val millis: Long,
    val failure: NativeDelayFailure? = null, val tcpMillis: Long? = null)

/** Baseline-only diagnostics. Results are never measurement samples or evidence qualifying an IP. */
internal object CdnBaselineControls {
    fun networkAllowed(selected: Long, active: Long?, internet: Boolean, notVpn: Boolean, vpn: Boolean, interrupted: Boolean) =
        selected == active && internet && notVpn && !vpn && !interrupted

    suspend fun run(guard: suspend () -> Unit, setBound: suspend (Boolean) -> Unit,
        preflight: suspend () -> Long, measure: suspend () -> CdnControlSample,
        publish: suspend (CdnControlResult) -> Unit) {
        suspend fun checkInputs() { currentCoroutineContext().ensureActive(); guard() }
        for (mode in CdnControlMode.entries) {
            checkInputs()
            var tcpMillis: Long? = null
            val sample = if (mode == CdnControlMode.DEFAULT_NETWORK) {
                try {
                    setBound(false)
                    checkInputs()
                    measure().also { checkInputs() }
                } finally {
                    // Restore even if cancellation or a failed network guard follows unbinding.
                    withContext(NonCancellable) { setBound(true) }
                }
            } else {
                if (mode == CdnControlMode.BOUND_TCP) {
                    tcpMillis = preflight()
                    checkInputs()
                }
                if (tcpMillis != null && tcpMillis < 0) CdnControlSample(-1, NativeDelayFailure.CONNECT)
                else measure().also { checkInputs() }
            }
            checkInputs()
            publish(CdnControlResult(mode, sample.millis.coerceAtLeast(-1),
                if (sample.millis < 0) sample.failure ?: NativeDelayFailure.UNKNOWN else null, tcpMillis))
        }
    }
}
