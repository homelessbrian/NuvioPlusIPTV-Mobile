package com.nuvio.app.features.details

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object MetaDetailsParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): MetaDetails {
        val root = json.parseToJsonElement(payload).jsonObject
        val meta = root["meta"]?.jsonObject ?: error("Missing 'meta' in response")

        return MetaDetails(
            id = meta.requiredString("id"),
            type = meta.requiredString("type"),
            name = meta.requiredString("name"),
            poster = meta.string("poster"),
            background = meta.string("background"),
            logo = meta.string("logo"),
            description = meta.string("description"),
            releaseInfo = meta.string("releaseInfo"),
            imdbRating = meta.string("imdbRating"),
            runtime = meta.string("runtime"),
            genres = meta.stringList("genres"),
            director = meta.stringList("director"),
            cast = meta.stringList("cast"),
            country = meta.string("country"),
            awards = meta.string("awards"),
            language = meta.string("language"),
            website = meta.string("website"),
            links = meta.links(),
            videos = meta.videos(),
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull ?: error("Missing required field '$name'")

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.array(name: String): JsonArray =
        this[name] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.stringList(name: String): List<String> =
        array(name).mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }

    private fun JsonObject.links(): List<MetaLink> =
        array("links").mapNotNull { element ->
            val link = element as? JsonObject ?: return@mapNotNull null
            val linkName = link.string("name") ?: return@mapNotNull null
            val category = link.string("category") ?: return@mapNotNull null
            val url = link.string("url") ?: return@mapNotNull null
            MetaLink(name = linkName, category = category, url = url)
        }

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.videos(): List<MetaVideo> =
        array("videos").mapNotNull { element ->
            val video = element as? JsonObject ?: return@mapNotNull null
            val id = video.string("id") ?: return@mapNotNull null
            val title = video.string("title") ?: video.string("name") ?: return@mapNotNull null
            MetaVideo(
                id = id,
                title = title,
                released = video.string("released"),
                thumbnail = video.string("thumbnail"),
                season = video.int("season"),
                episode = video.int("episode"),
                overview = video.string("overview") ?: video.string("description"),
            )
        }
}
