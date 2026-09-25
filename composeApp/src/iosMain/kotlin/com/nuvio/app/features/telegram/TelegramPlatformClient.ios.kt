package com.nuvio.app.features.telegram

import com.nuvio.app.features.telegram.iostelegram.NuvioTelegramCacheSize
import com.nuvio.app.features.telegram.iostelegram.NuvioTelegramClearCache
import com.nuvio.app.features.telegram.iostelegram.NuvioTelegramOptimizeCache
import com.nuvio.app.features.telegram.iostelegram.NuvioTelegramFree
import com.nuvio.app.features.telegram.iostelegram.NuvioTelegramPlaybackURL
import com.nuvio.app.features.telegram.iostelegram.NuvioTelegramReadConcat
import com.nuvio.app.features.telegram.iostelegram.NuvioTelegramRequest
import com.nuvio.app.features.telegram.iostelegram.NuvioTelegramStart
import com.nuvio.app.features.telegram.iostelegram.NuvioTelegramVirtualPlaybackURL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalForeignApi::class)
internal actual object TelegramPlatformClient {
    private val json = Json { encodeDefaults = true }

    actual val isSupported: Boolean = true

    actual fun start(apiId: Int, apiHash: String, appVersion: String): Boolean =
        NuvioTelegramStart(apiId, apiHash, appVersion) == 1

    actual fun request(json: String, timeoutSeconds: Double): String? =
        NuvioTelegramRequest(json, timeoutSeconds)?.let { pointer ->
            try {
                pointer.toKString()
            } finally {
                NuvioTelegramFree(pointer)
            }
        }

    actual fun playbackUrl(fileId: Int, fileSize: Long, fileName: String, mimeType: String?): String? =
        NuvioTelegramPlaybackURL(fileId, fileSize, fileName, mimeType)?.let { pointer ->
            try {
                pointer.toKString()
            } finally {
                NuvioTelegramFree(pointer)
            }
        }

    actual fun virtualPlaybackUrl(spec: TelegramVirtualPlaybackSpec): String? =
        NuvioTelegramVirtualPlaybackURL(json.encodeToString(spec))?.let { pointer ->
            try {
                pointer.toKString()
            } finally {
                NuvioTelegramFree(pointer)
            }
        }

    actual fun readConcat(parts: List<TelegramPlaybackPart>, offset: Long, length: Int): ByteArray? {
        if (parts.isEmpty() || length <= 0) return null
        return memScoped {
            val outLength = alloc<IntVar>()
            val pointer = NuvioTelegramReadConcat(
                json.encodeToString(parts),
                offset,
                length,
                outLength.ptr,
            ) ?: return@memScoped null
            try {
                val count = outLength.value.coerceAtLeast(0)
                pointer.readBytes(count)
            } finally {
                NuvioTelegramFree(pointer)
            }
        }
    }

    actual fun cacheSizeBytes(): Long = NuvioTelegramCacheSize()

    actual fun clearCache() = NuvioTelegramClearCache()

    actual fun optimizeCacheIfNeeded() = NuvioTelegramOptimizeCache()
}
