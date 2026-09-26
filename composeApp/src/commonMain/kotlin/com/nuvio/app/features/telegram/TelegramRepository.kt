package com.nuvio.app.features.telegram

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray


enum class TelegramAuthorizationMode {
    Unsupported,
    MissingCredentials,
    Starting,
    PhoneNumber,
    Code,
    EmailAddress,
    EmailCode,
    Password,
    Ready,
    LoggingOut,
    Error,
}

data class TelegramUiState(
    val mode: TelegramAuthorizationMode = TelegramAuthorizationMode.Starting,
    val displayName: String? = null,
    val username: String? = null,
    val errorMessage: String? = null,
    val cacheSizeBytes: Long = 0L,
    val isBusy: Boolean = false,
) {
    val isConnected: Boolean get() = mode == TelegramAuthorizationMode.Ready
}

object TelegramRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(TelegramUiState())
    val uiState: StateFlow<TelegramUiState> = _uiState.asStateFlow()

    private var initializationJob: Job? = null
    private var initialized = false

    fun ensureLoaded() {
        if (initialized) return
        initialized = true
        initializationJob = scope.launch {
            when {
                !TelegramPlatformClient.isSupported -> {
                    _uiState.value = TelegramUiState(mode = TelegramAuthorizationMode.Unsupported)
                }
                TelegramConfig.API_ID <= 0 || TelegramConfig.API_HASH.isBlank() -> {
                    _uiState.value = TelegramUiState(mode = TelegramAuthorizationMode.MissingCredentials)
                }
                !TelegramPlatformClient.start(
                    apiId = TelegramConfig.API_ID,
                    apiHash = TelegramConfig.API_HASH,
                    appVersion = AppVersionConfig.VERSION_NAME,
                ) -> {
                    _uiState.value = TelegramUiState(
                        mode = TelegramAuthorizationMode.Error,
                        errorMessage = "TDLib could not be started",
                    )
                }
                else -> pollAuthorizationState()
            }
        }
    }

    /** True when Telegram credentials are present and TDLib can run on this platform. */
    fun isConfigured(): Boolean =
        TelegramPlatformClient.isSupported &&
            TelegramConfig.API_ID > 0 &&
            TelegramConfig.API_HASH.isNotBlank()

    /**
     * Waits for Telegram's authorization state to settle and reports whether it is connected.
     *
     * The first stream load after app start can race TDLib's startup and observe
     * [TelegramAuthorizationMode.Starting] even for a persisted session, so callers must await
     * this instead of reading [uiState] directly.
     */
    suspend fun awaitConnection(timeoutMs: Long = TELEGRAM_CONNECTION_TIMEOUT_MS): Boolean {
        ensureLoaded()
        val settled = withTimeoutOrNull(timeoutMs) {
            uiState.first { state -> state.mode != TelegramAuthorizationMode.Starting }
        }
        return settled?.isConnected == true
    }

    fun submitPhoneNumber(phoneNumber: String) = submitAuthenticationRequest(
        buildJsonObject {
            put("@type", "setAuthenticationPhoneNumber")
            put("phone_number", phoneNumber.trim())
            put("settings", JsonNull)
        },
    )

    fun submitCode(code: String) = submitAuthenticationRequest(
        buildJsonObject {
            put("@type", "checkAuthenticationCode")
            put("code", code.trim())
        },
    )

    fun submitEmailAddress(emailAddress: String) = submitAuthenticationRequest(
        buildJsonObject {
            put("@type", "setAuthenticationEmailAddress")
            put("email_address", emailAddress.trim())
        },
    )

    fun submitEmailCode(code: String) = submitAuthenticationRequest(
        buildJsonObject {
            put("@type", "checkAuthenticationEmailCode")
            put("code", buildJsonObject {
                put("@type", "emailAddressAuthenticationCode")
                put("code", code.trim())
            })
        },
    )

    fun submitPassword(password: String) = submitAuthenticationRequest(
        buildJsonObject {
            put("@type", "checkAuthenticationPassword")
            put("password", password)
        },
    )

    fun logOut() = submitAuthenticationRequest(
        buildJsonObject { put("@type", "logOut") },
    )

    fun refreshCacheSize() {
        scope.launch {
            TelegramPlatformClient.optimizeCacheIfNeeded()
            _uiState.value = _uiState.value.copy(cacheSizeBytes = TelegramPlatformClient.cacheSizeBytes())
        }
    }

    fun clearCache() {
        scope.launch {
            TelegramPlatformClient.clearCache()
            _uiState.value = _uiState.value.copy(cacheSizeBytes = TelegramPlatformClient.cacheSizeBytes())
        }
    }

    private fun maybeOptimizeCache() {
        if (!_uiState.value.isConnected) return
        scope.launch { TelegramPlatformClient.optimizeCacheIfNeeded() }
    }

    suspend fun searchStreams(
        title: String,
        season: Int?,
        episode: Int?,
        limit: Int = 40,
    ): List<StreamItem> = withContext(Dispatchers.Default) {
        ensureLoaded()
        if (!_uiState.value.isConnected || title.isBlank()) return@withContext emptyList()

        val messages = buildTelegramSearchQueries(title, season, episode)
            .flatMap { query -> searchMessages(query, limit) }
            .distinctBy { message ->
                val value = message.jsonObject
                value.long("chat_id") to value.long("id")
            }
        val chatTitles = mutableMapOf<Long, String>()
        val hits = messages.mapNotNull { element -> telegramHit(element.jsonObject) }
        val used = mutableSetOf<Pair<Long, Long>>()
        val grouped = hits.groupBy { hit ->
            val split = parseTelegramSplitInfo(hit.fileName) ?: return@groupBy null
            hit.chatId to split.groupKey
        }

        val streams = mutableListOf<StreamItem>()
        for ((key, groupHits) in grouped) {
            if (key == null) continue
            val (chatId, groupKey) = key
            val seed = groupHits.minBy { it.messageId }
            val parts = gatherSplitParts(chatId, seed.messageId, groupKey, groupHits)
            if (!contiguousTelegramParts(parts.keys)) continue
            val ordered = parts.entries.sortedBy { it.key }.map { it.value }
            val split = parseTelegramSplitInfo(ordered.first().fileName) ?: continue
            val stream = if (!split.isZip && isTelegramIsoName(split.displayName)) {
                isoStreamItem(
                    hits = ordered,
                    displayName = split.displayName,
                    season = season,
                    episode = episode,
                    chatTitles = chatTitles,
                )
            } else {
                virtualStreamItem(
                    hits = ordered,
                    isZip = split.isZip,
                    displayName = split.displayName,
                    season = season,
                    episode = episode,
                    chatTitles = chatTitles,
                )
            } ?: continue
            ordered.forEach { hit -> used += hit.chatId to hit.messageId }
            streams += stream
        }

        for (hit in hits) {
            if (hit.chatId to hit.messageId in used) continue
            if (parseTelegramSplitInfo(hit.fileName) != null) continue
            if (!isTelegramStreamableName(hit.fileName, hit.mimeType)) continue
            if (hit.fileName.endsWith(".zip", ignoreCase = true)) continue
            // A disc image is not a video stream: open it and surface the title inside.
            if (isTelegramIsoName(hit.fileName)) {
                val opened = isoStreamItem(
                    hits = listOf(hit),
                    displayName = hit.fileName,
                    season = season,
                    episode = episode,
                    chatTitles = chatTitles,
                )
                if (opened != null) {
                    streams.add(opened)
                    continue
                }
                // No readable ISO9660 tree. A UDF-only disc still has a chance of playing
                // if the engine can mount it, so surface the raw image; anything else
                // (an audio ISO, a corrupt upload) stays hidden rather than failing on tap.
                val isDiscImage = hasUdfAnchor(hit.fileSize) { offset, length ->
                    TelegramPlatformClient.readFile(hit.fileId, offset, length)
                }
                if (isDiscImage) singleStreamItem(hit, chatTitles)?.let { streams.add(it) }
                continue
            }
            streams += singleStreamItem(hit, chatTitles) ?: continue
        }
        streams.distinctBy { it.url }
    }

    private fun telegramHit(message: JsonObject): TelegramHit? {
        val chatId = message.long("chat_id")
        val messageId = message.long("id")
        if (chatId == 0L || messageId == 0L) return null
        val content = message.objectValue("content") ?: return null
        val media = content.telegramMedia() ?: return null
        val caption = content.objectValue("caption")?.string("text")
        val rawFileName = media.fileName.takeUnless { it.startsWith("Telegram video ") }
            ?: caption?.takeIf { it.isNotBlank() }
            ?: media.fileName
        // Mirror bots append the byte size, which would otherwise hide `.iso` from every
        // split pattern and drop the upload before it can be grouped.
        val fileName = stripTelegramSizeToken(rawFileName)
        if (!isTelegramStreamableName(fileName, media.mimeType)) return null
        return TelegramHit(
            chatId = chatId,
            messageId = messageId,
            fileId = media.fileId,
            fileSize = media.fileSize,
            fileName = fileName,
            mimeType = media.mimeType,
            caption = caption,
        )
    }

    private fun gatherSplitParts(
        chatId: Long,
        seedId: Long,
        groupKey: String,
        known: List<TelegramHit>,
    ): Map<Int, TelegramHit> {
        val parts = mutableMapOf<Int, TelegramHit>()
        fun consider(hit: TelegramHit) {
            val split = parseTelegramSplitInfo(hit.fileName) ?: return
            if (hit.chatId != chatId || split.groupKey != groupKey) return
            if (split.partNumber !in parts) {
                parts[split.partNumber] = hit
            }
        }
        known.forEach(::consider)
        val startId = maxOf(1L, seedId - SPLIT_SCAN_WINDOW)
        val ids = (startId..(seedId + SPLIT_SCAN_WINDOW)).toList()
        val response = request(
            buildJsonObject {
                put("@type", "getMessages")
                put("chat_id", chatId)
                putJsonArray("message_ids") {
                    ids.forEach { id -> add(JsonPrimitive(id)) }
                }
            },
            timeoutSeconds = 20.0,
        )
        val nearby = response?.get("messages") as? JsonArray ?: JsonArray(emptyList())
        nearby.forEach { element ->
            val message = element as? JsonObject ?: return@forEach
            telegramHit(message)?.let(::consider)
        }
        return parts
    }

    private fun virtualStreamItem(
        hits: List<TelegramHit>,
        isZip: Boolean,
        displayName: String,
        season: Int?,
        episode: Int?,
        chatTitles: MutableMap<Long, String>,
    ): StreamItem? {
        val playbackParts = hits.map { TelegramPlaybackPart(fileId = it.fileId, size = it.fileSize) }
        val fileName: String
        val fileSize: Long
        val mimeType: String?
        val innerOffset: Long
        val innerSize: Long
        if (isZip) {
            val zipSize = playbackParts.sumOf { it.size }
            val entries = parseTelegramZipEntries(zipSize) { offset, length ->
                TelegramPlatformClient.readConcat(playbackParts, offset, length)
            }
            val entry = selectTelegramZipEntry(entries, season, episode)
            if (entry == null) {
                // The archive stores a disc image, not a video. Open the image and publish
                // the title inside it; the archive bytes are never handed to the player.
                val image = selectTelegramZipImageEntry(entries, season, episode) ?: return null
                return zipIsoStreamItem(
                    playbackParts = playbackParts,
                    entry = image,
                    displayName = displayName,
                    season = season,
                    episode = episode,
                    hits = hits,
                    chatTitles = chatTitles,
                )
            }
            fileName = entry.name
            fileSize = entry.size
            mimeType = mimeTypeForFileName(entry.name)
            innerOffset = entry.dataOffset
            innerSize = entry.size
        } else {
            fileName = displayName
            fileSize = playbackParts.sumOf { it.size }
            mimeType = hits.first().mimeType ?: mimeTypeForFileName(displayName)
            innerOffset = 0
            innerSize = fileSize
        }
        val url = TelegramPlatformClient.virtualPlaybackUrl(
            TelegramVirtualPlaybackSpec(
                parts = playbackParts,
                fileName = fileName,
                mimeType = mimeType,
                innerOffset = innerOffset,
                innerSize = innerSize,
            ),
        ) ?: return null
        val chatTitle = chatTitle(hits.first().chatId, chatTitles)
        val caption = hits.first().caption
        return StreamItem(
            name = fileName,
            title = fileName,
            description = listOfNotNull(chatTitle, caption?.takeIf { it.isNotBlank() }).joinToString(" • "),
            url = url,
            sourceName = chatTitle,
            addonName = "Telegram",
            addonId = TELEGRAM_ADDON_ID,
            behaviorHints = StreamBehaviorHints(
                notWebReady = true,
                videoSize = fileSize,
                filename = fileName,
            ),
        )
    }

    /**
     * Opens a disc image (`.iso`, `.img`, or its `.iso.001` volumes) and publishes the
     * main title found inside it.
     *
     * The image is never handed to the player directly: an ISO is a filesystem, not a
     * bitstream. The ISO9660 tree is walked for a Blu-ray `STREAM` payload or a DVD
     * `VTS_*.VOB` and that byte range is exposed as a virtual URL, so a 40 GB image costs
     * only the directory reads plus the title itself.
     */
    private fun isoStreamItem(
        hits: List<TelegramHit>,
        displayName: String,
        season: Int?,
        episode: Int?,
        chatTitles: MutableMap<Long, String>,
    ): StreamItem? {
        val playbackParts = hits.map { TelegramPlaybackPart(fileId = it.fileId, size = it.fileSize) }
        val imageSize = playbackParts.sumOf { it.size }
        if (imageSize <= 0L) return null
        val entry = findTelegramIsoEntry(
            imageSize = imageSize,
            season = season,
            episode = episode,
        ) { offset, length -> TelegramPlatformClient.readConcat(playbackParts, offset, length) } ?: return null

        val url = TelegramPlatformClient.virtualPlaybackUrl(
            TelegramVirtualPlaybackSpec(
                parts = playbackParts,
                fileName = entry.name,
                mimeType = mimeTypeForFileName(entry.name),
                innerOffset = entry.offset,
                innerSize = entry.size,
            ),
        ) ?: return null

        val chatTitle = chatTitle(hits.first().chatId, chatTitles)
        val caption = hits.first().caption
        val label = "$displayName ▸ ${entry.name}"
        return StreamItem(
            name = label,
            title = label,
            description = listOfNotNull(
                chatTitle,
                formatTelegramSize(entry.size),
                caption?.takeIf { it.isNotBlank() },
            ).joinToString(" • "),
            url = url,
            sourceName = chatTitle,
            addonName = "Telegram",
            addonId = TELEGRAM_ADDON_ID,
            behaviorHints = StreamBehaviorHints(
                notWebReady = true,
                videoSize = entry.size,
                filename = entry.name,
            ),
        )
    }

    /**
     * Publishes the main title inside a disc image that itself lives inside a ZIP archive
     * (`Name.iso.zip.001`). The archive bytes are never handed to the player: [entry]'s
     * range is walked as an image and only the payload it holds becomes the stream URL.
     */
    private fun zipIsoStreamItem(
        playbackParts: List<TelegramPlaybackPart>,
        entry: TelegramZipEntry,
        displayName: String,
        season: Int?,
        episode: Int?,
        hits: List<TelegramHit>,
        chatTitles: MutableMap<Long, String>,
    ): StreamItem? {
        val inner = findTelegramIsoEntry(entry.size, season, episode) { offset, length ->
            TelegramPlatformClient.readConcat(playbackParts, entry.dataOffset + offset, length)
        } ?: return null

        val url = TelegramPlatformClient.virtualPlaybackUrl(
            TelegramVirtualPlaybackSpec(
                parts = playbackParts,
                fileName = inner.name,
                mimeType = mimeTypeForFileName(inner.name),
                innerOffset = entry.dataOffset + inner.offset,
                innerSize = inner.size,
            ),
        ) ?: return null

        val chatTitle = chatTitle(hits.first().chatId, chatTitles)
        val caption = hits.first().caption
        val label = "$displayName ▸ ${inner.name}"
        return StreamItem(
            name = label,
            title = label,
            description = listOfNotNull(
                chatTitle,
                formatTelegramSize(inner.size),
                caption?.takeIf { it.isNotBlank() },
            ).joinToString(" • "),
            url = url,
            sourceName = chatTitle,
            addonName = TELEGRAM_ADDON_NAME,
            addonId = TELEGRAM_ADDON_ID,
            behaviorHints = StreamBehaviorHints(
                notWebReady = true,
                videoSize = inner.size,
                filename = inner.name,
            ),
        )
    }

    private fun singleStreamItem(
        hit: TelegramHit,
        chatTitles: MutableMap<Long, String>,
    ): StreamItem? {
        val playbackUrl = TelegramPlatformClient.playbackUrl(
            fileId = hit.fileId,
            fileSize = hit.fileSize,
            fileName = hit.fileName,
            mimeType = hit.mimeType,
        ) ?: return null
        val chatTitle = chatTitle(hit.chatId, chatTitles)
        return StreamItem(
            name = hit.fileName,
            title = hit.fileName,
            description = listOfNotNull(chatTitle, hit.caption?.takeIf { it.isNotBlank() }).joinToString(" • "),
            url = playbackUrl,
            sourceName = chatTitle,
            addonName = "Telegram",
            addonId = TELEGRAM_ADDON_ID,
            behaviorHints = StreamBehaviorHints(
                notWebReady = true,
                videoSize = hit.fileSize,
                filename = hit.fileName,
            ),
        )
    }

    private fun chatTitle(chatId: Long, chatTitles: MutableMap<Long, String>): String =
        chatTitles.getOrPut(chatId) {
            request(
                buildJsonObject {
                    put("@type", "getChat")
                    put("chat_id", chatId)
                },
                timeoutSeconds = 8.0,
            )?.string("title") ?: "Telegram"
        }

    private fun searchMessages(query: String, limit: Int): List<kotlinx.serialization.json.JsonElement> {
        val response = request(
            buildJsonObject {
                put("@type", "searchMessages")
                put("chat_list", JsonNull)
                put("query", query)
                put("offset", "")
                put("limit", limit.coerceIn(1, 100))
                put("filter", buildJsonObject { put("@type", "searchMessagesFilterEmpty") })
                put("chat_type_filter", JsonNull)
                put("min_date", 0)
                put("max_date", 0)
            },
            timeoutSeconds = 45.0,
        ) ?: return emptyList()
        if (response.type == "error") return emptyList()
        return response["messages"]?.jsonArray.orEmpty()
    }

    private suspend fun pollAuthorizationState() {
        while (true) {
            refreshAuthorizationState()
            delay(if (_uiState.value.isConnected) 5_000L else 750L)
        }
    }

    private fun refreshAuthorizationState() {
        val response = request(buildJsonObject { put("@type", "getAuthorizationState") })
            ?: return
        val mode = when (response.type) {
            "authorizationStateWaitPhoneNumber" -> TelegramAuthorizationMode.PhoneNumber
            "authorizationStateWaitCode" -> TelegramAuthorizationMode.Code
            "authorizationStateWaitEmailAddress" -> TelegramAuthorizationMode.EmailAddress
            "authorizationStateWaitEmailCode" -> TelegramAuthorizationMode.EmailCode
            "authorizationStateWaitPassword" -> TelegramAuthorizationMode.Password
            "authorizationStateReady" -> TelegramAuthorizationMode.Ready
            "authorizationStateLoggingOut", "authorizationStateClosing", "authorizationStateClosed" ->
                TelegramAuthorizationMode.LoggingOut
            "error" -> TelegramAuthorizationMode.Error
            else -> TelegramAuthorizationMode.Starting
        }
        val current = _uiState.value
        _uiState.value = current.copy(
            mode = mode,
            errorMessage = if (response.type == "error") response.string("message") else null,
            isBusy = false,
            cacheSizeBytes = TelegramPlatformClient.cacheSizeBytes(),
        )
        if (mode == TelegramAuthorizationMode.Ready) {
            maybeOptimizeCache()
            if (current.displayName == null) {
                refreshProfile()
            }
        }
    }

    private fun refreshProfile() {
        val response = request(buildJsonObject { put("@type", "getMe") }) ?: return
        if (response.type == "error") return
        val firstName = response.string("first_name").orEmpty()
        val lastName = response.string("last_name").orEmpty()
        _uiState.value = _uiState.value.copy(
            displayName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Telegram" },
            username = response.objectValue("usernames")
                ?.get("active_usernames")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonPrimitive
                ?.contentOrNull,
        )
    }

    private fun submitAuthenticationRequest(payload: JsonObject) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, errorMessage = null)
            val response = request(payload)
            if (response == null || response.type == "error") {
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    errorMessage = response?.string("message") ?: "Telegram request timed out",
                )
            } else {
                delay(250)
                refreshAuthorizationState()
            }
        }
    }

    private fun request(payload: JsonObject, timeoutSeconds: Double = 30.0): JsonObject? =
        TelegramPlatformClient.request(payload.toString(), timeoutSeconds)
            ?.let { response -> runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() }
}

const val TELEGRAM_ADDON_ID = "telegram"
internal const val TELEGRAM_ADDON_NAME = "Telegram"
private const val SPLIT_SCAN_WINDOW = 20L
private const val TELEGRAM_CONNECTION_TIMEOUT_MS = 4_000L

/**
 * Human-readable size, used in the disc-image stream description.
 */
internal fun formatTelegramSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    val rendered = if (value >= 100 || value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        val rounded = (value * 10).toLong() / 10.0
        rounded.toString()
    }
    return "$rendered ${units[unit]}"
}

internal fun resolveTelegramSearchTitle(searchTitle: String?, fallbackTitle: String?): String? =
    searchTitle?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallbackTitle?.trim()?.takeIf { it.isNotEmpty() }

internal fun telegramLoadingStreamGroup(): AddonStreamGroup = AddonStreamGroup(
    addonName = TELEGRAM_ADDON_NAME,
    addonId = TELEGRAM_ADDON_ID,
    streams = emptyList(),
    isLoading = true,
)

internal fun telegramStreamGroup(streams: List<StreamItem>): AddonStreamGroup = AddonStreamGroup(
    addonName = TELEGRAM_ADDON_NAME,
    addonId = TELEGRAM_ADDON_ID,
    streams = streams,
    isLoading = false,
)

internal fun telegramErrorStreamGroup(message: String?): AddonStreamGroup = AddonStreamGroup(
    addonName = TELEGRAM_ADDON_NAME,
    addonId = TELEGRAM_ADDON_ID,
    streams = emptyList(),
    isLoading = false,
    error = message,
)

private data class TelegramHit(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileSize: Long,
    val fileName: String,
    val mimeType: String?,
    val caption: String?,
)

private data class TelegramMedia(
    val fileId: Int,
    val fileSize: Long,
    val fileName: String,
    val mimeType: String?,
)

private fun JsonObject.telegramMedia(): TelegramMedia? {
    val mediaObject = when (type) {
        "messageVideo" -> objectValue("video")
        "messageDocument" -> objectValue("document")
        else -> null
    } ?: return null
    val fileObject = when (type) {
        "messageVideo" -> mediaObject.objectValue("video")
        else -> mediaObject.objectValue("document")
    } ?: return null
    val fileId = fileObject.int("id").takeIf { it > 0 } ?: return null
    // Telegram reports `expected_size` for a file that is still uploading and `size` can
    // lag behind it; the larger value is the real total, which the disc-image probe needs.
    val fileSize = maxOf(fileObject.long("size"), fileObject.long("expected_size"))
        .takeIf { it > 0 } ?: return null
    return TelegramMedia(
        fileId = fileId,
        fileSize = fileSize,
        fileName = mediaObject.string("file_name")?.takeIf { it.isNotBlank() }
            ?: "Telegram video $fileId",
        mimeType = mediaObject.string("mime_type"),
    )
}

private fun buildTelegramSearchQueries(title: String, season: Int?, episode: Int?): List<String> =
    telegramSearchQueries(title, season, episode)

private val JsonObject.type: String? get() = string("@type")
private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0
private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L
private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
