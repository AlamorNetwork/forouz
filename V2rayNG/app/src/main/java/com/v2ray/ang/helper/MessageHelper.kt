package com.v2ray.ang.helper

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.SubscriptionUpdateMessage
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.service.CoreTestService
import com.v2ray.ang.service.SubscriptionUpdateService
import com.v2ray.ang.util.LogUtil
import java.io.Serializable
import com.v2ray.ang.dto.ConnectionSnapshot
import com.v2ray.ang.extension.serializable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

object MessageHelper {
    const val EXTRA_REQUEST_ID = "requestId"

    /** A one-shot daemon query; receiving this never starts/restarts a VPN or registers a lasting client. */
    internal suspend fun requestConnectionSnapshot(ctx: Context): ConnectionSnapshot? {
        val reply = CompletableDeferred<ConnectionSnapshot?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getIntExtra("key", 0) == AppConfig.MSG_CONNECTION_SNAPSHOT) {
                    try { reply.complete(intent.serializable<ConnectionSnapshot>("content")) }
                    catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Snapshot mode=UI phase=decode", IllegalStateException(e.javaClass.simpleName)); reply.complete(null) }
                }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, android.content.IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val acknowledged = CompletableDeferred<Boolean>()
            sendMsg2ServiceForResult(ctx, AppConfig.MSG_REGISTER_CLIENT, "") { handled -> acknowledged.complete(handled) }
            return withTimeoutOrNull(2_500) { if (acknowledged.await()) reply.await() else null }
        } finally {
            reply.cancel()
            try { ctx.unregisterReceiver(receiver) }
            catch (e: Exception) { LogUtil.e(AppConfig.TAG, "Snapshot mode=UI phase=unregister", IllegalStateException(e.javaClass.simpleName)) }
        }
    }

    internal fun sendCdnCommand(ctx: Context, request: com.v2ray.ang.core.cdn.CdnRequest?) {
        val intent = Intent(ctx, com.v2ray.ang.service.CdnScannerService::class.java).apply {
            action = if (request == null) "cdn.cancel" else "cdn.start"
            request?.let { putExtra("request", it) }
        }
        if (request != null) ContextCompat.startForegroundService(ctx, intent) else ctx.startService(intent)
    }

    internal fun sendHealthCommand(ctx: Context, request: com.v2ray.ang.core.health.HealthRequest?) {
        val intent = Intent(ctx, com.v2ray.ang.service.ConnectionHealthService::class.java).apply {
            action = if (request == null) "health.stop" else "health.start"
            request?.let { putExtra("request", it) }
        }
        if (request != null) ContextCompat.startForegroundService(ctx, intent) else ctx.startService(intent)
    }


    internal fun sendLaboratoryCommand(ctx: Context, request: com.v2ray.ang.core.lab.LabStartRequest?) {
        val intent = Intent(ctx, com.v2ray.ang.service.LaboratoryService::class.java).apply {
            action = if (request == null) "lab.cancel" else "lab.start"
            request?.let { putExtra("request", it) }
        }
        if (request != null) ContextCompat.startForegroundService(ctx, intent) else ctx.startService(intent)
    }

    /**
     * Sends a message to the service.
     *
     * @param ctx The context.
     * @param what The message identifier.
     * @param content The message content.
     */
    fun sendMsg2Service(ctx: Context, what: Int, content: Serializable) {
        sendMsg(ctx, AppConfig.BROADCAST_ACTION_SERVICE, what, content)
    }

    /**
     * Sends an ordered service message and reports whether a daemon receiver handled it.
     * With no running daemon, the initial canceled result reaches [onResult] unchanged.
     */
    internal fun sendMsg2ServiceForResult(
        ctx: Context,
        what: Int,
        content: Serializable,
        onResult: (handled: Boolean) -> Unit,
    ) {
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                onResult(resultCode == Activity.RESULT_OK)
            }
        }
        try {
            ctx.sendOrderedBroadcast(
                messageIntent(AppConfig.BROADCAST_ACTION_SERVICE, what, content),
                null,
                resultReceiver,
                null,
                Activity.RESULT_CANCELED,
                null,
                null,
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to send ordered message to service", e)
            onResult(false)
        }
    }

    /**
     * Sends a message to the UI.
     *
     * @param ctx The context.
     * @param what The message identifier.
     * @param content The message content.
     */
    fun sendMsg2UI(ctx: Context, what: Int, content: Serializable, requestId: String? = null) {
        sendMsg(ctx, AppConfig.BROADCAST_ACTION_ACTIVITY, what, content, requestId)
    }

    /**
     * Sends a message to the test service.
     *
     * @param ctx The context.
     * @param message The test service message containing key, subscriptionId, and serverGuids.
     */
    fun sendMsg2TestService(ctx: Context, message: TestServiceMessage, requestId: String? = null) {
        try {
            val intent = Intent()
            intent.component = ComponentName(ctx, CoreTestService::class.java)
            intent.putExtra("content", message)
            requestId?.let { intent.putExtra(EXTRA_REQUEST_ID, it) }
            when (message.key) {
                AppConfig.MSG_MEASURE_CONFIG_START -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(ctx, intent)
                    } else {
                        ctx.startService(intent)
                    }
                }

                AppConfig.MSG_MEASURE_CONFIG_CANCEL -> {
                    // Do not wake up service just to cancel; stop only if it is already running.
                    ctx.stopService(intent)
                }

                else -> {
                    ctx.startService(intent)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to send message to test service", e)
            if (message.key == AppConfig.MSG_MEASURE_CONFIG_START) {
                sendMsg2UI(ctx, AppConfig.MSG_MEASURE_CONFIG_CANCEL, "", requestId)
            }
        }
    }

    /**
     * Sends a message to the subscription service.
     *
     * @param ctx The context.
     * @param message The subscription service message containing key and subId.
     */
    fun sendMsg2SubscriptionService(ctx: Context, message: SubscriptionUpdateMessage) {
        try {
            val intent = Intent()
            intent.component = ComponentName(ctx, SubscriptionUpdateService::class.java)
            intent.putExtra("content", message)
            when (message.key) {
                AppConfig.MSG_SUB_UPDATE_START -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(ctx, intent)
                    } else {
                        ctx.startService(intent)
                    }
                }

                AppConfig.MSG_SUB_UPDATE_CANCEL -> {
                    ctx.stopService(intent)
                }

                else -> {
                    ctx.startService(intent)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to send message to subscription service", e)
        }
    }

    /**
     * Sends a message with the specified action.
     *
     * @param ctx The context.
     * @param action The action string.
     * @param what The message identifier.
     * @param content The message content.
     */
    private fun sendMsg(ctx: Context, action: String, what: Int, content: Serializable, requestId: String? = null) {
        try {
            ctx.sendBroadcast(messageIntent(action, what, content).apply {
                requestId?.let { putExtra(EXTRA_REQUEST_ID, it) }
            })
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to send message with action: $action", e)
        }
    }

    private fun messageIntent(action: String, what: Int, content: Serializable): Intent =
        Intent(action).apply {
            `package` = AppConfig.ANG_PACKAGE
            putExtra("key", what)
            putExtra("content", content)
        }
}
