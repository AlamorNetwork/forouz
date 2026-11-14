package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.ConnectionSnapshot
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.receiver.ReconnectReceiver

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val NOTIFICATION_ICON_THRESHOLD = 3000
    private const val DISCONNECTED_NOTIFICATION_ID = 14

    private var mBuilder: NotificationCompat.Builder? = null
    private var speedVisible = true
    private var connectionReady = false
    private var mNotificationManager: NotificationManager? = null

    /**
     * Starts the speed notification.
     * @param currentConfig The current profile configuration.
     */
    @Synchronized fun startSpeedNotification() {
        speedVisible = true
    }

    @Synchronized fun markConnected() { connectionReady = true }

    /** Already sampled by the daemon; never read reset-on-query counters here. */
    @Synchronized fun updateTraffic(snapshot: ConnectionSnapshot) {
        if (!speedVisible || !snapshot.running) return
        val service = getService() ?: return
        if (!snapshot.trafficAvailable) {
            updateNotification(service.getString(R.string.lab_traffic_unavailable), 0, 0)
            return
        }
        if (!snapshot.sampleReady) return
        val text = service.getString(R.string.lab_notification_rates,
            snapshot.downloadRate.toSpeedString(), snapshot.uploadRate.toSpeedString())
        updateNotification(text, snapshot.proxyRate, snapshot.directRate)
    }

    /**
     * Shows the notification.
     * @param currentConfig The current profile configuration.
     */
    @Synchronized fun showNotification(currentConfig: ProfileItem?) {
        val service = getService() ?: return

        clearReconnect(service)

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(service, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(service, NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, flags)

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        stopV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        stopV2RayIntent.putExtra("key", AppConfig.MSG_STATE_STOP)
        val stopV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent, flags)

        val restartV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        restartV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        restartV2RayIntent.putExtra("key", AppConfig.MSG_STATE_RESTART)
        val restartV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_RESTART_V2RAY, restartV2RayIntent, flags)

        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            } else {
                // If earlier version channel ID is not used
                // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                ""
            }

        mBuilder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(currentConfig?.remarks ?: service.getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_delete_24dp,
                service.getString(R.string.notification_action_stop_v2ray),
                stopV2RayPendingIntent
            )
            .addAction(
                R.drawable.ic_restore_24dp,
                service.getString(R.string.title_service_restart),
                restartV2RayPendingIntent
            )

        //mBuilder?.setDefaults(NotificationCompat.FLAG_ONLY_ALERT_ONCE)

        service.startForeground(NOTIFICATION_ID, mBuilder?.build())
    }

    /**
     * Fulfills or refreshes the foreground-service contract before a start command can
     * return early. A duplicate startForegroundService call still requires the service
     * to enter foreground state promptly, even when the core is already running.
     */
    @Synchronized fun ensureForeground() {
        val service = getService() ?: return
        val notification = mBuilder?.build()
        if (notification == null) showNotification(null) else service.startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Cancels the notification.
     */
    @Synchronized fun cancelNotification(reconnect: Boolean = true): Boolean {
        com.v2ray.ang.core.health.HealthRecoveryFence.invalidate()
        val service = getService() ?: return false
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

        mBuilder = null
        val wasReady = connectionReady
        if (wasReady && reconnect) showReconnect(service)
        connectionReady = false
        mNotificationManager = null
        return wasReady
    }

    /**
     * Stops the speed notification.
     */
    @Synchronized fun stopSpeedNotification() {
        speedVisible = false
        updateNotification("", 0, 0)
    }

    fun clearReconnect(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(DISCONNECTED_NOTIFICATION_ID)
    }

    /** A dismissible ordinary notification: stopping never keeps a VPN/foreground service alive. */
    fun showReconnect(context: Context, openApp: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.activeNotifications.any { it.id == NOTIFICATION_ID }) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val content = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
        // If VPN consent was revoked, the user opens the permission-aware main screen directly.
        // Android 12+ disallows an activity trampoline through a notification receiver.
        val connect = if (openApp) content else PendingIntent.getBroadcast(context, 3,
            Intent(context, ReconnectReceiver::class.java).setPackage(AppConfig.ANG_PACKAGE), flags)
        val builder = NotificationCompat.Builder(context, AppConfig.RAY_NG_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(if (openApp) R.string.lab_notification_permission else R.string.lab_notification_disconnected))
            .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_power_24dp,
                context.getString(if (openApp) R.string.lab_open_app else R.string.acc_start), connect)
        manager.notify(DISCONNECTED_NOTIFICATION_ID, builder.build())
    }

    /**
     * Creates a notification channel for Android O and above.
     * @return The channel ID.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val service = getService() ?: return channelId
        NotificationHelper.ensureNotificationChannel(
            context = service,
            channelId = channelId,
            channelNameRes = R.string.notification_channel_service,
            // Foreground-service notifications must remain visible; LOW is silent but valid.
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            lightColor = Color.DKGRAY
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        return channelId
    }

    /**
     * Updates the notification with the given content text and traffic data.
     * @param contentText The content text.
     * @param proxyTraffic The proxy traffic.
     * @param directTraffic The direct traffic.
     */
    private fun updateNotification(contentText: String?, proxyTraffic: Long, directTraffic: Long) {
        if (mBuilder != null) {
            if (proxyTraffic < NOTIFICATION_ICON_THRESHOLD && directTraffic < NOTIFICATION_ICON_THRESHOLD) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_name)
            } else if (proxyTraffic > directTraffic) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_proxy)
            } else {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_direct)
            }
            mBuilder?.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            mBuilder?.setContentText(contentText)
            getNotificationManager()?.notify(NOTIFICATION_ID, mBuilder?.build())
        }
    }

    /**
     * Gets the notification manager.
     * @return The notification manager.
     */
    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    private fun getService(): Service? {
        return CoreServiceManager.externalVpnControl?.get()?.getService()
            ?: CoreServiceManager.serviceControl?.get()?.getService()
    }
}
