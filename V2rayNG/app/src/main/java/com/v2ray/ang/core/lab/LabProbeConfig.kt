package com.v2ray.ang.core.lab

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Never edits the original profile. The isolated controller exposes only a loopback SOCKS inbound. */
internal object LabProbeConfig {
    fun build(raw: String, port: Int): String {
        require(port in 1024..65535)
        val json = JsonParser.parseString(raw).asJsonObject
        val outbounds = json.getAsJsonArray("outbounds")
        require(outbounds != null && !outbounds.isEmpty)
        val main = outbounds[0].asJsonObject
        require(main.get("protocol")?.asString !in setOf("freedom", "blackhole", "dns"))
        val tag = main.get("tag")?.asString?.takeIf { it.isNotBlank() } ?: "lab-proxy".also { main.addProperty("tag", it) }
        json.add("log", JsonObject().apply { addProperty("loglevel", "none") })
        json.add("stats", JsonObject())
        json.remove("api"); json.remove("observatory"); json.remove("burstObservatory"); json.remove("reverse")
        json.add("inbounds", JsonArray().apply { add(JsonObject().apply {
            addProperty("tag", "lab-in"); addProperty("listen", "127.0.0.1"); addProperty("port", port)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply { addProperty("auth", "noauth"); addProperty("udp", false) })
        }) })
        json.add("routing", JsonObject().apply {
            add("rules", JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "field"); addProperty("outboundTag", tag)
                add("inboundTag", JsonArray().apply { add("lab-in") })
            }) })
        })
        return json.toString()
    }
}
