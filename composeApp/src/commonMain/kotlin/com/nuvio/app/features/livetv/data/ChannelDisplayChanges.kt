package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Retain only the overrides used by the display transform, not old playback or VOD state. */
internal data class ChannelDisplayOverrides(
    val channelNames: Map<String, String>,
    val channelNumbers: Map<String, Int>,
    val groupNames: Map<String, String>,
    val channelLogos: Map<String, String>,
)

/** Only saved overrides used by the display transform should rebuild the channel list. */
internal fun displayChannelChanges(
    channels: Flow<List<LiveChannel>>,
    user: Flow<LiveUserState>,
    settings: Flow<LiveTvSettings>,
    logos: Flow<Map<String, String>>,
    preferEpg: Flow<Set<String>>,
    transform: suspend (List<LiveChannel>, ChannelDisplayOverrides, String, Map<String, String>, Set<String>) -> List<LiveChannel>,
): Flow<List<LiveChannel>> = combine(
    channels,
    // Keep in step with named() and withPickedLogos() in LiveTvRepository.
    user.map { ChannelDisplayOverrides(it.channelNames, it.channelNumbers, it.groupNames, it.channelLogos) }
        .distinctUntilChanged { a, b ->
            // Stable-ID fallbacks use the first stale key, so entry order also matters.
            sameOrderedEntries(a.channelNames, b.channelNames) &&
                sameOrderedEntries(a.channelNumbers, b.channelNumbers) &&
                a.groupNames == b.groupNames && sameOrderedEntries(a.channelLogos, b.channelLogos)
        },
    settings.map { it.nameRemovals }.distinctUntilChanged(),
    logos,
    preferEpg,
    transform,
)

private fun <T> sameOrderedEntries(a: Map<String, T>, b: Map<String, T>): Boolean {
    if (a === b) return true
    if (a.size != b.size) return false
    val left = a.entries.iterator()
    val right = b.entries.iterator()
    while (left.hasNext()) if (left.next() != right.next()) return false
    return true
}
