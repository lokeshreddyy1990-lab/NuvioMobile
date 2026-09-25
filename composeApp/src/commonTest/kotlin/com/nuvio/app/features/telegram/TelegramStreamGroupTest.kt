package com.nuvio.app.features.telegram

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TelegramStreamGroupTest {
    @Test
    fun resolveTelegramSearchTitlePrefersSearchTitle() {
        assertEquals("Dune", resolveTelegramSearchTitle("Dune", "Fallback"))
    }

    @Test
    fun resolveTelegramSearchTitleTrimsSearchTitle() {
        assertEquals("Dune", resolveTelegramSearchTitle("  Dune  ", null))
    }

    @Test
    fun resolveTelegramSearchTitleFallsBackWhenSearchTitleBlank() {
        assertEquals("Fallback", resolveTelegramSearchTitle("   ", "Fallback"))
        assertEquals("Fallback", resolveTelegramSearchTitle(null, "  Fallback "))
    }

    @Test
    fun resolveTelegramSearchTitleReturnsNullWithoutUsableTitle() {
        assertNull(resolveTelegramSearchTitle(null, null))
        assertNull(resolveTelegramSearchTitle("  ", null))
        assertNull(resolveTelegramSearchTitle(null, "   "))
    }

    @Test
    fun telegramLoadingStreamGroupIsLoadingWithTelegramIdentity() {
        val group = telegramLoadingStreamGroup()
        assertEquals("Telegram", group.addonName)
        assertEquals(TELEGRAM_ADDON_ID, group.addonId)
        assertEquals(true, group.isLoading)
        assertEquals(0, group.streams.size)
        assertNull(group.error)
    }

    @Test
    fun telegramStreamGroupKeepsStreams() {
        val streams = listOf(
            StreamItem(
                name = "Dune.1080p.mkv",
                url = "http://127.0.0.1:8080/play/1",
                addonName = "Telegram",
                addonId = TELEGRAM_ADDON_ID,
            ),
        )
        val group = telegramStreamGroup(streams)
        assertEquals(streams, group.streams)
        assertFalse(group.isLoading)
        assertNull(group.error)
        assertEquals(TELEGRAM_ADDON_ID, group.addonId)
    }

    @Test
    fun telegramErrorStreamGroupCarriesMessageWithNoStreams() {
        val group = telegramErrorStreamGroup("boom")
        assertEquals("boom", group.error)
        assertEquals(0, group.streams.size)
        assertFalse(group.isLoading)
        assertEquals(TELEGRAM_ADDON_ID, group.addonId)
        assertEquals("Telegram", group.addonName)
    }
}