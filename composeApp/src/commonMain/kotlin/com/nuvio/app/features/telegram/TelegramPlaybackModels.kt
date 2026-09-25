package com.nuvio.app.features.telegram

import kotlinx.serialization.Serializable

@Serializable
data class TelegramPlaybackPart(
    val fileId: Int,
    val size: Long,
)

@Serializable
data class TelegramVirtualPlaybackSpec(
    val parts: List<TelegramPlaybackPart>,
    val fileName: String,
    val mimeType: String? = null,
    val innerOffset: Long = 0,
    val innerSize: Long = 0,
)
