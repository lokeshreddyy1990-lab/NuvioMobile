package com.nuvio.app.features.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A UDF-only Blu-ray image has no ISO9660 `CD001` descriptor, so only the UDF walk can
 * find its titles. These tests build minimal ECMA-167 trees in memory and assert the
 * scanner exposes the BDMV/STREAM title extent rather than the raw image.
 */
class TelegramUdfStreamTest {
    @Test
    fun udfOnlyBluRayImageResolvesStreamExtent() {
        val image = physicalUdfImage(withStreams = true)
        val entry = findTelegramIsoEntry(image.size.toLong(), null, null, readFrom(image))
        assertNotNull(entry)
        assertEquals("00001.m2ts", entry.name)
        assertEquals(PAYLOAD_LARGE.toLong(), entry.size)
        assertTrue(
            image.copyOfRange(entry.offset.toInt(), (entry.offset + entry.size).toInt())
                .all { it == 0x22.toByte() },
        )
    }

    @Test
    fun udfImageWithoutBdmvStreamReturnsNull() {
        val image = physicalUdfImage(withStreams = false)
        assertNull(findTelegramIsoEntry(image.size.toLong(), null, null, readFrom(image)))
    }

    @Test
    fun udfExtendedFileEntryAndMetadataPartitionResolveStream() {
        val image = metadataUdfImage()
        val entry = findTelegramIsoEntry(image.size.toLong(), null, null, readFrom(image))
        assertNotNull(entry)
        assertEquals("00001.m2ts", entry.name)
        assertEquals(PAYLOAD_LARGE.toLong(), entry.size)
        assertTrue(
            image.copyOfRange(entry.offset.toInt(), (entry.offset + entry.size).toInt())
                .all { it == 0x22.toByte() },
        )
    }

    @Test
    fun truncatedUdfImageNeverThrows() {
        val truncated = physicalUdfImage(withStreams = true).copyOf(300 * BLOCK)
        assertNull(findTelegramIsoEntry(truncated.size.toLong(), null, null, readFrom(truncated)))
        assertNull(findTelegramIsoEntry(0L, null, null) { _, _ -> null })
    }
}

private const val BLOCK = 2048
private const val PAYLOAD_SMALL = 4096
private const val PAYLOAD_LARGE = 8192

private fun readFrom(image: ByteArray): (Long, Int) -> ByteArray? = { offset, length ->
    if (offset < 0 || offset >= image.size) {
        null
    } else {
        image.copyOfRange(offset.toInt(), minOf(offset.toInt() + length, image.size))
    }
}

private fun blockOffset(block: Int): Int = block * BLOCK

private fun writeTag(target: ByteArray, offset: Int, tagId: Int) {
    writeU16(target, offset, tagId)
    writeU16(target, offset + 2, 2)
}

private fun writeLongAd(target: ByteArray, offset: Int, length: Int, block: Int, partitionRef: Int) {
    writeU32(target, offset, length)
    writeU32(target, offset + 4, block)
    writeU16(target, offset + 8, partitionRef)
}

private fun writeShortAd(target: ByteArray, offset: Int, length: Int, block: Int) {
    writeU32(target, offset, length)
    writeU32(target, offset + 4, block)
}

private fun udfDString(name: String): ByteArray =
    byteArrayOf(0x08) + name.encodeToByteArray()

/** File Entry (261): [adType] 0 = short_ad, 1 = long_ad; one allocation descriptor. */
private fun udfFileEntry(
    fileType: Int,
    informationLength: Long,
    adLength: Int,
    adType: Int,
    writeAd: (ByteArray, Int) -> Unit,
): ByteArray {
    val entry = ByteArray(BLOCK)
    writeTag(entry, 0, 261)
    entry[27] = fileType.toByte()
    writeU16(entry, 34, adType)
    writeU64(entry, 56, informationLength)
    writeU32(entry, 168, 0)
    writeU32(entry, 172, adLength)
    writeAd(entry, 176)
    return entry
}

/** Extended File Entry (266), the UDF 2.50 form used by Blu-ray metadata partitions. */
private fun udfExtendedFileEntry(
    fileType: Int,
    informationLength: Long,
    adLength: Int,
    adType: Int,
    writeAd: (ByteArray, Int) -> Unit,
): ByteArray {
    val entry = ByteArray(BLOCK)
    writeTag(entry, 0, 266)
    entry[27] = fileType.toByte()
    writeU16(entry, 34, adType)
    writeU64(entry, 56, informationLength)
    writeU32(entry, 208, 0)
    writeU32(entry, 212, adLength)
    writeAd(entry, 216)
    return entry
}

private fun udfFid(
    target: ByteArray,
    offset: Int,
    name: String,
    icbBlock: Int,
    partitionRef: Int,
    isDirectory: Boolean,
): Int {
    val nameBytes = udfDString(name)
    val length = 38 + nameBytes.size
    val padded = (length + 3) and 3.inv()
    writeTag(target, offset, 257)
    writeU16(target, offset + 16, 1)
    target[offset + 18] = (if (isDirectory) 0x02 else 0x00).toByte()
    target[offset + 19] = nameBytes.size.toByte()
    writeLongAd(target, offset + 20, BLOCK, icbBlock, partitionRef)
    writeU16(target, offset + 36, 0)
    nameBytes.copyInto(target, offset + 38)
    return padded
}

/** VRS + a single type-1 physical partition covering the whole image. */
private fun physicalUdfImage(withStreams: Boolean): ByteArray {
    val image = ByteArray(BLOCK * 700)
    "NSR02".encodeToByteArray().copyInto(image, blockOffset(16) + 1)

    val avdp = blockOffset(256)
    writeTag(image, avdp, 2)
    writeU32(image, avdp + 16, BLOCK * 3)
    writeU32(image, avdp + 20, 257)

    val pd = blockOffset(257)
    writeTag(image, pd, 5)
    writeU16(image, pd + 22, 0)
    writeU32(image, pd + 188, 0)

    val lvd = blockOffset(258)
    writeTag(image, lvd, 6)
    writeU32(image, lvd + 212, BLOCK)
    writeLongAd(image, lvd + 248, BLOCK, 400, 0)
    writeU32(image, lvd + 264, 6)
    writeU32(image, lvd + 268, 1)
    image[lvd + 440] = 1
    image[lvd + 441] = 6
    writeU16(image, lvd + 442, 1)
    writeU16(image, lvd + 444, 0)

    writeTag(image, blockOffset(259), 8)

    val fsd = blockOffset(400)
    writeTag(image, fsd, 256)
    writeLongAd(image, fsd + 400, BLOCK, 401, 0)

    udfFileEntry(4, BLOCK.toLong(), 8, 0) { b, o -> writeShortAd(b, o, BLOCK, 402) }
        .copyInto(image, blockOffset(401))
    udfFileEntry(4, BLOCK.toLong(), 8, 0) { b, o -> writeShortAd(b, o, BLOCK, 404) }
        .copyInto(image, blockOffset(403))
    udfFileEntry(4, BLOCK.toLong(), 8, 0) { b, o -> writeShortAd(b, o, BLOCK, 406) }
        .copyInto(image, blockOffset(405))

    udfFid(image, blockOffset(402), "BDMV", 403, 0, isDirectory = true)
    udfFid(image, blockOffset(404), "STREAM", 405, 0, isDirectory = true)
    if (withStreams) {
        var cursor = blockOffset(406)
        cursor += udfFid(image, cursor, "00000.m2ts", 407, 0, isDirectory = false)
        udfFid(image, cursor, "00001.m2ts", 409, 0, isDirectory = false)
    }

    udfFileEntry(5, PAYLOAD_SMALL.toLong(), 8, 0) { b, o -> writeShortAd(b, o, PAYLOAD_SMALL, 411) }
        .copyInto(image, blockOffset(407))
    udfFileEntry(5, PAYLOAD_LARGE.toLong(), 8, 0) { b, o -> writeShortAd(b, o, PAYLOAD_LARGE, 420) }
        .copyInto(image, blockOffset(409))

    image.fill(0x11.toByte(), blockOffset(411), blockOffset(411) + PAYLOAD_SMALL)
    image.fill(0x22.toByte(), blockOffset(420), blockOffset(420) + PAYLOAD_LARGE)
    return image
}

/**
 * UDF 2.50 shape: partition 0 is a type-2 metadata partition whose metadata file starts at
 * physical block 500, partition 1 is the type-1 physical partition. Directory metadata uses
 * Extended File Entries (266); the `*.m2ts` payload is a `long_ad` in the physical partition.
 */
private fun metadataUdfImage(): ByteArray {
    val image = ByteArray(BLOCK * 700)
    "NSR02".encodeToByteArray().copyInto(image, blockOffset(16) + 1)

    val avdp = blockOffset(256)
    writeTag(image, avdp, 2)
    writeU32(image, avdp + 16, BLOCK * 3)
    writeU32(image, avdp + 20, 257)

    val pd = blockOffset(257)
    writeTag(image, pd, 5)
    writeU16(image, pd + 22, 1)
    writeU32(image, pd + 188, 0)

    val lvd = blockOffset(258)
    writeTag(image, lvd, 6)
    writeU32(image, lvd + 212, BLOCK)
    writeLongAd(image, lvd + 248, BLOCK, 0, 0)
    writeU32(image, lvd + 264, 70)
    writeU32(image, lvd + 268, 2)

    // Partition map 0: type-2 metadata partition, partition number 0, metadata at block 500.
    val metadataMap = lvd + 440
    image[metadataMap] = 2
    image[metadataMap + 1] = 64
    "*UDF Metadata Partition".encodeToByteArray().copyInto(image, metadataMap + 5)
    writeU16(image, metadataMap + 36, 0)
    writeU32(image, metadataMap + 38, 500)

    // Partition map 1: type-1 physical partition, partition number 1, starting at block 0.
    val physicalMap = metadataMap + 64
    image[physicalMap] = 1
    image[physicalMap + 1] = 6
    writeU16(image, physicalMap + 2, 1)
    writeU16(image, physicalMap + 4, 1)

    writeTag(image, blockOffset(259), 8)

    // File Set Descriptor lives in the metadata partition (partition 0, block 0 -> 500).
    val fsd = blockOffset(500)
    writeTag(image, fsd, 256)
    writeLongAd(image, fsd + 400, BLOCK, 1, 0)

    udfExtendedFileEntry(4, BLOCK.toLong(), 8, 0) { b, o -> writeShortAd(b, o, BLOCK, 2) }
        .copyInto(image, blockOffset(501))
    udfExtendedFileEntry(4, BLOCK.toLong(), 8, 0) { b, o -> writeShortAd(b, o, BLOCK, 4) }
        .copyInto(image, blockOffset(503))
    udfExtendedFileEntry(4, BLOCK.toLong(), 8, 0) { b, o -> writeShortAd(b, o, BLOCK, 6) }
        .copyInto(image, blockOffset(505))

    udfFid(image, blockOffset(502), "BDMV", 3, 0, isDirectory = true)
    udfFid(image, blockOffset(504), "STREAM", 5, 0, isDirectory = true)
    var cursor = blockOffset(506)
    cursor += udfFid(image, cursor, "00000.m2ts", 7, 0, isDirectory = false)
    udfFid(image, cursor, "00001.m2ts", 8, 0, isDirectory = false)

    // Stream file entries live in the metadata partition, their payloads in partition 1.
    udfExtendedFileEntry(5, PAYLOAD_SMALL.toLong(), 16, 1) { b, o ->
        writeLongAd(b, o, PAYLOAD_SMALL, 610, 1)
    }.copyInto(image, blockOffset(507))
    udfExtendedFileEntry(5, PAYLOAD_LARGE.toLong(), 16, 1) { b, o ->
        writeLongAd(b, o, PAYLOAD_LARGE, 600, 1)
    }.copyInto(image, blockOffset(508))

    image.fill(0x11.toByte(), blockOffset(610), blockOffset(610) + PAYLOAD_SMALL)
    image.fill(0x22.toByte(), blockOffset(600), blockOffset(600) + PAYLOAD_LARGE)
    return image
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

private fun writeU64(bytes: ByteArray, offset: Int, value: Long) {
    for (index in 0 until 8) {
        bytes[offset + index] = ((value shr (8 * index)) and 0xff).toByte()
    }
}
