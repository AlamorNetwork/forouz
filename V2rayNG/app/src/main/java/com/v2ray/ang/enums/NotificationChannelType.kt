package com.v2ray.ang.enums

import androidx.annotation.StringRes
import com.v2ray.ang.R

/**
 * Enum defining different notification channels.
 * Each channel has a unique channelId, notificationId, and display name.
 */
enum class NotificationChannelType(
    val channelId: String,
    @StringRes val channelNameRes: Int,
    val notificationId: Int
) {
    CDN_SCANNER("cdn_scanner_channel", R.string.cdn_title, 16),
    CONNECTION_HEALTH("connection_health_channel", R.string.health_title, 15),
    LABORATORY(
        channelId = "laboratory_channel",
        channelNameRes = R.string.lab_title,
        notificationId = 14
    ),
    SUBSCRIPTION_UPDATE(
        channelId = "subscription_update_channel",
        channelNameRes = R.string.notification_channel_subscription_updates,
        notificationId = 13
    ),
    CORE_TEST(
        channelId = "core_test_channel",
        channelNameRes = R.string.notification_channel_connection_checks,
        notificationId = 12
    )
}
