package com.nuvio.app.features.watched

import com.nuvio.app.features.home.MetaPreview
import kotlinx.serialization.Serializable

@Serializable
data class WatchedItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val releaseInfo: String? = null,
    val markedAtEpochMs: Long,
)

data class WatchedUiState(
    val items: List<WatchedItem> = emptyList(),
    val watchedKeys: Set<String> = emptySet(),
    val isLoaded: Boolean = false,
)

fun MetaPreview.toWatchedItem(markedAtEpochMs: Long): WatchedItem =
    WatchedItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        releaseInfo = releaseInfo,
        markedAtEpochMs = markedAtEpochMs,
    )

fun watchedItemKey(type: String, id: String): String = "${type.trim()}:${id.trim()}"

