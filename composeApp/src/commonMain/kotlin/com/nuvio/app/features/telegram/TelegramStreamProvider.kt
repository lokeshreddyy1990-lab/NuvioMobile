package com.nuvio.app.features.telegram

import com.nuvio.app.features.details.MetaDetailsRepository

/**
 * A Telegram message search the stream pipeline should run for the current stream request.
 */
internal data class TelegramStreamSearch(
    val title: String,
)

/**
 * Adapter that lets Telegram participate in the addon/plugin stream pipeline.
 *
 * Telegram is not a Stremio addon, so [com.nuvio.app.features.streams.StreamsRepository] and
 * [com.nuvio.app.features.player.PlayerStreamsRepository] inject it as an extra provider group.
 * This object centralises the "can Telegram serve streams right now?" decision and the title
 * resolution that Telegram's message search depends on.
 */
internal object TelegramStreamProvider {

    /**
     * Resolves the Telegram search to run, or null when Telegram cannot serve this request.
     *
     * Returns null when Telegram is unconfigured, when the user is sitting on an authentication
     * step, or when no usable title could be resolved. A non-null result still requires awaiting
     * [TelegramRepository.awaitConnection] before searching, because a fresh app start can observe
     * [TelegramAuthorizationMode.Starting] before TDLib reports the persisted session.
     */
    fun resolveSearch(
        type: String,
        videoId: String,
        parentMetaId: String?,
        explicitTitle: String?,
    ): TelegramStreamSearch? {
        TelegramRepository.ensureLoaded()
        if (!TelegramRepository.isConfigured()) return null
        if (!TelegramRepository.uiState.value.mode.canServeStreams) return null

        val metaTitle = runCatching {
            MetaDetailsRepository.peek(
                type,
                parentMetaId?.takeIf(String::isNotBlank) ?: deriveMetaId(videoId),
            )
        }.getOrNull()?.name

        // Prefer the title the UI is already showing; fall back to the loaded meta entry.
        val title = resolveTelegramSearchTitle(
            searchTitle = explicitTitle,
            fallbackTitle = metaTitle,
        ) ?: return null
        return TelegramStreamSearch(title)
    }

    /** `tt123:1:5` -> `tt123`; ids without a season/episode suffix are returned unchanged. */
    private fun deriveMetaId(videoId: String): String {
        val parts = videoId.split(':')
        return if (parts.size >= 3) parts.dropLast(2).joinToString(":") else videoId
    }
}

/**
 * True when the authorization state is settled enough to attempt a search.
 *
 * `Starting` is included because it is also the initial state of a freshly started app, where the
 * persisted session has not been reported yet; the search path awaits the real state before use.
 * Every other non-ready state means the user is mid-auth or signed out.
 */
private val TelegramAuthorizationMode.canServeStreams: Boolean
    get() = when (this) {
        TelegramAuthorizationMode.Ready,
        TelegramAuthorizationMode.Starting,
        -> true

        TelegramAuthorizationMode.PhoneNumber,
        TelegramAuthorizationMode.Code,
        TelegramAuthorizationMode.EmailAddress,
        TelegramAuthorizationMode.EmailCode,
        TelegramAuthorizationMode.Password,
        TelegramAuthorizationMode.LoggingOut,
        TelegramAuthorizationMode.Unsupported,
        TelegramAuthorizationMode.MissingCredentials,
        TelegramAuthorizationMode.Error,
        -> false
    }
