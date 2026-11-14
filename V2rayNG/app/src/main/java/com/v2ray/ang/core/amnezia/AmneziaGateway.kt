package com.v2ray.ang.core.amnezia

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.handler.MmkvManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.amnezia.awg.crypto.KeyPair
import java.util.Base64
import java.util.UUID
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit

/** Implements the official gateway envelope; no token, payload or response is logged. */
object AmneziaGateway {
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).build()
    private const val PUBLIC_KEY = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAj5mxl/4DL3Sk89ntxs5GX3JawGQWIoq6rvNkOzNGuNgedNS2+pi6hZl3Izl1Io9om4KiUlMT6mgLO1hTr9q+s7CYhlvroFA7ErucF+9L+7FCt0Igi0kIK/R2/vxd/2HaUrorn/aSvvutkYwbfxqWSwtzE+RuBeDWGvEt937OW0oqYONPYv9E4T56Dz/EZ6v2t8ejAnKLbGD/GocMmipK7etFSiSMAB2RmaztqTq4NleBepfO80XpYlW9pCSXuHcE8wxHczkzxsbyMAMsG/K3vUQY6qPtohqqzSSBwa/8u2ptNHBeor7l7DdYXeR/Nqcc4z92VUkZ5lOVR4evkS5V/wQqp5tnOJEj3NjUhEhXFoNEapbZd1bh6iQoUk7jC1TdvKJ/nPKGZAsHRpr0rNKzfx/N/Oo6lr2yh/+ps6VxTkbPmB6E85WOO3UvjImZUY0XQdBjWle/4iJLdEC77Nr0jXhdgeypucy6jkB6iBHMeVMlrNMEV7UxoBR/cCNx55zu/8sml5ByiDvCDT7sRomNNgVt5S/FaVjYuzFUifJ12ToChXFgESKFmuso7WluEaWvMIGREdrMrKQKHfYLOzWF2B5ZJDqw4o03fU4J/6rw61M1b+rjVpXMjPnzc2A+RgcjTvXv955gfZkwe4lt5wk/3j8zMVo3+zLrMTAaEeIUM0UCAwEAAQ=="
    @Synchronized private fun installationId(): String {
        val key = "amnezia_lab_installation_uuid"
        return MmkvManager.decodeSettingsString(key) ?: UUID.randomUUID().toString().also {
            check(MmkvManager.encodeSettings(key, it))
        }
    }
    class CaptchaRequired(val id: String, val image: ByteArray, val resume: (String) -> String) :
        Exception("Amnezia captcha required")

    fun resolve(config: JsonObject): String = requestConfig(config, KeyPair(), null, null)

    private fun requestConfig(config: JsonObject, keys: KeyPair, captchaId: String?, answer: String?): String {
        val api = config.getAsJsonObject("api_config") ?: error("Unsupported subscription")
        require(api["service_type"]?.asString == "amnezia-free")
        require(api["service_protocol"]?.asString == "awg")
        val payload = AmneziaGatewayProtocol.request(api, config.getAsJsonObject("auth_data"),
            keys.publicKey.toBase64(), installationId()).apply {
            if (captchaId != null && answer != null) {
                addProperty("captcha_id", captchaId)
                addProperty("captcha_solution", AmneziaCaptcha.normalizeAnswer(answer))
            }
        }
        val random = SecureRandom()
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(32).also(random::nextBytes)
        val salt = ByteArray(8).also(random::nextBytes)
        val encoder = Base64.getEncoder()
        val meta = JsonObject().apply {
            addProperty("aes_key", encoder.encodeToString(key))
            addProperty("aes_iv", encoder.encodeToString(iv))
            addProperty("aes_salt", encoder.encodeToString(salt))
        }
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))))
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secret = SecretKeySpec(key, "AES")
        val vector = IvParameterSpec(iv.copyOf(16))
        aes.init(Cipher.ENCRYPT_MODE, secret, vector)
        val body = JsonObject().apply {
            addProperty("key_payload", encoder.encodeToString(rsa.doFinal(meta.toString().toByteArray())))
            addProperty("api_payload", encoder.encodeToString(aes.doFinal(payload.toString().toByteArray())))
        }
        val request = Request.Builder().url(AmneziaGatewayProtocol.CONFIG_ENDPOINT)
            .header("X-Client-Request-ID", UUID.randomUUID().toString())
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val stream = response.body?.byteStream() ?: error("Empty gateway response")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                require(output.size() + n <= 1048576)
                output.write(buffer, 0, n)
            }
            aes.init(Cipher.DECRYPT_MODE, secret, vector)
            val data = try { aes.doFinal(output.toByteArray()).toString(Charsets.UTF_8) }
                catch (e: java.security.GeneralSecurityException) {
                    throw IllegalStateException("Amnezia gateway HTTP ${response.code}: encrypted response unavailable")
                }
            val parsed = JsonParser.parseString(data)
            check(parsed.isJsonObject) { "Amnezia gateway response format" }
            val result = parsed.asJsonObject
            AmneziaCaptcha.fromResponse(response.code, result)?.let { challenge ->
                throw CaptchaRequired(challenge.id, challenge.image) { solution ->
                    requestConfig(config, keys, challenge.id, solution)
                }
            }
            return AmneziaGatewayProtocol.nativeConfig(result, keys.privateKey.toBase64())
        }
    }
}
