package com.nuvio.app.features.player

/** Last position from the playable surface, retained while VPN protection reconnects. */
internal class VpnPlaybackPosition {
    private var savedRevision: Long = -1L
    private var savedPositionMs: Long? = null

    fun forReload(revision: Long): Long? = savedPositionMs.takeIf { revision > savedRevision }

    fun record(revision: Long, snapshot: PlayerPlaybackSnapshot) {
        if (snapshot.isOpening || snapshot.isLoading || !snapshot.isSeekable) return
        if (revision < savedRevision) return
        if (snapshot.isEnded) {
            savedRevision = revision
            savedPositionMs = null
        } else if (snapshot.positionMs >= 0L && snapshot.positionMs < snapshot.durationMs) {
            savedRevision = revision
            savedPositionMs = snapshot.positionMs
        }
    }
}
