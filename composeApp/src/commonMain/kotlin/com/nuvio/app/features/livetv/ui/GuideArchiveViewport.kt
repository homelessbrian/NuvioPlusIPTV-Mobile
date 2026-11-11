package com.nuvio.app.features.livetv.ui

import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel

/** Only the rows actually on screen can request the portal's separate historical API. */
internal fun archiveChannelsForViewport(visible: List<LiveChannel>, fromMs: Long, toMs: Long, nowMs: Long,
    cached: Map<String, List<EpgProgram>>): List<LiveChannel> {
    val pastEnd = minOf(toMs, nowMs)
    if (fromMs >= pastEnd) return emptyList()
    return visible.filter { channel ->
        if (channel.stalker == null || channel.catchup == null) return@filter false
        val start = maxOf(fromMs, nowMs - channel.catchup.days * 86_400_000L)
        if (start >= pastEnd) return@filter false
        var through = start
        var needsArchive = false
        for (program in cached[channel.key].orEmpty()) {
            if (program.stopMs <= start || program.startMs >= pastEnd) continue
            if (program.startMs > through) needsArchive = true
            through = maxOf(through, program.stopMs)
            // A previously future listing may now have become available in the provider's archive.
            if (program.startMs < nowMs && (!program.stalkerArchiveAvailable || program.stalkerSourceIdentity != channel.stalker.sourceIdentity)) needsArchive = true
        }
        needsArchive || through < pastEnd
    }
}

/** Archive refreshes replace matching rows while retaining already visible future/past coverage. */
internal fun mergeGuideArchiveRows(base: List<EpgProgram>, archive: List<EpgProgram>): List<EpgProgram> =
    if (archive.isEmpty()) base else (base + archive).associateBy { it.startMs to it.stopMs }.values.sortedBy { it.startMs }
