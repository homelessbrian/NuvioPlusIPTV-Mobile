package com.nuvio.app.features.player

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

actual object PlayerSettingsStorage {
    private const val showLoadingOverlayKey = "show_loading_overlay"
    private const val preferredAudioLanguageKey = "preferred_audio_language"
    private const val secondaryPreferredAudioLanguageKey = "secondary_preferred_audio_language"
    private const val preferredSubtitleLanguageKey = "preferred_subtitle_language"
    private const val secondaryPreferredSubtitleLanguageKey = "secondary_preferred_subtitle_language"
    private const val streamReuseLastLinkEnabledKey = "stream_reuse_last_link_enabled"
    private const val streamReuseLastLinkCacheHoursKey = "stream_reuse_last_link_cache_hours"

    actual fun loadShowLoadingOverlay(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(showLoadingOverlayKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveShowLoadingOverlay(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(showLoadingOverlayKey))
    }

    actual fun loadPreferredAudioLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(preferredAudioLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun savePreferredAudioLanguage(language: String) {
        NSUserDefaults.standardUserDefaults.setObject(language, forKey = ProfileScopedKey.of(preferredAudioLanguageKey))
    }

    actual fun loadSecondaryPreferredAudioLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredAudioLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun saveSecondaryPreferredAudioLanguage(language: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredAudioLanguageKey)
        if (language.isNullOrBlank()) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(language, forKey = key)
        }
    }

    actual fun loadPreferredSubtitleLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(preferredSubtitleLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun savePreferredSubtitleLanguage(language: String) {
        NSUserDefaults.standardUserDefaults.setObject(language, forKey = ProfileScopedKey.of(preferredSubtitleLanguageKey))
    }

    actual fun loadSecondaryPreferredSubtitleLanguage(): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey)
        return defaults.stringForKey(key)
    }

    actual fun saveSecondaryPreferredSubtitleLanguage(language: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey)
        if (language.isNullOrBlank()) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(language, forKey = key)
        }
    }

    actual fun loadStreamReuseLastLinkEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamReuseLastLinkEnabledKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            null
        }
    }

    actual fun saveStreamReuseLastLinkEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(streamReuseLastLinkEnabledKey))
    }

    actual fun loadStreamReuseLastLinkCacheHours(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveStreamReuseLastLinkCacheHours(hours: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(hours.toLong(), forKey = ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey))
    }
}
