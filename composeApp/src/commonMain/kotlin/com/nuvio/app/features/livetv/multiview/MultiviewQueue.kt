package com.nuvio.app.features.livetv.multiview

import com.nuvio.app.core.ui.NuvioToastController

/**
 * Channels set aside with "Add to Multiview" (a channel's long-press menu) or the player's
 * Multiview button. Multiview takes them when it opens and puts them in its panes. Held in
 * memory only, as channel keys: no stream links, and each one is re-checked against what the
 * active profile may watch when Multiview places it. Used from the UI thread only.
 */
internal object MultiviewQueue {
    const val MAX = 4
    private val keys = ArrayList<String>()

    /** Returns how many are set aside now, or -1 when the list is already full. */
    fun add(key: String): Int {
        if (key in keys) return keys.size
        if (keys.size >= MAX) return -1
        keys.add(key)
        return keys.size
    }

    /** Puts [key] first (the channel you were watching gets the first pane and the sound). */
    fun addFirst(key: String) {
        keys.remove(key)
        keys.add(0, key)
        while (keys.size > MAX) keys.removeAt(keys.lastIndex)
    }

    /** Hands over the set-aside channels and empties the list. */
    fun take(): List<String> = keys.toList().also { keys.clear() }

    fun addAndAnnounce(key: String) {
        val before = key in keys
        val count = add(key)
        NuvioToastController.show(when {
            count < 0 -> "Multiview already has $MAX channels waiting. Open Multiview to place them."
            before -> "Already added to Multiview ($count of $MAX)."
            else -> "Added to Multiview ($count of $MAX). Open Multiview from the Live TV header."
        })
    }
}

/**
 * Puts set-aside channels into the panes: a channel already showing stays where it is; the rest
 * fill empty panes first, then new panes (up to four). Returns the pane of the first channel.
 */
internal fun MultiviewSession.placeQueued(channels: List<com.nuvio.app.features.livetv.model.LiveChannel>): Int? {
    var firstPane: Int? = null
    channels.forEach { channel ->
        val current = selection.value
        val showing = (0 until current.layout.paneCount).firstOrNull { current.channels[it]?.key == channel.key }
        val pane = showing
            ?: (0 until current.layout.paneCount).firstOrNull { current.channels[it] == null }
            ?: if (current.layout.paneCount < MultiviewQueue.MAX) {
                setLayout(MultiviewLayout.entries.first { it.paneCount == current.layout.paneCount + 1 })
                current.layout.paneCount
            } else null
        if (pane != null) {
            if (showing == null) assign(pane, channel)
            if (firstPane == null) firstPane = pane
        }
    }
    return firstPane
}
