package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerSettingsUiState(
    val showLoadingOverlay: Boolean = true,
    val preferredAudioLanguage: String = AudioLanguageOption.DEVICE,
    val secondaryPreferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String = SubtitleLanguageOption.NONE,
    val secondaryPreferredSubtitleLanguage: String? = null,
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
)

object PlayerSettingsRepository {
    private val _uiState = MutableStateFlow(PlayerSettingsUiState())
    val uiState: StateFlow<PlayerSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var showLoadingOverlay = true
    private var preferredAudioLanguage = AudioLanguageOption.DEVICE
    private var secondaryPreferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage = SubtitleLanguageOption.NONE
    private var secondaryPreferredSubtitleLanguage: String? = null
    private var streamReuseLastLinkEnabled = false
    private var streamReuseLastLinkCacheHours = 24

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        showLoadingOverlay = PlayerSettingsStorage.loadShowLoadingOverlay() ?: true
        preferredAudioLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadPreferredAudioLanguage())
                ?: AudioLanguageOption.DEVICE
        secondaryPreferredAudioLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadSecondaryPreferredAudioLanguage())
        preferredSubtitleLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadPreferredSubtitleLanguage())
                ?: SubtitleLanguageOption.NONE
        secondaryPreferredSubtitleLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadSecondaryPreferredSubtitleLanguage())
        streamReuseLastLinkEnabled = PlayerSettingsStorage.loadStreamReuseLastLinkEnabled() ?: false
        streamReuseLastLinkCacheHours = PlayerSettingsStorage.loadStreamReuseLastLinkCacheHours() ?: 24
        publish()
    }

    fun setShowLoadingOverlay(enabled: Boolean) {
        ensureLoaded()
        if (showLoadingOverlay == enabled) return
        showLoadingOverlay = enabled
        publish()
        PlayerSettingsStorage.saveShowLoadingOverlay(enabled)
    }

    fun setPreferredAudioLanguage(language: String) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language) ?: AudioLanguageOption.DEVICE
        if (preferredAudioLanguage == normalized) return
        preferredAudioLanguage = normalized
        publish()
        PlayerSettingsStorage.savePreferredAudioLanguage(normalized)
    }

    fun setSecondaryPreferredAudioLanguage(language: String?) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language)
        if (secondaryPreferredAudioLanguage == normalized) return
        secondaryPreferredAudioLanguage = normalized
        publish()
        PlayerSettingsStorage.saveSecondaryPreferredAudioLanguage(normalized)
    }

    fun setPreferredSubtitleLanguage(language: String) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language) ?: SubtitleLanguageOption.NONE
        if (preferredSubtitleLanguage == normalized) return
        preferredSubtitleLanguage = normalized
        publish()
        PlayerSettingsStorage.savePreferredSubtitleLanguage(normalized)
    }

    fun setSecondaryPreferredSubtitleLanguage(language: String?) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language)
        if (secondaryPreferredSubtitleLanguage == normalized) return
        secondaryPreferredSubtitleLanguage = normalized
        publish()
        PlayerSettingsStorage.saveSecondaryPreferredSubtitleLanguage(normalized)
    }

    fun setStreamReuseLastLinkEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamReuseLastLinkEnabled == enabled) return
        streamReuseLastLinkEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamReuseLastLinkEnabled(enabled)
    }

    fun setStreamReuseLastLinkCacheHours(hours: Int) {
        ensureLoaded()
        if (streamReuseLastLinkCacheHours == hours) return
        streamReuseLastLinkCacheHours = hours
        publish()
        PlayerSettingsStorage.saveStreamReuseLastLinkCacheHours(hours)
    }

    private fun publish() {
        _uiState.value = PlayerSettingsUiState(
            showLoadingOverlay = showLoadingOverlay,
            preferredAudioLanguage = preferredAudioLanguage,
            secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
            streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
        )
    }
}
