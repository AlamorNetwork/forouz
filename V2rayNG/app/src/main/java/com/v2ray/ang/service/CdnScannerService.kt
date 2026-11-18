package com.v2ray.ang.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.cdn.*
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class CdnCommand { START, KEEP, CANCEL, STOP }
internal fun cdnCommand(action: String?, request: Boolean, busy: Boolean): CdnCommand = when {
    action == "cdn.cancel" -> CdnCommand.CANCEL
    busy -> CdnCommand.KEEP
    action == "cdn.start" && request -> CdnCommand.START
    else -> CdnCommand.STOP
}

class CdnScannerService : Service() {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val commands = Mutex()
    private var run: Job? = null
    @Volatile private var lastStartId = 0
    override fun attachBaseContext(newBase: Context?) { super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext)) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val cancel = PendingIntent.getService(this, 16, Intent(this, CdnScannerService::class.java).setAction("cdn.cancel"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        NotificationHelper.startForeground(this, NotificationChannelType.CDN_SCANNER, getString(R.string.cdn_title), getString(R.string.lab_running),
            NotificationCompat.Action.Builder(R.drawable.ic_stop_24dp, getString(R.string.action_cancel), cancel).build())
        scope.launch {
            commands.withLock {
                val request = intent?.serializable<CdnRequest>("request")
                when (cdnCommand(intent?.action, request != null, labHasWork(run))) {
                    CdnCommand.START -> run = scope.launch {
                        try {
                            CdnScanRunner(this@CdnScannerService).run(requireNotNull(request)) { completed, total ->
                                NotificationHelper.updateNotification(NotificationChannelType.CDN_SCANNER, this@CdnScannerService,
                                    getString(R.string.cdn_title), getString(R.string.lab_progress, completed, total))
                            }
                        } finally { stopSelfResult(lastStartId) }
                    }
                    CdnCommand.CANCEL -> {
                        run?.cancelAndJoin(); run = null
                        try { MmkvManager.readCdnRun()?.takeIf { it.status == CdnStatus.RUNNING }?.let {
                            MmkvManager.saveCdnRun(it.copy(status = CdnStatus.INTERRUPTED, updatedAt = maxOf(it.createdAt, System.currentTimeMillis())))
                        } } catch (e: Exception) { LogUtil.e(AppConfig.TAG, "CDN mode=isolated phase=cancel", IllegalStateException(e.javaClass.simpleName)) }
                        stopSelfResult(startId)
                    }
                    CdnCommand.STOP -> stopSelfResult(startId)
                    CdnCommand.KEEP -> Unit
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { owner.cancel(); stopSelf() }
    override fun onDestroy() { owner.cancel(); NotificationHelper.stopForeground(this); super.onDestroy() }
}
