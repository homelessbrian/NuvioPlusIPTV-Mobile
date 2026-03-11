package com.nuvio.app.features.streams

data class StreamItem(
    val name: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val addonName: String,
    val addonId: String,
    val behaviorHints: StreamBehaviorHints = StreamBehaviorHints(),
) {
    val streamLabel: String
        get() = name ?: "Stream"

    val streamSubtitle: String?
        get() = description

    val hasPlayableSource: Boolean
        get() = url != null || infoHash != null || externalUrl != null
}

data class StreamBehaviorHints(
    val bingeGroup: String? = null,
    val notWebReady: Boolean = false,
    val videoSize: Long? = null,
    val filename: String? = null,
)

data class AddonStreamGroup(
    val addonName: String,
    val addonId: String,
    val streams: List<StreamItem>,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class StreamsUiState(
    val groups: List<AddonStreamGroup> = emptyList(),
    val activeAddonIds: Set<String> = emptySet(),
    val selectedFilter: String? = null,
    val isAnyLoading: Boolean = false,
) {
    val filteredGroups: List<AddonStreamGroup>
        get() = if (selectedFilter == null) groups
                else groups.filter { it.addonId == selectedFilter }

    val allStreams: List<StreamItem>
        get() = filteredGroups.flatMap { it.streams }

    val hasAnyStreams: Boolean
        get() = groups.any { it.streams.isNotEmpty() }
}
