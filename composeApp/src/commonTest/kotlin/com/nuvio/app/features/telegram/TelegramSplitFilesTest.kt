package com.nuvio.app.features.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramSplitFilesTest {
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
    fun plainIsoIsRecognisedAsADiscImage() {
        assertTrue(isTelegramStreamableName("Dune.Part.One.2021.iso", null))
        assertTrue(isTelegramIsoName("Dune.Part.One.2021.iso"))
        assertTrue(isTelegramIsoName("Movie.ISO"))
        assertTrue(isTelegramIsoName("Movie.img"))
        assertFalse(isTelegramIsoName("Movie.mkv"))
    }

    @Test
    fun isoVolumeNamesShareAGroupKey() {
        val first = parseTelegramSplitInfo("Dune.2021.2160p.iso.001")
        val second = parseTelegramSplitInfo("Dune.2021.2160p.iso.002")
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.groupKey, second.groupKey)
        assertEquals("dune.2021.2160p.iso", first.groupKey)
        assertEquals(1, first.partNumber)
        assertEquals(2, second.partNumber)
        assertFalse(first.isZip)
        assertTrue(first.isIso)
        assertEquals("Dune.2021.2160p.iso", first.displayName)
        assertTrue(isTelegramIsoName("Dune.2021.2160p.iso.001"))
    }

    @Test
    fun isoVolumesDoNotCollideWithAVideoSplit() {
        val iso = parseTelegramSplitInfo("Dune.2021.iso.001")
        val video = parseTelegramSplitInfo("Dune.2021.mkv.001")
        assertNotNull(iso)
        assertNotNull(video)
        assertTrue(iso.isIso)
        assertFalse(video.isIso)
        assertNotEquals(iso.groupKey, video.groupKey)
    }

    @Test
    fun isoVolumeIsNotTreatedAsARawVideoSplit() {
        // `.iso.001` must reach the disc-image path, not the plain concatenation path.
        val info = parseTelegramSplitInfo("Movie.iso.001")
        assertNotNull(info)
        assertFalse(info.isZip)
        assertTrue(info.isIso)
        assertFalse(isTelegramVideoFileName(info.displayName))
    }

    @Test
    fun discPayloadExtensionsMapToRealMimeTypes() {
        assertEquals("video/mp2t", mimeTypeForFileName("00001.m2ts"))
        assertEquals("video/mpeg", mimeTypeForFileName("VTS_01_1.VOB"))
        assertEquals("application/x-iso9660-image", mimeTypeForFileName("Movie.iso"))
        assertTrue(isTelegramDiscStreamFileName("00001.m2ts"))
        assertTrue(isTelegramDiscStreamFileName("VTS_01_1.vob"))
        assertFalse(isTelegramDiscStreamFileName("PLAYLIST.bdmv"))
    }

    @Test
    fun isoScannerFindsLargestBluRayTitle() {
        // The fixture models a flat one-level tree: `STREAM` holds the Blu-ray payloads.
        val image = isoImage(
            listOf(
                "STREAM/00000.m2ts" to ByteArray(MIN_ISO_TEST_PAYLOAD) { 0x11 },
                "STREAM/00001.m2ts" to ByteArray(MIN_ISO_TEST_PAYLOAD * 2) { 0x22 },
                "PLAYLIST/00000.mpls" to ByteArray(64) { 0x33 },
            ),
        )
        val entry = findTelegramIsoEntry(image.size.toLong()) { offset, length ->
            image.copyOfRange(offset.toInt(), minOf(offset.toInt() + length, image.size))
        }
        assertNotNull(entry)
        assertEquals("00001.m2ts", entry.name)
        assertEquals(MIN_ISO_TEST_PAYLOAD * 2L, entry.size)
        // The selected range must be the large payload's own bytes, not an overlapping extent.
        assertTrue(
            image.copyOfRange(entry.offset.toInt(), (entry.offset + entry.size).toInt())
                .all { it == 0x22.toByte() },
        )
    }

    @Test
    fun isoScannerFindsDvdTitleInsideVideoTs() {
        val image = isoImage(
            listOf(
                "VIDEO_TS/VIDEO_TS.IFO" to ByteArray(64) { 0x44 },
                // A title-set menu VOB, deliberately below the floor and so skipped.
                "VIDEO_TS/VTS_01_0.VOB" to ByteArray(MIN_ISO_TEST_PAYLOAD / 2) { 0x55 },
                "VIDEO_TS/VTS_01_1.VOB" to ByteArray(MIN_ISO_TEST_PAYLOAD) { 0x66 },
            ),
        )
        val entry = findTelegramIsoEntry(image.size.toLong()) { offset, length ->
            image.copyOfRange(offset.toInt(), minOf(offset.toInt() + length, image.size))
        }
        assertNotNull(entry)
        assertEquals("VTS_01_1.VOB", entry.name)
        assertEquals(MIN_ISO_TEST_PAYLOAD.toLong(), entry.size)
        assertTrue(
            image.copyOfRange(entry.offset.toInt(), (entry.offset + entry.size).toInt())
                .all { it == 0x66.toByte() },
        )
    }

    @Test
    fun isoScannerReturnsNullWhenNoDiscTreeExists() {
        val junk = ByteArray(4096) { 0x7f }
        assertNull(
            findTelegramIsoEntry(junk.size.toLong()) { offset, length ->
                junk.copyOfRange(offset.toInt(), minOf(offset.toInt() + length, junk.size))
            },
        )
        assertNull(findTelegramIsoEntry(0L) { _, _ -> null })
    }
}

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

internal const val ISO_TEST_SECTOR = 2048
internal const val ISO_TEST_SYSTEM_AREA = ISO_TEST_SECTOR * 16
private const val ISO_TEST_PVD_SECTOR = 16
internal const val ISO_TEST_ROOT_SECTOR = 17

/**
 * Payload size used by the disc-image fixture, chosen to clear the scanner's 32 MB menu
 * floor while staying small enough to build a test image in memory.
 */
internal const val MIN_ISO_TEST_PAYLOAD = 33 * 1024 * 1024

/**
 * Builds a minimal but structurally valid ISO9660 image holding [files], so the disc
 * scanner can be exercised without a real multi-gigabyte rip.
 *
 * Layout: sector 16 is the Primary Volume Descriptor, sector 17 is the root directory,
 * then one directory extent plus payload extents per entry. Intermediates are dropped
 * (their sizes are recorded before the final layout pass), so callers must assert on leaf
 * payloads, which is exactly what the scanner selects.
 */
internal fun isoImage(files: List<Pair<String, ByteArray>>): ByteArray {
    val leafBytes = files.sumOf { it.second.size }
    // Sectors needed beyond the system area: PVD (1) + root dir (1) + one dir per file,
    // plus the payload extents. The system area itself is reserved below.
    val firstDataSector = ISO_TEST_ROOT_SECTOR + 1 + files.size * 2
    val dataSectors = firstDataSector + (leafBytes / ISO_TEST_SECTOR) + 8
    val totalBytes = ISO_TEST_SYSTEM_AREA + dataSectors * ISO_TEST_SECTOR
    val image = ByteArray(totalBytes)

    // Extents are addressed by logical block address, but stored after the 16-sector system
    // area, so every write must use `lba * SECTOR + SYSTEM_AREA` — the same arithmetic the
    // reader uses. Getting this wrong silently shifts every payload.
    fun extentOffset(lba: Int): Int = lba * ISO_TEST_SECTOR + ISO_TEST_SYSTEM_AREA

    // Primary Volume Descriptor. The Root Directory Record is embedded at offset 156 and
    // keeps its own field layout: 156 = record length, 158 = extent LBA, 166 = data length.
    val pvd = ISO_TEST_SYSTEM_AREA
    image[pvd] = 1
    "CD001".encodeToByteArray().copyInto(image, pvd + 1)
    image[pvd + 6] = 1
    image[pvd + 156] = 34
    writeBothEndianU32(image, pvd + 158, ISO_TEST_ROOT_SECTOR)
    writeBothEndianU32(image, pvd + 166, ISO_TEST_SECTOR)

    val rootDir = ByteArray(ISO_TEST_SECTOR)
    var rootCursor = 0
    rootCursor += isoRecord(rootDir, rootCursor, 0x00, "\u0000", ISO_TEST_ROOT_SECTOR, ISO_TEST_SECTOR)
    rootCursor += isoRecord(rootDir, rootCursor, 0x01, "\u0001", ISO_TEST_ROOT_SECTOR, ISO_TEST_SECTOR)

    // One leaf directory per distinct parent folder, holding every payload that lives in it.
    // Leaf directories occupy the sectors immediately after the root, so payloads start past them.
    val byDirectory = files.groupBy { it.first.substringBeforeLast('/', "") }
    var dirSector = ISO_TEST_ROOT_SECTOR + 1
    var payloadSector = dirSector + byDirectory.size
    for ((dirName, entries) in byDirectory) {
        val leafDir = ByteArray(ISO_TEST_SECTOR)
        var cursor = 0
        cursor += isoRecord(leafDir, cursor, 0x00, "\u0000", dirSector, ISO_TEST_SECTOR)
        cursor += isoRecord(leafDir, cursor, 0x01, "\u0001", dirSector, ISO_TEST_SECTOR)

        var sector = payloadSector
        for ((path, payload) in entries) {
            payload.copyInto(image, extentOffset(sector))
            val fileName = "${path.substringAfterLast('/')};1"
            // Advance by the record's own padded length, name suffix included, so the next
            // record cannot overwrite this one's tail.
            cursor += isoRecord(leafDir, cursor, 0x00, fileName, sector, payload.size)
            sector += (payload.size / ISO_TEST_SECTOR) + 1
        }
        leafDir.copyInto(image, extentOffset(dirSector))

        if (dirName.isNotEmpty()) {
            val record = isoDirectoryRecord(dirName, dirSector)
            record.copyInto(rootDir, rootCursor)
            rootCursor += record.size
        }
        dirSector += 1
        payloadSector = sector
    }

    rootDir.copyInto(image, extentOffset(ISO_TEST_ROOT_SECTOR))
    return image
}

/** Padded byte length of a directory record carrying a name of [nameLength] bytes. */
private fun isoRecordLength(nameLength: Int): Int {
    val length = 33 + nameLength
    return if (length % 2 == 0) length else length + 1
}


internal fun isoDirectoryRecord(name: String, childExtent: Int): ByteArray {
    val nameBytes = name.encodeToByteArray()
    val length = 33 + nameBytes.size
    // A record's byte 0 stores the PADDED length, so the backing array must be padded too.
    val padded = if (length % 2 == 0) length else length + 1
    val record = ByteArray(padded)
    isoRecord(record, 0, 0x02, name, childExtent, ISO_TEST_SECTOR)
    return record
}

internal fun isoRecord(
    target: ByteArray,
    offset: Int,
    flags: Int,
    name: String,
    extent: Int,
    size: Int,
): Int {
    val nameBytes = name.encodeToByteArray()
    val length = 33 + nameBytes.size
    val padded = if (length % 2 == 0) length else length + 1
    target[offset] = padded.toByte()
    writeBothEndianU32(target, offset + 2, extent)
    writeBothEndianU32(target, offset + 10, size)
    target[offset + 25] = flags.toByte()
    target[offset + 32] = nameBytes.size.toByte()
    nameBytes.copyInto(target, offset + 33)
    return padded
}

/** Writes the little-endian half of an ISO9660 both-endian 32-bit field. */
internal fun writeBothEndianU32(bytes: ByteArray, offset: Int, value: Int) {
    writeU32(bytes, offset, value)
}

