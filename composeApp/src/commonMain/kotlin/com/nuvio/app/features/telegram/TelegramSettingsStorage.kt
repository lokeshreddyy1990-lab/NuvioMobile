package com.nuvio.app.features.telegram

/**
 * Persists Telegram playback settings that live outside the profile sync payload.
 *
 * Telegram is iOS-only today, so the Android actual is a no-op that always reports the
 * default. Keeping the declaration in common lets the repository stay platform-agnostic.
 */
internal expect object TelegramSettingsStorage {
    fun loadCacheSizeLimit(): TelegramCacheSize

    fun saveCacheSizeLimit(limit: TelegramCacheSize)
}
