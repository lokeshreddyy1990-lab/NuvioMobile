package com.nuvio.app.features.telegram

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramSplitFilesTest {
    @Test
    fun splitScanStepsByTdlibMessageIdSpacing() {
        val ids = telegramSplitScanIds(seedId = 50L shl 20)
        assertTrue((50L shl 20) in ids)
        assertTrue((51L shl 20) in ids)
        assertTrue((52L shl 20) in ids)
        assertFalse((50L shl 20) + 1 in ids)
    }

    @Test
    fun zipVolumesShareAGroupKey() {
        val first = parseTelegramSplitInfo("Reacher.S01.zip.001")
        val second = parseTelegramSplitInfo("Reacher.S01.zip.002")
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.groupKey, second.groupKey)
        assertEquals("reacher.s01.zip", first.groupKey)
        assertEquals(1, first.partNumber)
        assertEquals(2, second.partNumber)
        assertTrue(first.isZip)
        assertTrue(contiguousTelegramParts(listOf(1, 2)))
    }

    @Test
    fun partAndCdNamesAreNotJoined() {
        assertNull(parseTelegramSplitInfo("Reacher.S01E01.Part.01.mkv"))
        assertNull(parseTelegramSplitInfo("Movie.CD01.mkv"))
        assertNull(parseTelegramSplitInfo("Movie.Disc02.mkv"))
    }

    @Test
    fun videoSplitUsesExtensionPrefix() {
        val info = parseTelegramSplitInfo("Avatar.2009.2160p.BluRay.mkv.001")
        assertNotNull(info)
        assertFalse(info.isZip)
        assertEquals("avatar.2009.2160p.bluray.mkv", info.groupKey)
        assertEquals("Avatar.2009.2160p.BluRay.mkv", info.displayName)
    }

    @Test
    fun episodeQueriesIncludeSeasonPackNames() {
        val queries = telegramSearchQueries("Reacher", 1, 3)
        assertTrue(queries.contains("Reacher S01"))
        assertTrue(queries.contains("Reacher.S01"))
        assertTrue(queries.contains("Reacher S01E03"))
    }

    @Test
    fun innerEpisodeMatchPicksS01E03Only() {
        assertTrue(telegramInnerMatchesEpisode("Reacher.S01E03.mkv", 1, 3))
        assertFalse(telegramInnerMatchesEpisode("Reacher.S01E08.mkv", 1, 3))
        assertFalse(telegramInnerMatchesEpisode("Reacher.S02E03.mkv", 1, 3))
        assertTrue(telegramInnerMatchesEpisode("E03.mkv", 1, 3))
    }

    @Test
    fun storedZipIndexSelectsMatchingEpisode() {
        val zip = storedZip(
            listOf(
                "Reacher.S01E01.mkv" to byteArrayOf(1, 1),
                "Reacher.S01E03.mkv" to byteArrayOf(3, 3, 3),
                "Reacher.S01E08.mkv" to byteArrayOf(8),
            ),
        )
        val entries = parseTelegramZipEntries(zip)
        assertEquals(3, entries.size)
        val selected = selectTelegramZipEntry(entries, season = 1, episode = 3)
        assertNotNull(selected)
        assertEquals("Reacher.S01E03.mkv", selected.name)
        assertEquals(3, selected.size)
        assertEquals(ZIP_STORED, selected.method)
        val bytes = zip.copyOfRange(selected.dataOffset.toInt(), (selected.dataOffset + selected.size).toInt())
        assertTrue(bytes.contentEquals(byteArrayOf(3, 3, 3)))
        assertNull(selectTelegramZipEntry(entries, season = 1, episode = 9))
    }


    @Test
    fun sameNameAndSizeCollapseAcrossChats() {
        val first = telegramStream(
            url = "http://127.0.0.1:1/telegram/v/1/Dune.mkv",
            name = "Dune.2021.2160p.mkv",
            size = 4_000L,
        )
        // Same release mirrored in another chat: a different file id, so a different URL.
        val mirror = telegramStream(
            url = "http://127.0.0.1:1/telegram/v/9/Dune.mkv",
            name = "Dune.2021.2160p.mkv",
            size = 4_000L,
        )
        // A different release that happens to share the name must survive.
        val other = telegramStream(
            url = "http://127.0.0.1:1/telegram/v/7/Dune.mkv",
            name = "Dune.2021.2160p.mkv",
            size = 8_000L,
        )
        val deduped = listOf(first, mirror, other).distinctBy(::telegramStreamIdentity)
        assertEquals(2, deduped.size)
        assertEquals(first.url, deduped.first().url)
        assertEquals(other.url, deduped.last().url)
    }

    @Test
    fun differentlyNamedFilesAreNeverCollapsed() {
        val a = telegramStream("http://127.0.0.1:1/telegram/v/1/a.mkv", "Movie.mkv", 4_000L)
        val b = telegramStream("http://127.0.0.1:1/telegram/v/2/b.mkv", "Movie.2024.mkv", 4_000L)
        assertEquals(2, listOf(a, b).distinctBy(::telegramStreamIdentity).size)
    }
}

private fun telegramStream(url: String, name: String, size: Long): StreamItem = StreamItem(
    url = url,
    name = name,
    addonName = "Telegram",
    addonId = "telegram",
    behaviorHints = StreamBehaviorHints(videoSize = size),
)

private fun storedZip(files: List<Pair<String, ByteArray>>): ByteArray {
    val locals = ArrayList<ByteArray>()
    val localsOffsets = ArrayList<Int>()
    var cursor = 0
    for ((name, payload) in files) {
        localsOffsets += cursor
        val nameBytes = name.encodeToByteArray()
        val header = ByteArray(30)
        header[0] = 0x50
        header[1] = 0x4b
        header[2] = 0x03
        header[3] = 0x04
        writeU16(header, 4, 20)
        writeU16(header, 8, 0)
        writeU32(header, 18, payload.size)
        writeU32(header, 22, payload.size)
        writeU16(header, 26, nameBytes.size)
        val local = header + nameBytes + payload
        locals += local
        cursor += local.size
    }
    val cdRecords = ArrayList<ByteArray>()
    var cdSize = 0
    files.forEachIndexed { index, (name, payload) ->
        val nameBytes = name.encodeToByteArray()
        val record = ByteArray(46 + nameBytes.size)
        record[0] = 0x50
        record[1] = 0x4b
        record[2] = 0x01
        record[3] = 0x02
        writeU16(record, 4, 20)
        writeU16(record, 6, 20)
        writeU16(record, 10, 0)
        writeU32(record, 20, payload.size)
        writeU32(record, 24, payload.size)
        writeU16(record, 28, nameBytes.size)
        writeU32(record, 42, localsOffsets[index])
        nameBytes.copyInto(record, 46)
        cdRecords += record
        cdSize += record.size
    }
    val eocd = ByteArray(22)
    eocd[0] = 0x50
    eocd[1] = 0x4b
    eocd[2] = 0x05
    eocd[3] = 0x06
    writeU16(eocd, 8, files.size)
    writeU16(eocd, 10, files.size)
    writeU32(eocd, 12, cdSize)
    writeU32(eocd, 16, cursor)
    var total = ByteArray(0)
    locals.forEach { total += it }
    cdRecords.forEach { total += it }
    return total + eocd
}

private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xff).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
}

private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xff).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
    bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
    bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
}

