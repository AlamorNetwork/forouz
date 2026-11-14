package com.v2ray.ang.core

import com.v2ray.ang.core.amnezia.AmneziaCaptcha
import com.v2ray.ang.core.amnezia.AmneziaGatewayFailure
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AmneziaCaptchaTest {
    @Test fun `HTTP 402 challenge fields survive without treating it as config failure`() {
        val result = JsonParser.parseString("""{"http_status":402,"captcha_id":"synthetic-id","captcha_image":"data:image/png;base64,AQID"}""").asJsonObject
        val challenge = AmneziaCaptcha.fromResponse(402, result)!!
        assertEquals("synthetic-id", challenge.id)
        assertArrayEquals(byteArrayOf(1,2,3), challenge.image)
    }
    @Test fun `HTTP failures without challenge retain only safe status diagnostics`() {
        val result = JsonParser.parseString("""{"http_status":403,"message":"private-response-not-for-logs"}""").asJsonObject
        val error = assertThrows(IllegalStateException::class.java) { AmneziaCaptcha.fromResponse(403, result) }
        assertEquals("Amnezia gateway HTTP 403", error.message)
    }
    @Test fun `normal responses do not require captcha`() {
        assertNull(AmneziaCaptcha.parse(JsonParser.parseString("""{"http_status":200}""").asJsonObject))
    }
    @Test fun `corrupt and oversized challenge data are rejected`() {
        for (image in listOf("!", "", "A".repeat(700004))) {
            val result = com.google.gson.JsonObject().apply {
                addProperty("captcha_id", "test")
                addProperty("captcha_image", image)
            }
            assertThrows(Exception::class.java) { AmneziaCaptcha.parse(result) }
        }
    }
    @Test fun `numeric answers normalize across keyboard digit sets`() {
        assertEquals("1234", AmneziaCaptcha.normalizeAnswer(" ۱۲ ３4 "))
        assertEquals("AbCd", AmneziaCaptcha.normalizeAnswer(" AbCd "))
    }

    @Test fun `provider worker availability failure is recognized without exposing account`() {
        val result = JsonParser.parseString("""{"http_status":422,"message":"No protocol-compatible workers available for account [synthetic-private-account] after rule filtering"}""").asJsonObject
        val error = assertThrows(AmneziaGatewayFailure::class.java) { AmneziaCaptcha.fromResponse(422, result) }
        assertEquals(AmneziaGatewayFailure.Reason.NO_COMPATIBLE_SERVERS, error.reason)
        assertFalse(error.toString().contains("synthetic-private-account"))
        assertNull(error.cause)
    }

    @Test fun `configuration quota has a separate category`() {
        val result = JsonParser.parseString("""{"http_status":409,"message":"Conflict: The limit of allowable configurations has been exceeded."}""").asJsonObject
        val error = assertThrows(AmneziaGatewayFailure::class.java) { AmneziaCaptcha.fromResponse(409, result) }
        assertEquals(AmneziaGatewayFailure.Reason.CONFIGURATION_LIMIT, error.reason)
    }

    @Test fun `unknown validation message remains private and is not called missing servers`() {
        val result = JsonParser.parseString("""{"http_status":422,"message":"private-token-value"}""").asJsonObject
        val error = assertThrows(AmneziaGatewayFailure::class.java) { AmneziaCaptcha.fromResponse(422, result) }
        assertEquals(AmneziaGatewayFailure.Reason.UNKNOWN, error.reason)
        assertEquals("Amnezia gateway HTTP 422", error.message)
    }

    @Test fun `HTTP error does not become success when inner status is 200`() {
        val error = AmneziaGatewayFailure.fromResponse(409, JsonParser.parseString("""{"http_status":200}""").asJsonObject)!!
        assertEquals(409, error.status)
        assertNull(AmneziaGatewayFailure.fromResponse(200, JsonParser.parseString("{}").asJsonObject))
    }
}
