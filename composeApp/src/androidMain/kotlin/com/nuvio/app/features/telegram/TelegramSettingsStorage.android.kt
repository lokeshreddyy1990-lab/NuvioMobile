package com.nuvio.app.features.telegram

internal actual object TelegramSettingsStorage {
    actual fun loadCacheSizeLimit(): TelegramCacheSize = TelegramCacheSize.Default

    actual fun saveCacheSizeLimit(limit: TelegramCacheSize) = Unit
}
