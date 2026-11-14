package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.ReconnectAction
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Only an explicit, immutable notification PendingIntent can reach this non-exported receiver. */
class ReconnectReceiver : BroadcastReceiver() {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        // A daemon acknowledgement prevents an old Connect action from starting a second
        // engine after a newer connection. No UI-process native state is consulted.
        MessageHelper.sendMsg2ServiceForResult(context, AppConfig.MSG_REGISTER_CLIENT, "") { handled ->
            scope.launch {
                try {
                    val permissionRequired = !handled && SettingsManager.isVpnMode() &&
                        !SettingsManager.isRootMode() && VpnService.prepare(context) != null
                    when (ReconnectAction.decide(handled, permissionRequired)) {
                        ReconnectAction.ALREADY_HANDLED -> Unit
                        ReconnectAction.NEEDS_PERMISSION -> NotificationManager.showReconnect(context, openApp = true)
                        // Android 12+ permits FGS starts initiated by a notification action.
                        ReconnectAction.START -> LauncherManager.startServiceFromToggle(context)
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Notification reconnect dispatch failed", e)
                } finally {
                    pending.finish()
                    scope.cancel()
                }
            }
        }
    }
}
