package com.nuvio.app.features.telegram

import kotlin.math.min

/** Largest single read issued against the backing file; keeps TDLib requests bounded. */
private const val ISO_MAX_CHUNK = 65_536

/** ISO9660 logical sector size, for file offsets. */
private const val ISO9660_SECTOR = 2_048L

/** The same sector size, for arithmetic on in-memory directory buffers. */
private const val ISO9660_SECTOR_BYTES = 2_048

private const val ISO9660_RESERVED_SECTORS = 16

/** Byte offset where the ISO9660 volume descriptors begin. */
private const val ISO9660_SYSTEM_AREA_SIZE = ISO9660_SECTOR * ISO9660_RESERVED_SECTORS
private const val ISO9660_MAX_DEPTH = 4
private const val ISO9660_MAX_ENTRIES = 64

/**
 * Cap on a single directory read. A Blu-ray `BDMV/STREAM` directory is a few KB; the
 * largest DVD `VIDEO_TS` is well under this. Keeps a malformed extent from causing an
 * enormous allocation.
 */
private const val ISO_DIRECTORY_MAX_BYTES = 262_144

/**
 * Sector holding the UDF Volume Recognition Sequence. It lies in the same system-area
 * sector ISO9660 uses, so `BEA01`/`NSR02` and `CD001` are found at the same offset.
 */
private const val UDF_VRS_SECTOR = 16L
private const val UDF_VRS_SCAN_SECTORS = 8

/**
 * A playable payload found inside a disc image.
 *
 * [offset] is absolute within the (possibly reassembled) image, so a ZIP-style
 * `innerOffset`/`innerSize` pair can be handed straight to the platform client.
 */
data class TelegramIsoEntry(
    val name: String,
    val offset: Long,
    val size: Long,
)

/**
 * Scans a disc image for a playable payload.
 *
 * Blu-ray and DVD layouts are both probed:
 * - `BDMV/STREAM/&#42;.m2ts`
 * - `VIDEO_TS/VTS_&#42;.VOB`
 *
 * Blu-ray menus, `PLAYLIST`, and `CERTIFICATE` payloads are ignored so the longest
 * real stream wins.
 *
 * When [season] and [episode] are supplied the closest matching title is preferred;
 * otherwise the largest payload wins. Blu-ray images that carry only a UDF volume are
 * resolved by walking the UDF tree for `BDMV/STREAM/&#42;.m2ts` as well. Returns `null`
 * when the image has no readable disc tree — an encrypted retail disc, an audio ISO, or
 * a file that is not a disc image at all.
 *
 * @param read random-access reader over the reassembled image. May return a short
 *   or `null` read; this function never throws and gives up gracefully.
 */
fun findTelegramIsoEntry(
    imageSize: Long,
    season: Int? = null,
    episode: Int? = null,
    read: (offset: Long, length: Int) -> ByteArray?,
): TelegramIsoEntry? {
    if (imageSize < ISO9660_SYSTEM_AREA_SIZE + ISO9660_SECTOR) return null

    val pvd = readIsoChunk(ISO9660_SYSTEM_AREA_SIZE, ISO9660_SECTOR.toInt(), imageSize, read)
    if (pvd != null && pvd.size >= 7 && pvd.decodeToString(1, 6) == "CD001") {
        findIso9660Entry(pvd, imageSize, season, episode, read)?.let { return it }
    }

    //
    // No readable ISO9660 disc tree. A UDF-only Blu-ray keeps its titles in a `BDMV/STREAM`
    // directory, so resolve the largest `*.m2ts` extent and expose that byte range instead
    // of the raw image. Anything still unresolved (an audio ISO, an encrypted disc, a file
    // that is not a disc image) returns null, which callers treat as "no inner payload".
    if (hasUdfAnchor(imageSize, read)) {
        findUdfBluRayEntry(imageSize, season, episode, read)?.let { return it }
    }
    return null
}

/** True when the image carries a UDF Volume Recognition Sequence. */
internal fun hasUdfAnchor(
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): Boolean {
    val vrs = readIsoChunk(
        offset = UDF_VRS_SECTOR * ISO9660_SECTOR,
        length = UDF_VRS_SCAN_SECTORS * ISO9660_SECTOR_BYTES,
        imageSize = imageSize,
        read = read,
    ) ?: return false
    var sector = 0
    while ((sector + 1) * ISO9660_SECTOR_BYTES <= vrs.size) {
        val start = sector * ISO9660_SECTOR_BYTES
        when (vrs.decodeToString(start + 1, start + 6)) {
            "NSR02", "NSR03" -> return true
            // TEA01 terminates the sequence; nothing after it is a UDF descriptor.
            "TEA01" -> return false
        }
        sector++
    }
    return false
}

// ---------------------------------------------------------------------------
// UDF (ECMA-167) reader
//
// Blu-ray images carry only a UDF volume, so the ISO9660 walk finds nothing. The chain
// followed here mirrors `findIso9660Entry`: AVDP -> main volume descriptor sequence ->
// Partition/Logical-Volume descriptors -> File Set Descriptor -> root ICB -> `BDMV` ->
// `STREAM` -> the largest `*.m2ts` extent.
// ---------------------------------------------------------------------------

/** UDF logical sector size, used for descriptor locations. */
private const val UDF_SECTOR = 2_048L

/** Sector holding the Anchor Volume Descriptor Pointer. */
private const val UDF_ANCHOR_SECTOR = 256L

private const val UDF_DESCRIPTOR_MAX_BYTES = 262_144
private const val UDF_MAX_DESCRIPTORS = 64
private const val UDF_MAX_DEPTH = 4
private const val UDF_MAX_ENTRIES = 64
private const val UDF_MIN_BLOCK_SIZE = 512
private const val UDF_MAX_BLOCK_SIZE = 32_768

private const val UDF_TAG_ANCHOR = 2
private const val UDF_TAG_PARTITION = 5
private const val UDF_TAG_LOGICAL_VOLUME = 6
private const val UDF_TAG_TERMINATING = 8
private const val UDF_TAG_FILE_SET = 256
private const val UDF_TAG_FILE_IDENTIFIER = 257
private const val UDF_TAG_FILE_ENTRY = 261
private const val UDF_TAG_EXTENDED_FILE_ENTRY = 266

private const val UDF_ICB_DIRECTORY = 4

private const val UDF_AD_SHORT = 0
private const val UDF_AD_LONG = 1
private const val UDF_AD_EMBEDDED = 3

private const val UDF_METADATA_MAP_ID = "*UDF Metadata Partition"

private data class UdfPartitionMap(
    val type: Int,
    val partitionNumber: Int,
    /** Physical block of the metadata file, or `-1` when the map is not a metadata partition. */
    val metadataFileLocation: Long,
)

private data class UdfLongAd(
    val length: Long,
    val block: Long,
    val partitionRef: Int,
)

private class UdfIcb(val partitionRef: Int, val block: Long)

private class UdfVolume(
    val blockSize: Int,
    val partitionStarts: Map<Int, Long>,
    val partitionMaps: Map<Int, UdfPartitionMap>,
    val fileSet: UdfLongAd,
) {
    /** Absolute byte offset of [block] in partition [partitionRef], or `null` if unmapped. */
    fun offsetFor(partitionRef: Int, block: Long): Long? {
        val map = partitionMaps[partitionRef] ?: return null
        if (block < 0) return null
        val physical = when (map.type) {
            // Physical (type 1) partition: blocks are relative to its Partition Descriptor.
            1 -> (partitionStarts[map.partitionNumber] ?: return null) + block
            // Metadata (type 2) partition: blocks live inside the contiguous metadata file.
            2 -> {
                if (map.metadataFileLocation < 0) return null
                map.metadataFileLocation + block
            }
            else -> return null
        }
        if (physical < 0) return null
        return physical * blockSize
    }
}

private fun udfTagId(bytes: ByteArray, offset: Int): Int = u16(bytes, offset)

private fun readUdfLongAd(bytes: ByteArray, offset: Int): UdfLongAd? {
    val length = u32(bytes, offset)
    val block = u32(bytes, offset + 4)
    val partitionRef = u16(bytes, offset + 8)
    if (length < 0 || block < 0 || partitionRef < 0) return null
    return UdfLongAd(length and 0x3FFF_FFFFL, block, partitionRef)
}

private fun udfMapIdentifier(bytes: ByteArray, start: Int, length: Int): String {
    if (start < 0 || start + length > bytes.size) return ""
    val end = (start until start + length).firstOrNull { bytes[it] == 0.toByte() } ?: (start + length)
    return bytes.decodeToString(start, end)
}

private fun parseUdfPartitionMaps(lvd: ByteArray): Map<Int, UdfPartitionMap>? {
    val count = u32(lvd, 268)
    val tableLength = u32(lvd, 264)
    if (count < 0 || count > UDF_MAX_DESCRIPTORS || tableLength < 0) return null
    val available = (lvd.size - 440).coerceAtLeast(0)
    val limit = 440 + tableLength.coerceAtMost(available.toLong()).toInt()
    val maps = LinkedHashMap<Int, UdfPartitionMap>()
    var cursor = 440
    var index = 0
    while (index < count.toInt() && cursor + 2 <= lvd.size && cursor < limit) {
        val type = lvd[cursor].toInt() and 0xFF
        val length = lvd[cursor + 1].toInt() and 0xFF
        if (length < 2) break
        when (type) {
            1 -> {
                val number = u16(lvd, cursor + 4)
                if (number >= 0) maps[number] = UdfPartitionMap(1, number, -1L)
            }
            2 -> {
                val number = u16(lvd, cursor + 36)
                if (number >= 0) {
                    val isMetadata = udfMapIdentifier(lvd, cursor + 5, 23) == UDF_METADATA_MAP_ID
                    maps[number] = UdfPartitionMap(
                        type = 2,
                        partitionNumber = number,
                        metadataFileLocation = if (isMetadata) u32(lvd, cursor + 38) else -1L,
                    )
                }
            }
            // Sparable and other virtual map subtypes are not resolvable here.
        }
        cursor += length
        index++
    }
    return maps
}

private fun readUdfVolume(
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): UdfVolume? {
    val avdp = readIsoChunk(
        offset = UDF_ANCHOR_SECTOR * UDF_SECTOR,
        length = UDF_SECTOR.toInt(),
        imageSize = imageSize,
        read = read,
    ) ?: return null
    if (udfTagId(avdp, 0) != UDF_TAG_ANCHOR) return null

    val sequenceLength = (u32(avdp, 16) and 0x3FFF_FFFFL)
        .coerceAtMost(UDF_DESCRIPTOR_MAX_BYTES.toLong())
    val sequenceLocation = u32(avdp, 20)
    if (sequenceLength <= 0 || sequenceLocation <= 0) return null

    val partitionStarts = mutableMapOf<Int, Long>()
    var blockSize = 0
    var partitionMaps: Map<Int, UdfPartitionMap>? = null
    var fileSet: UdfLongAd? = null

    var offset = sequenceLocation * UDF_SECTOR
    val end = offset + sequenceLength
    var descriptors = 0
    while (offset < end && descriptors < UDF_MAX_DESCRIPTORS) {
        val descriptor = readIsoChunk(offset, UDF_SECTOR.toInt(), imageSize, read) ?: break
        descriptors++
        when (udfTagId(descriptor, 0)) {
            UDF_TAG_PARTITION -> {
                val number = u16(descriptor, 22)
                val start = u32(descriptor, 188)
                if (number >= 0 && start >= 0) partitionStarts[number] = start
            }
            UDF_TAG_LOGICAL_VOLUME -> {
                val parsedBlockSize = u32(descriptor, 212)
                if (parsedBlockSize in UDF_MIN_BLOCK_SIZE.toLong()..UDF_MAX_BLOCK_SIZE.toLong()) {
                    blockSize = parsedBlockSize.toInt()
                }
                partitionMaps = parseUdfPartitionMaps(descriptor)
                readUdfLongAd(descriptor, 248)?.let { fileSet = it }
            }
            UDF_TAG_TERMINATING -> break
        }
        offset += UDF_SECTOR
    }

    val maps = partitionMaps ?: return null
    val fsd = fileSet ?: return null
    if (blockSize == 0 || maps.isEmpty()) return null
    return UdfVolume(blockSize, partitionStarts, maps, fsd)
}

private fun UdfVolume.fileEntryData(
    icb: UdfIcb,
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): ByteArray? {
    val offset = offsetFor(icb.partitionRef, icb.block) ?: return null
    val entry = readIsoChunk(offset, blockSize, imageSize, read) ?: return null
    val tag = udfTagId(entry, 0)
    if (tag != UDF_TAG_FILE_ENTRY && tag != UDF_TAG_EXTENDED_FILE_ENTRY) return null
    return entry
}

private fun UdfVolume.directoryBytes(
    icb: UdfIcb,
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): ByteArray? {
    val entry = fileEntryData(icb, imageSize, read) ?: return null
    val extended = udfTagId(entry, 0) == UDF_TAG_EXTENDED_FILE_ENTRY
    if ((entry[27].toInt() and 0xFF) != UDF_ICB_DIRECTORY) return null

    val lengthOfExtendedAttributes = u32(entry, if (extended) 208 else 168)
    val lengthOfAllocationDescriptors = u32(entry, if (extended) 212 else 172)
    if (lengthOfExtendedAttributes < 0 || lengthOfAllocationDescriptors < 0) return null
    val adStart = (if (extended) 216 else 176) + lengthOfExtendedAttributes.toInt()
    if (adStart >= entry.size) return null

    val adType = u16(entry, 34)
    if (adType < 0) return null
    if (adType and 0x7 == UDF_AD_EMBEDDED) {
        val end = (adStart + lengthOfAllocationDescriptors.toInt()).coerceAtMost(entry.size)
        if (adStart >= end) return null
        return entry.copyOfRange(adStart, end)
    }
    val adSize = when (adType and 0x7) {
        UDF_AD_SHORT -> 8
        UDF_AD_LONG -> 16
        else -> return null
    }

    val informationLength = u64(entry, 56)
    val wanted = if (informationLength > 0) informationLength else lengthOfAllocationDescriptors
    val limit = wanted.coerceAtMost(UDF_DESCRIPTOR_MAX_BYTES.toLong()).toInt()
    if (limit <= 0) return null

    val out = ByteArray(limit)
    var copied = 0
    var adOffset = adStart
    val descriptorCount = lengthOfAllocationDescriptors.toInt() / adSize
    var index = 0
    while (index < descriptorCount && copied < limit && adOffset + adSize <= entry.size) {
        val extentLength = u32(entry, adOffset) and 0x3FFF_FFFFL
        val block = u32(entry, adOffset + 4)
        val partitionRef = if ((adType and 0x7) == UDF_AD_LONG) u16(entry, adOffset + 8) else icb.partitionRef
        adOffset += adSize
        index++
        if (extentLength <= 0 || block < 0 || partitionRef < 0) continue
        val extentOffset = offsetFor(partitionRef, block) ?: continue
        val take = min(extentLength, (limit - copied).toLong()).toInt()
        if (take <= 0) continue
        val bytes = readIsoChunk(extentOffset, take, imageSize, read) ?: continue
        val toCopy = min(bytes.size, take)
        bytes.copyInto(out, copied, 0, toCopy)
        copied += toCopy
    }
    if (copied <= 0) return null
    return out.copyOf(copied)
}

private fun udfName(bytes: ByteArray, start: Int, length: Int): String {
    if (length <= 0) return ""
    return when (bytes[start].toInt() and 0xFF) {
        0x08 -> bytes.decodeToString(start + 1, start + length).trim()
        0x10 -> {
            val builder = StringBuilder()
            var index = start + 1
            while (index + 2 <= start + length) {
                val code = ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
                builder.append(code.toChar())
                index += 2
            }
            builder.toString().trim()
        }
        else -> bytes.decodeToString(start, start + length).trim()
    }
}

private inline fun walkUdfDirectory(
    bytes: ByteArray,
    onEntry: (name: String, isDirectory: Boolean, icb: UdfIcb) -> Unit,
) {
    var cursor = 0
    while (cursor + 38 <= bytes.size) {
        if (udfTagId(bytes, cursor) != UDF_TAG_FILE_IDENTIFIER) return
        val version = u16(bytes, cursor + 16)
        val characteristics = bytes[cursor + 18].toInt() and 0xFF
        val nameLength = bytes[cursor + 19].toInt() and 0xFF
        val implementationUseLength = u16(bytes, cursor + 36)
        if (version != 1 || implementationUseLength < 0) return
        val nameStart = cursor + 38 + implementationUseLength
        val nameEnd = nameStart + nameLength
        if (nameEnd > bytes.size) return
        val isParent = characteristics and 0x08 != 0
        if (!isParent && nameLength > 0) {
            val name = udfName(bytes, nameStart, nameLength)
            val icb = readUdfLongAd(bytes, cursor + 20)
            if (name.isNotEmpty() && icb != null) {
                onEntry(name, characteristics and 0x02 != 0, UdfIcb(icb.partitionRef, icb.block))
            }
        }
        val recordLength = 38 + implementationUseLength + nameLength
        val padded = (recordLength + 3) and 3.inv()
        if (padded <= 0) return
        cursor += padded
    }
}

private fun UdfVolume.childDirectories(
    parent: UdfIcb,
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): List<UdfIcb> {
    val bytes = directoryBytes(parent, imageSize, read) ?: return emptyList()
    val result = mutableListOf<UdfIcb>()
    walkUdfDirectory(bytes) { _, isDirectory, icb ->
        if (isDirectory) result += icb
    }
    return result
}

private fun UdfVolume.findChild(
    name: String,
    parent: UdfIcb,
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): UdfIcb? {
    val bytes = directoryBytes(parent, imageSize, read) ?: return null
    var found: UdfIcb? = null
    walkUdfDirectory(bytes) { childName, _, icb ->
        if (found == null && childName.equals(name, ignoreCase = true)) found = icb
    }
    return found
}

private fun UdfVolume.collectUdfStreams(
    directoryIcb: UdfIcb,
    depth: Int,
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
    out: MutableList<TelegramIsoEntry>,
) {
    if (depth > UDF_MAX_DEPTH || out.size >= UDF_MAX_ENTRIES) return
    val bytes = directoryBytes(directoryIcb, imageSize, read) ?: return
    walkUdfDirectory(bytes) { name, isDirectory, icb ->
        if (out.size >= UDF_MAX_ENTRIES) return@walkUdfDirectory
        if (isDirectory) {
            collectUdfStreams(icb, depth + 1, imageSize, read, out)
            return@walkUdfDirectory
        }
        if (!isTelegramDiscStreamFileName(name)) return@walkUdfDirectory
        val entry = fileEntryData(icb, imageSize, read) ?: return@walkUdfDirectory
        val size = u64(entry, 56)
        if (size <= 0L) return@walkUdfDirectory
        val extended = udfTagId(entry, 0) == UDF_TAG_EXTENDED_FILE_ENTRY
        val adStartOffset = u32(entry, if (extended) 208 else 168)
        if (adStartOffset < 0) return@walkUdfDirectory
        val adStart = (if (extended) 216 else 176) + adStartOffset.toInt()
        val adType = u16(entry, 34)
        if (adType < 0) return@walkUdfDirectory
        val extentOffset = when (adType and 0x7) {
            UDF_AD_SHORT -> u32(entry, adStart + 4).takeIf { it >= 0 }?.let { offsetFor(icb.partitionRef, it) }
            UDF_AD_LONG -> readUdfLongAd(entry, adStart)?.let { offsetFor(it.partitionRef, it.block) }
            else -> null
        } ?: return@walkUdfDirectory
        if (extentOffset + size <= imageSize) {
            out += TelegramIsoEntry(name = name, offset = extentOffset, size = size)
        }
    }
}

private fun findUdfBluRayEntry(
    imageSize: Long,
    season: Int?,
    episode: Int?,
    read: (offset: Long, length: Int) -> ByteArray?,
): TelegramIsoEntry? {
    val volume = readUdfVolume(imageSize, read) ?: return null

    val fsdOffset = volume.offsetFor(volume.fileSet.partitionRef, volume.fileSet.block) ?: return null
    val fsd = readIsoChunk(fsdOffset, volume.blockSize, imageSize, read) ?: return null
    if (udfTagId(fsd, 0) != UDF_TAG_FILE_SET) return null
    val root = readUdfLongAd(fsd, 400) ?: return null
    val rootIcb = UdfIcb(root.partitionRef, root.block)

    val bdmvIcb = volume.findChild("BDMV", rootIcb, imageSize, read)
        ?: volume.childDirectories(rootIcb, imageSize, read)
            .firstNotNullOfOrNull { volume.findChild("BDMV", it, imageSize, read) }
        ?: return null
    val streamIcb = volume.findChild("STREAM", bdmvIcb, imageSize, read) ?: return null

    val candidates = mutableListOf<TelegramIsoEntry>()
    volume.collectUdfStreams(streamIcb, depth = 0, imageSize = imageSize, read = read, out = candidates)
    if (candidates.isEmpty()) return null
    return pickIsoEntry(candidates, season, episode)
}

private fun findIso9660Entry(
    pvd: ByteArray,
    imageSize: Long,
    season: Int?,
    episode: Int?,
    read: (offset: Long, length: Int) -> ByteArray?,
): TelegramIsoEntry? {
    val rootLength = u32(pvd, 166)
    val rootExtent = u32(pvd, 158)
    if (rootExtent <= 0 || rootLength < 34) return null

    val candidates = mutableListOf<TelegramIsoEntry>()
    collectIso9660Streams(rootExtent, rootLength, imageSize, read, depth = 0, candidates)

    if (candidates.isEmpty()) {
        // Some DVD images keep VIDEO_TS inside a sub-directory rather than at the root.
        val dirs = mutableListOf<IsoDirectory>()
        collectIso9660Directories(rootExtent, rootLength, imageSize, read, depth = 0, dirs)
        for (dir in dirs) {
            if (dir.name.none { it.isLetterOrDigit() }) continue
            collectIso9660Streams(dir.extent, dir.size, imageSize, read, depth = 1, candidates)
        }
    }
    if (candidates.isEmpty()) return null
    return pickIsoEntry(candidates, season, episode)
}

private class IsoDirectory(val name: String, val extent: Long, val size: Long)

private fun collectIso9660Directories(
    extent: Long,
    size: Long,
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
    depth: Int,
    out: MutableList<IsoDirectory>,
) {
    if (depth > ISO9660_MAX_DEPTH) return
    val bytes = readIsoChunk(
        offset = extent * ISO9660_SECTOR + ISO9660_SYSTEM_AREA_SIZE,
        length = size.coerceAtMost(ISO_DIRECTORY_MAX_BYTES.toLong()).toInt(),
        imageSize = imageSize,
        read = read,
    ) ?: return
    walkIsoDirectory(bytes) { record ->
        if (record.isDirectory && !record.isDot) {
            out += IsoDirectory(record.shortName, record.extent, record.size)
        }
    }
}

private fun collectIso9660Streams(
    extent: Long,
    size: Long,
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
    depth: Int,
    out: MutableList<TelegramIsoEntry>,
) {
    if (depth > ISO9660_MAX_DEPTH || out.size >= ISO9660_MAX_ENTRIES) return
    val bytes = readIsoChunk(
        offset = extent * ISO9660_SECTOR + ISO9660_SYSTEM_AREA_SIZE,
        length = size.coerceAtMost(ISO_DIRECTORY_MAX_BYTES.toLong()).toInt(),
        imageSize = imageSize,
        read = read,
    ) ?: return
    walkIsoDirectory(bytes) { record ->
        when {
            record.isDirectory && !record.isDot -> collectIso9660Streams(
                extent = record.extent,
                size = record.size,
                imageSize = imageSize,
                read = read,
                depth = depth + 1,
                out = out,
            )
            !record.isDirectory -> {
                val payloadOffset = record.extent * ISO9660_SECTOR + ISO9660_SYSTEM_AREA_SIZE
                if (isDiscStreamPayload(record.shortName, record.size, imageSize, payloadOffset)) {
                    out += TelegramIsoEntry(
                        name = record.shortName,
                        offset = payloadOffset,
                        size = record.size,
                    )
                }
            }
        }
    }
}

private inline fun walkIsoDirectory(bytes: ByteArray, onRecord: (IsoRecord) -> Unit) {
    var cursor = 0
    while (cursor + 34 <= bytes.size) {
        val recordLength = bytes[cursor].toInt() and 0xFF
        if (recordLength == 0) {
            // Records never straddle a sector boundary; skip to the next one.
            val sectorOffset = cursor % ISO9660_SECTOR_BYTES
            cursor += if (sectorOffset == 0) ISO9660_SECTOR_BYTES else ISO9660_SECTOR_BYTES - sectorOffset
            continue
        }
        if (cursor + recordLength > bytes.size) return
        val record = parseIsoRecord(bytes, cursor, recordLength)
        if (record != null) onRecord(record)
        cursor += recordLength
    }
}

private class IsoRecord(
    val shortName: String,
    val extent: Long,
    val size: Long,
    val isDirectory: Boolean,
    val isDot: Boolean,
)

private fun parseIsoRecord(bytes: ByteArray, offset: Int, recordLength: Int): IsoRecord? {
    if (recordLength < 34) return null
    val extent = u32(bytes, offset + 2)
    val size = u32(bytes, offset + 10)
    val flags = bytes[offset + 25].toInt() and 0xFF
    val nameLen = bytes[offset + 32].toInt() and 0xFF
    if (33 + nameLen > recordLength) return null
    val rawName = bytes.decodeToString(offset + 33, offset + 33 + nameLen)
    val isDirectory = flags and 0x02 != 0
    val isDot = nameLen == 1 && (rawName[0] == '\u0000' || rawName[0] == '\u0001')
    return IsoRecord(
        shortName = if (isDot) rawName else normalizeIsoName(rawName),
        extent = extent,
        size = size,
        isDirectory = isDirectory,
        isDot = isDot,
    )
}

/** Strips the ISO9660 `;1` version suffix and a trailing dot. */
private fun normalizeIsoName(raw: String): String =
    raw.substringBefore(';').trimEnd('.').trim()

private fun isDiscStreamPayload(name: String, size: Long, imageSize: Long, offset: Long): Boolean {
    if (!isTelegramDiscStreamFileName(name)) return false
    if (size <= 0L || offset + size > imageSize) return false
    // Menus and tiny VOB fragments are not worth surfacing; features are far larger.
    if (name.startsWith("VTS_", ignoreCase = true) && size < MIN_TITLE_BYTES) return false
    return true
}

/** Menus and tiny VOB fragments are not worth surfacing; features are far larger. */
private const val MIN_TITLE_BYTES = 32L * 1024L * 1024L

private fun pickIsoEntry(
    candidates: List<TelegramIsoEntry>,
    season: Int?,
    episode: Int?,
): TelegramIsoEntry? {
    if (candidates.isEmpty()) return null
    if (episode != null) {
        val wanted = candidates.filter { telegramInnerMatchesEpisode(it.name, season, episode) }
        if (wanted.isNotEmpty()) return wanted.maxBy { it.size }
    }
    return candidates.maxBy { it.size }
}

private fun readIsoChunk(
    offset: Long,
    length: Int,
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): ByteArray? {
    if (offset < 0 || offset >= imageSize) return null
    val safeLength = min(length.toLong(), imageSize - offset).toInt()
    if (safeLength <= 0) return null
    val buffer = ByteArray(safeLength)
    var copied = 0
    while (copied < safeLength) {
        val requestOffset = offset + copied
        val requestLength = min(ISO_MAX_CHUNK, safeLength - copied)
        val chunk = read(requestOffset, requestLength) ?: return if (copied == 0) null else buffer.copyOf(copied)
        if (chunk.isEmpty()) return if (copied == 0) null else buffer.copyOf(copied)
        val toCopy = min(chunk.size, safeLength - copied)
        chunk.copyInto(buffer, copied, 0, toCopy)
        copied += toCopy
    }
    return buffer
}

private fun u32(bytes: ByteArray, offset: Int): Long {
    if (offset < 0 || offset + 4 > bytes.size) return -1L
    // Both-endian field: the little-endian half occupies the first four bytes.
    return (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

private fun u16(bytes: ByteArray, offset: Int): Int {
    if (offset < 0 || offset + 2 > bytes.size) return -1
    return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

private fun u64(bytes: ByteArray, offset: Int): Long {
    if (offset < 0 || offset + 8 > bytes.size) return -1L
    var value = 0L
    for (index in 0 until 8) {
        value = value or ((bytes[offset + index].toLong() and 0xFF) shl (8 * index))
    }
    return value
}
