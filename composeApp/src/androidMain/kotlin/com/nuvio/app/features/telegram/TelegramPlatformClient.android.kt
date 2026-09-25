package com.nuvio.app.features.telegram

internal actual object TelegramPlatformClient {
    actual val isSupported: Boolean = false

    actual fun start(apiId: Int, apiHash: String, appVersion: String): Boolean = false

    actual fun request(json: String, timeoutSeconds: Double): String? = null

    actual fun playbackUrl(fileId: Int, fileSize: Long, fileName: String, mimeType: String?): String? = null

    actual fun virtualPlaybackUrl(spec: TelegramVirtualPlaybackSpec): String? = null

    actual fun readConcat(parts: List<TelegramPlaybackPart>, offset: Long, length: Int): ByteArray? = null

    actual fun cacheSizeBytes(): Long = 0L

    actual fun clearCache() = Unit

    actual fun optimizeCacheIfNeeded() = Unit
}
