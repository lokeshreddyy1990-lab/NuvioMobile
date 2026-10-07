package com.nuvio.app.features.telegram

internal expect object TelegramPlatformClient {
    val isSupported: Boolean

    fun start(apiId: Int, apiHash: String, appVersion: String): Boolean

    fun request(json: String, timeoutSeconds: Double = 30.0): String?

    fun playbackUrl(fileId: Int, fileSize: Long, fileName: String, mimeType: String?): String?

    fun virtualPlaybackUrl(spec: TelegramVirtualPlaybackSpec): String?

    fun readConcat(parts: List<TelegramPlaybackPart>, offset: Long, length: Int): ByteArray?

    /**
     * Reads a byte range from a single Telegram file without materialising the whole file.
     * Used to probe a disc image's header before deciding whether it is playable.
     */
    fun readFile(fileId: Int, offset: Long, length: Int): ByteArray?

    fun cacheSizeBytes(): Long

    fun clearCache()

    fun optimizeCacheIfNeeded()
}
