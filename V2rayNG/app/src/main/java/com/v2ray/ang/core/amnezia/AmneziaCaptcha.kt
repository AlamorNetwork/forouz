package com.v2ray.ang.core.amnezia

import com.google.gson.JsonObject
import java.util.Base64

/** Only bounded challenge data crosses to the UI. Account/config payloads stay in the retry closure. */
class AmneziaCaptcha(val id: String, val image: ByteArray) {
    companion object {
        fun fromResponse(httpStatus: Int, result: JsonObject): AmneziaCaptcha? {
            parse(result)?.let { return it }
            AmneziaGatewayFailure.fromResponse(httpStatus, result)?.let { throw it }
            return null
        }
        fun normalizeAnswer(answer: String): String {
            val digits = answer.mapNotNull { it.digitToIntOrNull()?.digitToChar() }.joinToString("")
            return digits.ifEmpty { answer.trim() }
        }
        fun parse(result: JsonObject): AmneziaCaptcha? {
            if (!result.has("captcha_id") || !result.has("captcha_image")) return null
            val id = result["captcha_id"].asString
            val encoded = result["captcha_image"].asString.substringAfter(',')
            require(id.isNotBlank() && id.length <= 256 && encoded.length <= 700000)
            val image = Base64.getDecoder().decode(encoded)
            require(image.isNotEmpty() && image.size <= 524288)
            return AmneziaCaptcha(id, image)
        }
    }
}
