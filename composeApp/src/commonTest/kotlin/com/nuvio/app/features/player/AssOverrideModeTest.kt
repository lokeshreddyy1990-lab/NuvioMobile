package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class AssOverrideModeTest {
    @Test
    fun mpvValueMappingMatchesMpvOptions() {
        assertEquals("no", AssOverrideMode.Preserve.mpvValue)
        assertEquals("scale", AssOverrideMode.ScaleOnly.mpvValue)
        assertEquals("yes", AssOverrideMode.OverrideColorsAndOutlines.mpvValue)
        assertEquals("force", AssOverrideMode.ForceFullOverride.mpvValue)
    }

    @Test
    fun defaultStylePreservesEmbeddedAss() {
        assertEquals(AssOverrideMode.Preserve, SubtitleStyleState.DEFAULT.subAssOverride)
        assertEquals(AssOverrideMode.Preserve, SubtitleStyleState().subAssOverride)
    }

    @Test
    fun enumNamesRoundTripThroughValueOf() {
        AssOverrideMode.entries.forEach { mode ->
            assertEquals(mode, AssOverrideMode.valueOf(mode.name))
        }
    }
}
