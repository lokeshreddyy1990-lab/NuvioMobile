package com.nuvio.app.features.telegram

/**
 * Retained-cache budget for Telegram playback data.
 *
 * This caps media that has already been watched and is being kept around for rewatching
 * or seeking back. The file currently being played is exempt: a single episode larger than
 * the budget still streams, and the cache trims back to the budget once playback ends.
 *
 * [NONE] is deliberately absent. TDLib treats a zero size as "delete everything" rather
 * than "do not cache", so exposing 0 here would wipe the cache instead of disabling it.
 */
enum class TelegramCacheSize(val bytes: Long) {
    GB_2(2L * 1024L * 1024L * 1024L),
    GB_4(4L * 1024L * 1024L * 1024L),
    GB_8(8L * 1024L * 1024L * 1024L),
    GB_16(16L * 1024L * 1024L * 1024L),
    ;

    companion object {
        val Default: TelegramCacheSize = GB_4

        fun fromStored(value: String?): TelegramCacheSize =
            value?.let { stored -> entries.firstOrNull { it.name == stored } } ?: Default
    }
}
