package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AwgConnectionTestRequest
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.helper.ConnectionProbeReply
import com.v2ray.ang.helper.requestConnectionProbe
import java.util.UUID

internal fun awgBatchResult(guid: String, reply: ConnectionProbeReply): RealPingEvent.Result = when (reply) {
    is ConnectionProbeReply.Result -> RealPingEvent.Result(guid, reply.value.delayMillis)
    // Unsupported/inactive/cancelled IPC is not evidence of a dead configuration.
    else -> RealPingEvent.Result(guid, 0, measured = false)
}

internal suspend fun measureActiveAwg(context: Context, guid: String): RealPingEvent.Result {
    val id = UUID.randomUUID().toString()
    return awgBatchResult(guid, requestConnectionProbe(context, AppConfig.MSG_MEASURE_AWG_DELAY,
        id, AwgConnectionTestRequest(id, guid), cancelCommand = AppConfig.MSG_CANCEL_AWG_DELAY))
}
