package com.nuvio.app.features.telegram

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object TelegramSettingsStorage {
    private const val cacheSizeLimitKey = "telegram_cache_size_limit"

    actual fun loadCacheSizeLimit(): TelegramCacheSize =
        TelegramCacheSize.fromStored(
            NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(cacheSizeLimitKey)),
        )

    actual fun saveCacheSizeLimit(limit: TelegramCacheSize) {
        NSUserDefaults.standardUserDefaults.setObject(
            limit.name,
            forKey = ProfileScopedKey.of(cacheSizeLimitKey),
        )
    }
}
