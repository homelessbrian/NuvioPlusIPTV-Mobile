package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.thumbs.SEEK_THUMB_STORAGE_CHOICES
import com.nuvio.app.features.player.thumbs.SeekThumbWhen
import com.nuvio.app.features.player.thumbs.SeekThumbsPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Settings > Playback > Video: Seek Thumbnails (Android only). Every limit is a choice the user
 * can change; nothing is locked away on lower-end phones.
 */
@Composable
internal fun PlaybackSeekThumbnailSettings(isTablet: Boolean) {
    if (!SeekThumbsPlatform.supported) return
    val s by SeekThumbsPlatform.settings.collectAsState()
    val lowMemory = remember { SeekThumbsPlatform.isLowMemoryDevice() }
    var savedBytes by remember { mutableStateOf<Long?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, s.enabled) {
        savedBytes = withContext(Dispatchers.IO) { runCatching { SeekThumbsPlatform.savedBytes() }.getOrNull() }
    }
    val padding = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 6.dp)

    SettingsSection(title = "Seek Thumbnails", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = "Seek Thumbnails",
                description = "Shows a preview picture above the seek bar while you drag it. Pictures are made on this phone from the video itself",
                checked = s.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> SeekThumbsPlatform.updateSettings { it.copy(enabled = on) } },
            )
            Text(
                "Works on: movies and shows (including streamed HLS links), recordings, sports replays and highlights, and streamed (HLS) catch-up.\n" +
                    "Doesn't work on: live channels, torrents, DASH links, protected streams, or catch-up that plays straight from the provider as one file (it would need a second connection on your provider account).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = padding,
            )
            if (lowMemory) {
                Text(
                    "This device has limited memory. Thumbnails may cause stutter. If they do, they pause by themselves and the preview says so.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = padding,
                )
            }
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = "Include 4K and HDR",
                description = "Also makes thumbnails for 4K and HDR videos. Starts on for devices with plenty of memory and off for lower-end ones, but you can turn it on anywhere and try it. 4K thumbnails may stutter or not work on lower-end devices, and use more data. Pictures of HDR videos can look washed out. Streamed links use their smallest version, so this rarely matters for them",
                checked = s.include4k,
                enabled = s.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> SeekThumbsPlatform.updateSettings { it.copy(include4k = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = "When to Make Thumbnails: " + s.whenToMake.label,
                description = s.whenToMake.detail + ". Tap to change",
                enabled = s.enabled,
                isTablet = isTablet,
                onClick = {
                    val all = SeekThumbWhen.entries
                    SeekThumbsPlatform.updateSettings { it.copy(whenToMake = all[(all.indexOf(it.whenToMake) + 1) % all.size]) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = "Storage Limit: ${s.storageMb} MB",
                description = "The most space saved thumbnails can use. The oldest are removed first. Tap to change",
                enabled = s.enabled,
                isTablet = isTablet,
                onClick = {
                    val all = SEEK_THUMB_STORAGE_CHOICES
                    SeekThumbsPlatform.updateSettings { it.copy(storageMb = all[(all.indexOf(it.storageMb) + 1) % all.size]) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = "Clear Saved Thumbnails",
                description = savedBytes?.let { "Uses ${formatThumbBytes(it)}" } ?: "Checking…",
                isTablet = isTablet,
                onClick = {
                    SeekThumbsPlatform.clearAll()
                    refresh++
                },
            )
            if (s.lastLowMemoryAt > 0L) {
                Text(
                    "Thumbnails last paused for low memory: " + agoLabel(com.nuvio.app.features.livetv.model.LiveTvClock.nowMs() - s.lastLowMemoryAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = padding,
                )
            }
        }
    }
}

private fun formatThumbBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 1.0) "less than 1 MB" else "${(mb * 10).toLong() / 10.0} MB"
}

private fun agoLabel(ms: Long): String {
    val min = (ms / 60_000L).coerceAtLeast(0L)
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}
