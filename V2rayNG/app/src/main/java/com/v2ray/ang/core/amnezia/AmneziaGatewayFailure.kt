package com.v2ray.ang.core.amnezia

import com.google.gson.JsonObject

/** Only fixed categories leave the response parser; provider messages can contain account IDs. */
class AmneziaGatewayFailure(val status: Int, val reason: Reason) :
    IllegalStateException("Amnezia gateway HTTP $status" +
        if (reason == Reason.UNKNOWN) "" else " (${reason.name})") {
    enum class Reason { NO_COMPATIBLE_SERVERS, CONFIGURATION_LIMIT, UNKNOWN }

    companion object {
        fun fromResponse(httpStatus: Int, result: JsonObject): AmneziaGatewayFailure? {
            val status = result["http_status"]?.takeIf { it.isJsonPrimitive }?.asString
                ?.toIntOrNull()?.takeIf { it in 400..599 } ?: httpStatus
            if (httpStatus in 200..299 && status in 200..299) return null
            val message = result["message"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val reason = when {
                status == 422 && message.startsWith("No protocol-compatible workers available for account ") &&
                    message.endsWith(" after rule filtering") -> Reason.NO_COMPATIBLE_SERVERS
                status == 409 && message == "Conflict: The limit of allowable configurations has been exceeded." ->
                    Reason.CONFIGURATION_LIMIT
                else -> Reason.UNKNOWN
            }
            return AmneziaGatewayFailure(status, reason)
        }
    }
}
