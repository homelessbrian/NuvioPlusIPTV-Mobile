package com.nuvio.app.features.livetv.stalker

import com.nuvio.app.features.livetv.data.LiveTvDirs
import com.nuvio.app.features.livetv.data.LiveTvFiles
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.liveTvJson
import com.nuvio.app.features.livetv.data.optBoolean
import com.nuvio.app.features.livetv.data.optInt
import com.nuvio.app.features.livetv.data.optLong
import com.nuvio.app.features.livetv.data.optString
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.stalker.StalkerChannel
import com.nuvio.stalker.StalkerGroup
import com.nuvio.stalker.StalkerProgramme
import com.nuvio.stalker.StalkerStreamRef
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** App-private, line-oriented catalog snapshots. Only provider commands, never sessions or resolved links. */
internal object StalkerCatalogCache {
    fun channelKey(sourceId: String, providerId: String): String = "$sourceId|stalker|${providerId.encodeURLParameter()}"

    fun channelLines(groups: List<StalkerGroup>, channels: List<StalkerChannel>): Sequence<String> = sequence {
        groups.forEach { group ->
            yield(buildJsonObject { put("row", "group"); put("id", group.id); put("name", group.name) }.toString() + "\n")
        }
        channels.forEach { channel ->
            yield(buildJsonObject {
                put("row", "channel"); put("id", channel.id); put("name", channel.name)
                put("stream", channel.stream.toJson()); put("number", channel.number.orEmpty())
                put("logo", channel.logo.orEmpty()); put("group", channel.groupId.orEmpty())
                put("epg", channel.epgId.orEmpty()); put("archive", channel.archiveEnabled); put("days", channel.archiveDays)
            }.toString() + "\n")
        }
    }

    fun encodeChannels(groups: List<StalkerGroup>, channels: List<StalkerChannel>): String = buildString {
        channelLines(groups, channels).forEach { append(it) }
    }

    fun decodeChannels(lines: Sequence<String>): Pair<List<StalkerGroup>, List<StalkerChannel>> {
        val groups = ArrayList<StalkerGroup>()
        val channels = ArrayList<StalkerChannel>()
        lines.filter { it.isNotBlank() }.forEach { line ->
            val row = liveTvJson.parseToJsonElement(line) as JsonObject
            when (row.optString("row")) {
                "group" -> groups += StalkerGroup(row.optString("id"), row.optString("name"))
                "channel" -> {
                    val stream = (row["stream"] as? JsonObject)?.let(StalkerStreamRef::fromJson) ?: return@forEach
                    channels += StalkerChannel(row.optString("id"), row.optString("name"), stream,
                        number = row.optString("number").ifBlank { null }, logo = row.optString("logo").ifBlank { null },
                        groupId = row.optString("group").ifBlank { null }, epgId = row.optString("epg").ifBlank { null },
                        archiveEnabled = row.optBoolean("archive"), archiveDays = row.optInt("days"))
                }
            }
        }
        return groups to channels
    }

    fun channels(sourceId: String): Pair<List<StalkerGroup>, List<StalkerChannel>> =
        LiveTvFiles.readChunks(LiveTvDirs.PLAYLISTS, "$sourceId.m3u") { decodeChannels(LiveTvRepository.linesOf(it)) }
            ?: (emptyList<StalkerGroup>() to emptyList())

    fun guideLine(p: StalkerProgramme, sourceIdentity: String? = null): String = buildJsonObject {
        put("channel", p.channelId); put("id", p.id); put("title", p.title)
        put("start", p.startMillis); put("stop", p.stopMillis)
        put("description", p.description.orEmpty()); put("category", p.category.orEmpty()); put("archive", p.archiveAvailable)
        put("archiveId", p.archiveProgramId.orEmpty())
        if (p.archiveProgramId != null && sourceIdentity != null) put("sourceIdentity", sourceIdentity)
    }.toString() + "\n"

    fun encodeGuide(programmes: List<StalkerProgramme>, sourceIdentity: String? = null): String = buildString {
        programmes.forEach { append(guideLine(it, sourceIdentity)) }
    }

    fun decodeGuide(lines: Sequence<String>, fromMs: Long, toMs: Long): Map<String, List<EpgProgram>> {
        val programs = HashMap<String, MutableList<EpgProgram>>()
        val identities = HashMap<String, String>()
        lines.filter { it.isNotBlank() }.forEach { line ->
            val row = liveTvJson.parseToJsonElement(line) as JsonObject
            val start = row.optLong("start")
            val stop = row.optLong("stop")
            if (stop <= fromMs || start >= toMs || stop <= start) return@forEach
            programs.getOrPut(row.optString("channel")) { ArrayList() } += EpgProgram(
                start, stop, row.optString("title"), description = row.optString("description").ifBlank { null },
                category = row.optString("category").ifBlank { null },
                stalkerProgramId = row.optString("archiveId").ifBlank { null },
                stalkerArchiveAvailable = row.optBoolean("archive") && row.optString("archiveId").isNotBlank() && row.optString("sourceIdentity").isNotBlank(),
                stalkerSourceIdentity = row.optString("sourceIdentity").ifBlank { null }?.let { identities.getOrPut(it) { it } })
        }
        return programs
    }

    fun guide(file: String, fromMs: Long, toMs: Long): Map<String, List<EpgProgram>> =
        LiveTvFiles.readChunks(LiveTvDirs.EPG, file) { decodeGuide(LiveTvRepository.linesOf(it), fromMs, toMs) }.orEmpty()
}
