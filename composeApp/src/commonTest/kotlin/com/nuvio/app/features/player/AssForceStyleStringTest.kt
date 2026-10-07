package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssForceStyleStringTest {
    @Test
    fun preserveModeReturnsEmptyStringRegardlessOfFontSize() {
        val style = SubtitleStyleState(subAssOverride = AssOverrideMode.Preserve)
        assertEquals("", buildAssForceStyleString(style, 42))
        assertEquals("", buildAssForceStyleString(style, 0))
        assertEquals("", buildAssForceStyleString(style, 999))
    }

    @Test
    fun emitsPassedFontSizeVerbatimWithoutRescaling() {
        val style = SubtitleStyleState(
            // Opaque white text, transparent background (not boxed), default outline.
            textColor = Color(0xFFFFFFFF),
            backgroundColor = Color(0x00000000),
            outlineColor = Color(0xFF000000),
            outlineEnabled = true,
            outlineWidth = 2,
            bold = false,
            subAssOverride = AssOverrideMode.OverrideColorsAndOutlines,
        )
        val result = buildAssForceStyleString(style, 42)
        assertEquals(
            "Fontsize=42," +
                "PrimaryColour=&H00FFFFFF," +
                "BackColour=&H00000000," +
                "OutlineColour=&H00000000," +
                "Bold=0," +
                "BorderStyle=1," +
                "Outline=2," +
                "Shadow=0",
            result,
        )
    }

    @Test
    fun assColorInvertsAlphaAndOrdersBgr() {
        // 0x80 alpha -> inverted 0x7F; white channels -> FF FF FF in BGR order too.
        assertEquals("&H7FFFFFFF", Color(0x80FFFFFF).toAssColorString())
        // Opaque white -> alpha 0x00.
        assertEquals("&H00FFFFFF", Color(0xFFFFFFFF).toAssColorString())
        // Pure red, opaque -> B=00 G=00 R=FF.
        assertEquals("&H000000FF", Color(0xFFFF0000).toAssColorString())
    }

    @Test
    fun boxedBackgroundSelectsOpaqueBoxBorderStyle() {
        val boxed = SubtitleStyleState(
            backgroundColor = Color(0x80000000),
            subAssOverride = AssOverrideMode.ForceFullOverride,
        )
        val result = buildAssForceStyleString(boxed, 55)
        assertTrue(result.contains("BorderStyle=3"), result)
        // BackColour follows the (boxed) background, not the outline colour.
        assertTrue(result.contains("BackColour=&H7F000000"), result)
    }

    @Test
    fun transparentBackgroundUsesOutlineColourAndOutlineBorderStyle() {
        val style = SubtitleStyleState(
            backgroundColor = Color(0x00000000),
            outlineColor = Color(0xFFFF0000),
            subAssOverride = AssOverrideMode.ScaleOnly,
        )
        val result = buildAssForceStyleString(style, 20)
        assertTrue(result.contains("BorderStyle=1"), result)
        assertTrue(result.contains("BackColour=&H000000FF"), result)
    }

    @Test
    fun disabledOutlineAndBoldFlagsAreEncoded() {
        val noOutline = SubtitleStyleState(
            outlineEnabled = false,
            outlineWidth = 7,
            subAssOverride = AssOverrideMode.OverrideColorsAndOutlines,
        )
        assertTrue(buildAssForceStyleString(noOutline, 30).contains("Outline=0"))

        val bold = SubtitleStyleState(bold = true, subAssOverride = AssOverrideMode.ForceFullOverride)
        assertTrue(buildAssForceStyleString(bold, 30).contains("Bold=1"))
    }
}
