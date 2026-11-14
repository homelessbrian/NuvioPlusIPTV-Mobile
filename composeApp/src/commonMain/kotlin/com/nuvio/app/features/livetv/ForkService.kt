package com.nuvio.app.features.livetv

import com.nuvio.app.features.updater.UpdateConfig

/**
 * Our own livetv-cloud service. Its /v2/ door (cloud sync and the sports files) is only for our own
 * builds, which carry the app key ([UpdateConfig.APP_KEY], from the LIVETV_APP_KEY secret). A build
 * without the key keeps using the old addresses (/v1/ sync and the public nuvio-data files).
 */
internal object ForkService {
    const val URL = "https://livetv-cloud.nuvioplusiptv.workers.dev"
    const val HEADER = "X-App-Key"

    private const val PUBLIC_DATA = "https://raw.githubusercontent.com/homelessbrian/nuvio-data/main/"
    private val DATA_FOLDERS = listOf("sportsdb/", "espn/", "football/")

    val appKey: String = UpdateConfig.APP_KEY.trim()
    val hasKey: Boolean get() = appKey.isNotEmpty()

    /** "/v1/setup" -> "/v2/setup" when this build has the key. */
    fun syncPath(v1Path: String): String =
        if (hasKey && v1Path.startsWith("/v1/")) "/v2/" + v1Path.removePrefix("/v1/") else v1Path

    /** The service's address for a public nuvio-data sports file, or null to use [url] as is. */
    fun dataUrl(url: String): String? {
        if (!hasKey || !url.startsWith(PUBLIC_DATA)) return null
        val rest = url.removePrefix(PUBLIC_DATA)
        return if (DATA_FOLDERS.any { rest.startsWith(it) }) "$URL/v2/data/$rest" else null
    }
}
