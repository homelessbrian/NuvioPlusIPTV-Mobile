package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object StreamsRepository {
    private val log = Logger.withTag("StreamsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(StreamsUiState())
    val uiState: StateFlow<StreamsUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    /**
     * Loads streams for a given type + videoId from all installed addons that declare
     * the "stream" resource for the given type (and matching idPrefixes if set).
     *
     * For movies: videoId == meta id (e.g. "tt1234567")
     * For series: videoId == "{metaId}:{season}:{episode}" (e.g. "tt0898266:9:17")
     */
    fun load(type: String, videoId: String) {
        activeJob?.cancel()
        _uiState.value = StreamsUiState()

        val streamAddons = AddonRepository.uiState.value.addons
            .mapNotNull { it.manifest }
            .filter { manifest ->
                manifest.resources.any { resource ->
                    resource.name == "stream" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() ||
                            resource.idPrefixes.any { videoId.startsWith(it) })
                }
            }

        log.d { "Found ${streamAddons.size} addons for stream type=$type id=$videoId" }

        if (streamAddons.isEmpty()) {
            _uiState.value = StreamsUiState(isAnyLoading = false)
            return
        }

        // Initialise loading placeholders
        val initialGroups = streamAddons.map { manifest ->
            AddonStreamGroup(
                addonName = manifest.name,
                addonId = manifest.id,
                streams = emptyList(),
                isLoading = true,
            )
        }
        _uiState.value = StreamsUiState(
            groups = initialGroups,
            activeAddonIds = streamAddons.map { it.id }.toSet(),
            isAnyLoading = true,
        )

        activeJob = scope.launch {
            val jobs = streamAddons.map { manifest ->
                async {
                    val encodedId = videoId.encodeForPath()
                    val url = "${manifest.transportUrl}/stream/$type/$encodedId.json"
                    log.d { "Fetching streams from: $url" }

                    runCatching {
                        val payload = httpGetText(url)
                        StreamParser.parse(
                            payload = payload,
                            addonName = manifest.name,
                            addonId = manifest.id,
                        )
                    }.fold(
                        onSuccess = { streams ->
                            log.d { "Got ${streams.size} streams from ${manifest.name}" }
                            AddonStreamGroup(
                                addonName = manifest.name,
                                addonId = manifest.id,
                                streams = streams,
                                isLoading = false,
                            )
                        },
                        onFailure = { err ->
                            log.w(err) { "Failed to fetch streams from ${manifest.name}" }
                            AddonStreamGroup(
                                addonName = manifest.name,
                                addonId = manifest.id,
                                streams = emptyList(),
                                isLoading = false,
                                error = err.message,
                            )
                        },
                    )
                }
            }

            // Collect results as they arrive and update state incrementally
            jobs.forEach { deferred ->
                val result = deferred.await()
                _uiState.update { current ->
                    val updated = current.groups.map { group ->
                        if (group.addonId == result.addonId) result else group
                    }
                    current.copy(
                        groups = updated,
                        isAnyLoading = updated.any { it.isLoading },
                    )
                }
            }
        }
    }

    fun selectFilter(addonId: String?) {
        _uiState.update { it.copy(selectedFilter = addonId) }
    }

    fun clear() {
        activeJob?.cancel()
        _uiState.value = StreamsUiState()
    }

    // Encode id segment so colons and slashes don't break URL path parsing on addons
    private fun String.encodeForPath(): String =
        replace("%", "%25").replace(" ", "%20")
}
