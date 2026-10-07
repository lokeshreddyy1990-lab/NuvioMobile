package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Shared, pure helpers that translate a [SubtitleStyleState] into the mpv
 * `sub-ass-force-style` string, mirroring PlayTorrioV3's Flutter
 * `_buildAssForceStyleString` / `_toAssColor`.
 *
 * Kept in commonMain (rather than duplicated per platform) so both the Android
 * libmpv engine and the iOS MPVKit engine emit an identical style payload, and
 * so the logic is unit-testable in commonTest.
 */

/**
 * Convert a Compose [Color] to an ASS/SSA colour literal: `&HAABBGGRR`.
 *
 * ASS stores an *inverted* alpha (0x00 = opaque, 0xFF = fully transparent),
 * and channels in BGR order — matching Flutter's `_toAssColor`.
 */
internal fun Color.toAssColorString(): String {
    fun byte(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)
    fun hex(value: Int): String = value.toString(16).padStart(2, '0').uppercase()

    val invertedAlpha = 255 - byte(alpha)
    val r = byte(red)
    val g = byte(green)
    val b = byte(blue)
    return "&H" + hex(invertedAlpha) + hex(b) + hex(g) + hex(r)
}

/**
 * Build the `sub-ass-force-style` payload for [style], or an empty string when
 * the mode is [AssOverrideMode.Preserve] (mpv `no`), which means "don't force
 * anything" and also clears any previously set value.
 *
 * [fontSize] is supplied by the calling engine so the forced `Fontsize` matches
 * that engine's own `sub-font-size` scaling (Android libmpv vs iOS MPVKit use
 * different factors) — no rescaling happens here.
 */
internal fun buildAssForceStyleString(
    style: SubtitleStyleState,
    fontSize: Int,
): String {
    if (style.subAssOverride == AssOverrideMode.Preserve) return ""

    // A background with any opacity means an opaque box (BorderStyle 3) rather
    // than a plain outline (BorderStyle 1).
    val isBoxed = style.backgroundColor.alpha.toByteValue() > 0
    val backColour = if (isBoxed) style.backgroundColor else style.outlineColor
    val outline = if (style.outlineEnabled) style.outlineWidth else 0

    return buildString {
        append("Fontsize=").append(fontSize)
        append(",PrimaryColour=").append(style.textColor.toAssColorString())
        append(",BackColour=").append(backColour.toAssColorString())
        append(",OutlineColour=").append(style.outlineColor.toAssColorString())
        append(",Bold=").append(if (style.bold) 1 else 0)
        append(",BorderStyle=").append(if (isBoxed) 3 else 1)
        append(",Outline=").append(outline)
        append(",Shadow=0")
    }
}

private fun Float.toByteValue(): Int = (this * 255f).roundToInt().coerceIn(0, 255)
