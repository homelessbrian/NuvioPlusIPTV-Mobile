package com.nuvio.app.features.watchprogress

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max

internal const val ContinueWatchingLimit = 20
private const val MinResumePositionMs = 30_000L
private const val CompletionThresholdMs = 180_000L

@Serializable
private data class StoredWatchProgressPayload(
    val entries: List<WatchProgressEntry> = emptyList(),
)

internal object WatchProgressCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeEntries(payload: String): List<WatchProgressEntry> =
        runCatching {
            json.decodeFromString<StoredWatchProgressPayload>(payload).entries
        }.getOrDefault(emptyList())

    fun encodeEntries(entries: Collection<WatchProgressEntry>): String =
        json.encodeToString(
            StoredWatchProgressPayload(
                entries = entries.toList().sortedByDescending { it.lastUpdatedEpochMs },
            ),
        )
}

internal fun shouldStoreWatchProgress(
    positionMs: Long,
    durationMs: Long,
): Boolean {
    val thresholdMs = if (durationMs > 0L) {
        max(MinResumePositionMs, (durationMs * 0.02f).toLong())
    } else {
        MinResumePositionMs
    }
    return positionMs >= thresholdMs
}

internal fun isWatchProgressComplete(
    positionMs: Long,
    durationMs: Long,
    isEnded: Boolean,
): Boolean {
    if (isEnded) return true
    if (durationMs <= 0L) return false

    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    val watchedFraction = positionMs.toDouble() / durationMs.toDouble()
    return watchedFraction >= 0.92 || remainingMs <= CompletionThresholdMs
}

internal fun List<WatchProgressEntry>.resumeEntryForSeries(metaId: String): WatchProgressEntry? =
    filter { it.parentMetaId == metaId && !it.isCompleted }
        .maxByOrNull { it.lastUpdatedEpochMs }

internal fun List<WatchProgressEntry>.continueWatchingEntries(
    limit: Int = ContinueWatchingLimit,
): List<WatchProgressEntry> =
    filterNot { it.isCompleted }
        .sortedByDescending { it.lastUpdatedEpochMs }
        .take(limit)
