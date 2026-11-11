package com.nuvio.app.features.livetv.stalker

internal object StalkerPlaybackSupport {
    const val ROUTING = "Stalker portals require HTTP + HTTPS or Full app VPN protection. Change the VPN protection mode and retry."
    const val ENGINE = "Stalker playback currently requires the internal ExoPlayer engine to protect portal credentials."
    const val ROUTE_MISMATCH = "The Stalker portal and stream use different VPN routes. Use a remote portal and consistent VPN protection, then retry."
    fun isRestriction(message: String?): Boolean = message == ROUTING || message == ENGINE || message == ROUTE_MISMATCH
}

/** Static host capability error, safe to show without copying any provider response. */
internal class StalkerRoutingException : Exception(StalkerPlaybackSupport.ROUTING)
internal class StalkerRouteMismatchException : Exception(StalkerPlaybackSupport.ROUTE_MISMATCH)

/** Consumers without a typed transport cannot hash, inspect or share an authenticated portal URL. */
internal fun mayUseGenericMediaRequests(hasPolicy: Boolean, stream: com.nuvio.app.features.streams.StreamItem?, url: String): Boolean =
    !hasPolicy && stream?.stalkerStream == null && !StalkerGateway.isLocator(url)

/** Provider artwork and a signed URL's basename are private even when only metadata is shared. */
internal fun partyMetadataForTransport(media: com.nuvio.app.features.party.PartyMedia, genericRequestsAllowed: Boolean): com.nuvio.app.features.party.PartyMedia =
    if (genericRequestsAllowed) media else media.copy(poster = null, background = null, logo = null, source = media.source?.copy(filename = null))
