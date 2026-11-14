package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import java.util.zip.Inflater
import java.io.ByteArrayOutputStream

/** Qt qCompress envelope. The prefix is a size hint, not trusted allocation authority. */
object AmneziaVpnLink {
    private const val LIMIT = 1048576
    fun decode(link: String): JsonObject {
        require(link.startsWith("vpn://") && link.length <= LIMIT)
        val bytes = Base64.getUrlDecoder().decode(link.removePrefix("vpn://").trim())
        val data = if (bytes.firstOrNull()?.toInt() == 123) bytes else {
            require(bytes.size > 6)
            val inflater = Inflater()
            try {
                inflater.setInput(bytes, 4, bytes.size - 4)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    require(count > 0 && out.size() + count <= LIMIT)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } finally { inflater.end() }
        }
        require(data.size <= LIMIT)
        val parsed = JsonParser.parseString(data.toString(Charsets.UTF_8))
        require(parsed.isJsonObject)
        return parsed.asJsonObject
    }
    fun isManaged(config: JsonObject): Boolean = config.has("auth_data") || config.has("api_key")
    fun embeddedConfig(config: JsonObject): String {
        if (config.has("config") && config["config"].isJsonPrimitive) return config["config"].asString
        val containers = config.getAsJsonArray("containers") ?: error("Missing containers")
        for (entry in containers) {
            val awg = entry.asJsonObject.getAsJsonObject("awg") ?: continue
            val last = awg["last_config"] ?: continue
            val parsed = if (last.isJsonPrimitive) JsonParser.parseString(last.asString).asJsonObject else last.asJsonObject
            val original = parsed["config"]?.asString ?: continue
            val fields = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4",
                "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5")
            val overrides = fields.mapNotNull { key ->
                val value = parsed[key]?.takeUnless { it.isJsonNull } ?: awg[key]?.takeUnless { it.isJsonNull }
                value?.asString?.takeIf { it.isNotBlank() }?.let {
                    require(!it.contains('\n') && !it.contains('\r'))
                    key to it
                }
            }.toMap()
            var inInterface = false
            var foundInterface = false
            val text = buildList {
                for (line in original.lineSequence()) {
                    val content = line.substringBefore('#').trim()
                    if (content.startsWith("[")) {
                        inInterface = content.equals("[Interface]", ignoreCase = true)
                        add(line)
                        if (inInterface) {
                            require(!foundInterface)
                            foundInterface = true
                            overrides.forEach { (key, value) -> add("$key = $value") }
                        }
                    } else if (!inInterface || overrides.keys.none {
                        it.equals(content.substringBefore('=').trim(), ignoreCase = true)
                    }) add(line)
                }
            }.joinToString("\n")
            require(foundInterface)
            require(AmneziaWgFmt.isAmneziaConfig(text))
            return text
        }
        error("No complete AWG configuration")
    }
}
