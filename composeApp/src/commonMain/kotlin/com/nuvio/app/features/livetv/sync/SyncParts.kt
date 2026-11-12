package com.nuvio.app.features.livetv.sync

import com.nuvio.app.features.livetv.data.LiveTvKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/** The parts of the Live TV setup that Setup Sync can leave out on one device. */
internal enum class SyncPart(val label: String, val description: String, val defaultOn: Boolean) {
    LOGINS("Playlists and logins", "Your IPTV links, usernames and passwords. Stored encrypted.", true),
    VPN("VPN profiles and protection", "VPN configurations, selected profile and automatic protection scope.", true),
    FAVORITES("Favorites and groups", "Favorite channels, hidden channels and group order.", true),
    SPORTS("Sports", "Followed teams and leagues, and Choose leagues.", true),
    CALENDAR("Calendar", "Followed and hidden shows.", true),
    BEHAVIOR("Behavior and options", "How often lists and guides refresh, recording and catch-up options, sorting, search sources and Calendar choices. Not the look, so it is shared with your phone too.", true),
    LOOK("Look and layout", "Themes, menu, card styles and other visual options.", false),
}

/**
 * Which parts of the setup this device syncs. The choice is kept on this device only (it is not part
 * of the synced setup). A part that is off is left out when this device saves, and keeps this
 * device's own values when a saved setup is restored; the saved copy keeps what other devices put
 * in it. With every part on, nothing here changes how sync works.
 */
internal object SyncParts {
    private val store = LiveTvDriveHttp
    private val PROFILE_SUFFIX = Regex("""_p\d+$""")

    private val LOGIN_KEYS = setOf(LiveTvKeys.playlists, LiveTvKeys.epgs, LiveTvKeys.playlistAccounts, LiveTvKeys.epgOverrides)
    private val FAVORITE_KEYS = setOf(
        LiveTvKeys.favorites, LiveTvKeys.hiddenChannels, LiveTvKeys.hiddenGroups, LiveTvKeys.channelNames,
        LiveTvKeys.channelLogos, LiveTvKeys.channelNumbers, LiveTvKeys.groupNames, LiveTvKeys.groupOrder,
        LiveTvKeys.customGroups, LiveTvKeys.channelOrder, LiveTvKeys.channelCopies, LiveTvKeys.channelAudio,
        LiveTvKeys.pictureShapes, LiveTvKeys.lockedGroups, LiveTvKeys.nameRemovals, LiveTvKeys.reminders,
        LiveTvKeys.vodHiddenCategories, LiveTvKeys.vodGroupOrder, LiveTvKeys.vodGroupNames,
    )
    private val SPORTS_KEYS = setOf(LiveTvKeys.sports, LiveTvKeys.sportsMine)
    /** Options that act the same on any device (not how the app looks), so phones and TVs share them. */
    private val BEHAVIOR_KEYS = setOf(
        "playlist_refresh_hours", "epg_refresh_hours", "epg_future_days", "epg_past_hours", "epg_offset_minutes",
        "record_pre_minutes", "record_post_minutes", "recording_space_rule", "record_series_keep", "reminder_lead_minutes",
        "catchup_prefer_hls", "catchup_cache", "catchup_shift_minutes", "timeshift_enabled", "timeshift_minutes",
        "auto_reconnect", "auto_backup_channel", "captions_by_default",
        "channel_sort", "group_sort", "vod_default_sort", "vod_sorts", "vod_merge_duplicates",
        "calendar_hide_spoilers", "calendar_hide_watched", "calendar_skip_news_talk", "calendar_sports", "calendar_new_episode_cards",
        "guide_search_catalog", "guide_search_live", "guide_search_ondemand",
        "vod_search_catalog", "vod_search_live", "vod_search_ondemand", "search_order", "logo_sources_off",
        "clean_group_names", "browse_by_channel_name",
        "on_demand_enabled", "on_demand_auto_play", "on_demand_addon_posters", "on_demand_streams_position",
        "on_demand_in_streams", "on_demand_play_direct",
    )
    private val CALENDAR_KEYS = setOf(
        LiveTvKeys.calendarFollows, LiveTvKeys.calendarHiddenShows, LiveTvKeys.calendarChannelPicks,
        LiveTvKeys.calendarPlans, LiveTvKeys.calendarCountry,
    )

    /** Always synced, whatever is switched off: the parental lock and the profile set-up marker. */
    private val ALWAYS_KEYS = setOf(LiveTvKeys.parentalPin, LiveTvKeys.lockAdultContent, "profile_seeded")

    init {
        // Behavior and options became their own part: the first time, take the saved copy's values for it.
        if (!store.getBoolean(BEHAVIOR_SEEN, false)) {
            store.putBoolean(BEHAVIOR_SEEN, true)
            store.putBoolean(FRESH + SyncPart.BEHAVIOR.name, true)
        }
    }

    private val _revision = MutableStateFlow(0)

    /** Changes whenever a switch is flipped (for the settings card to redraw). */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Which part a stored key belongs to, or null when it is always synced. Everything not named above is "Look and layout". */
    fun partOf(key: String): SyncPart? {
        val base = key.replace(PROFILE_SUFFIX, "")
        return when {
            base == "vpn_profiles_v1" -> SyncPart.VPN
            base in ALWAYS_KEYS -> null
            base in LOGIN_KEYS -> SyncPart.LOGINS
            base in FAVORITE_KEYS -> SyncPart.FAVORITES
            base in SPORTS_KEYS -> SyncPart.SPORTS
            base in CALENDAR_KEYS -> SyncPart.CALENDAR
            base in BEHAVIOR_KEYS -> SyncPart.BEHAVIOR
            else -> SyncPart.LOOK
        }
    }

    /**
     * A phone never syncs its look and layout: the saved copy is shared with TVs, whose look is
     * different, so [SyncPart.LOOK] is always off here (and not offered in settings).
     */
    fun isOn(part: SyncPart): Boolean = part != SyncPart.LOOK && store.getBoolean(KEY + part.name, part.defaultOn)

    fun isOff(part: SyncPart): Boolean = !isOn(part)

    private fun keyIsOn(key: String): Boolean = partOf(key)?.let { isOn(it) } ?: true

    /**
     * Turns a part on or off on this device. A part just turned on takes the saved copy's values
     * for what the copy has (see [freshen]), so this device doesn't overwrite what other devices
     * changed meanwhile.
     */
    fun set(part: SyncPart, on: Boolean) {
        if (part == SyncPart.LOOK || isOn(part) == on) return
        store.putBoolean(KEY + part.name, on)
        store.putBoolean(FRESH + part.name, on)
        _revision.value++
    }

    /** [data] without the parts that are off. */
    fun strip(data: JsonObject): JsonObject {
        if (SyncPart.entries.all { isOn(it) }) return data
        return JsonObject(data.filterKeys { keyIsOn(it) })
    }

    /** [view] plus the entries of the parts that are off, taken from [from]. */
    fun keep(view: JsonObject, from: JsonObject): JsonObject {
        if (SyncPart.entries.all { isOn(it) }) return view
        val out = view.toMutableMap()
        from.forEach { (k, v) -> if (!keyIsOn(k)) out[k] = v }
        return JsonObject(out)
    }

    /**
     * [local] where the saved copy's value wins for parts switched on since the last sync (the
     * lists of playlists and guides are left alone: they combine source by source anyway).
     */
    fun freshen(local: JsonObject, remote: JsonObject): JsonObject {
        val fresh = SyncPart.entries.filter { isOn(it) && store.getBoolean(FRESH + it.name, false) }
        if (fresh.isEmpty()) return local
        val out = local.toMutableMap()
        local.keys.forEach { k ->
            val base = k.replace(PROFILE_SUFFIX, "")
            if (base == LiveTvKeys.playlists || base == LiveTvKeys.epgs) return@forEach
            if (partOf(k)?.let { it in fresh } == true) remote[k]?.let { out[k] = it }
        }
        return JsonObject(out)
    }

    /** Called after a sync round finished: the parts switched on are now part of the agreed copy. */
    fun clearFresh() {
        SyncPart.entries.forEach { if (store.getBoolean(FRESH + it.name, false)) store.putBoolean(FRESH + it.name, false) }
    }

    private const val KEY = "sync_part_"
    private const val FRESH = "sync_part_fresh_"
    private const val BEHAVIOR_SEEN = "sync_part_behavior_seen"
}
