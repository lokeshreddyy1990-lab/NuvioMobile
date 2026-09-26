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

/** Sector holding the UDF Anchor Volume Descriptor Pointer. */
private const val UDF_ANCHOR_SECTOR = 256L

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
 * otherwise the largest payload wins. Returns `null` when the image has no readable disc
 * tree — a UDF-only Blu-ray layout, an encrypted retail disc, an audio ISO, or a file
 * that is not a disc image at all.
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

    val pvd = readIsoChunk(ISO9660_SYSTEM_AREA_SIZE, ISO9660_SECTOR.toInt(), imageSize, read) ?: return null
    if (pvd.size >= 7 && pvd.decodeToString(1, 6) == "CD001") {
        findIso9660Entry(pvd, imageSize, season, episode, read)?.let { return it }
    }

    //
    // Not a readable ISO9660 disc tree. Callers treat `null` as "no inner payload", which
    // is the correct outcome for UDF-only Blu-ray images as well.
    return null
}

/** True when the image carries a UDF Volume Recognition Sequence. */
internal fun hasUdfAnchor(
    imageSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): Boolean {
    val anchor = readIsoChunk(UDF_ANCHOR_SECTOR * ISO9660_SECTOR, 64, imageSize, read) ?: return false
    return anchor.size >= 5 && anchor.decodeToString(1, 5) == "NSR0"
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
    if (offset + 4 > bytes.size) return -1L
    // Both-endian field: the little-endian half occupies the first four bytes.
    return (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
