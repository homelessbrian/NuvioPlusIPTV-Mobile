package com.nuvio.app.features.streams

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object StreamParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        payload: String,
        addonName: String,
        addonId: String,
    ): List<StreamItem> {
        val root = json.parseToJsonElement(payload).jsonObject
        val streamsArray = root["streams"] as? JsonArray ?: return emptyList()
        return streamsArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.string("url")
            val infoHash = obj.string("infoHash")
            val externalUrl = obj.string("externalUrl")

            // Must have at least one playable source
            if (url == null && infoHash == null && externalUrl == null) return@mapNotNull null

            val hintsObj = obj["behaviorHints"] as? JsonObject
            StreamItem(
                name = obj.string("name"),
                description = obj.string("description") ?: obj.string("title"),
                url = url,
                infoHash = infoHash,
                fileIdx = obj.int("fileIdx"),
                externalUrl = externalUrl,
                addonName = addonName,
                addonId = addonId,
                behaviorHints = StreamBehaviorHints(
                    bingeGroup = hintsObj?.string("bingeGroup"),
                    notWebReady = hintsObj?.boolean("notWebReady") ?: false,
                    videoSize = hintsObj?.long("videoSize"),
                    filename = hintsObj?.string("filename"),
                ),
            )
        }
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(name: String): Long? =
        this[name]?.jsonPrimitive?.longOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        this[name]?.jsonPrimitive?.booleanOrNull
}
