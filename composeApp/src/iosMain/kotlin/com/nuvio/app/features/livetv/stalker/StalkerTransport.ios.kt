package com.nuvio.app.features.livetv.stalker

import com.nuvio.stalker.StalkerError
import com.nuvio.stalker.StalkerException
import com.nuvio.stalker.StalkerHttpResponse
import com.nuvio.stalker.StalkerGuideHttpResponse
import com.nuvio.stalker.StalkerGuideRow

internal actual suspend fun stalkerRouteIdentity(): String = throw StalkerException(StalkerError.UNSUPPORTED)
internal actual suspend fun stalkerHttpGet(url: String, headers: Map<String, String>, route: String): StalkerHttpResponse =
    throw StalkerException(StalkerError.UNSUPPORTED)
internal actual suspend fun stalkerHttpGuide(url: String, headers: Map<String, String>, route: String, onRow: (String?, StalkerGuideRow) -> Unit): StalkerGuideHttpResponse =
    throw StalkerException(StalkerError.UNSUPPORTED)
internal actual suspend fun stalkerCheckMediaRoute(url: String, route: String, portalUrl: String) { throw StalkerException(StalkerError.UNSUPPORTED) }
internal actual fun stalkerCheckRouteNow(route: String, portalUrl: String?, actualUrl: String) { throw StalkerException(StalkerError.UNSUPPORTED) }
internal actual suspend fun stalkerProbeStreamType(url: String, policy: StalkerMediaPolicy): String? = null
