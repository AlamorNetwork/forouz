package com.v2ray.ang.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.Serializable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface ConnectionProbeReply {
    data class Result(val value: ConnectionTestResult) : ConnectionProbeReply
    data object Unavailable : ConnectionProbeReply
    data object TimedOut : ConnectionProbeReply
}

/** Retains recent managed IDs after completion so delayed raw broadcasts cannot race the mailbox result. */
internal class ProbeReplyWindow(private val capacity: Int = 64) {
    private val ids = linkedSetOf<String>()
    @Synchronized fun track(id: String) {
        ids.remove(id)
        ids.add(id)
        while (ids.size > capacity) ids.remove(ids.first())
    }
    @Synchronized operator fun contains(id: String): Boolean = id in ids
}

/** Scope-free mailbox: the awaiting ViewModel or batch owns cancellation and lifetime. */
internal class ConnectionProbeMailbox(private val requestId: String) {
    private val reply = CompletableDeferred<ConnectionProbeReply>()
    fun receive(id: String, result: ConnectionTestResult?, cancelled: Boolean): Boolean {
        if (id != requestId || (result == null && !cancelled)) return false
        return reply.complete(result?.let { ConnectionProbeReply.Result(it) } ?: ConnectionProbeReply.Unavailable)
    }
    fun unavailable() { reply.complete(ConnectionProbeReply.Unavailable) }
    suspend fun await(timeoutMillis: Long): ConnectionProbeReply =
        withTimeoutOrNull(timeoutMillis) { reply.await() } ?: ConnectionProbeReply.TimedOut
    fun close() { reply.cancel() }
}

/** Waits for a correlated daemon reply, with no UI/native singleton assumption or direct-network fallback. */
internal suspend fun requestConnectionProbe(
    context: Context, command: Int, requestId: String, payload: Serializable,
    timeoutMillis: Long = 12_000, cancelCommand: Int? = null,
): ConnectionProbeReply {
    val mailbox = ConnectionProbeMailbox(requestId)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(MessageHelper.EXTRA_REQUEST_ID) != requestId) return
            when (intent.getIntExtra("key", 0)) {
                AppConfig.MSG_MEASURE_DELAY_RESULT -> mailbox.receive(requestId,
                    intent.serializable<ConnectionTestResult>("content"), false)
                AppConfig.MSG_MEASURE_DELAY_CANCEL -> mailbox.receive(requestId, null, true)
            }
        }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags())
    try {
        MessageHelper.sendMsg2ServiceForResult(context, command, payload) { handled ->
            if (!handled) mailbox.unavailable()
        }
        return mailbox.await(timeoutMillis)
    } finally {
        mailbox.close()
        try { context.unregisterReceiver(receiver) }
        catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Connection probe receiver cleanup request=$requestId", e) }
        // The daemon cancels only this request ID, never a newer UI/batch probe or the VPN itself.
        cancelCommand?.let { MessageHelper.sendMsg2Service(context, it, requestId) }
    }
}
