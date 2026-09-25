package com.nuvio.app.features.telegram

const val ZIP_STORED = 0

data class TelegramZipEntry(
    val name: String,
    val dataOffset: Long,
    val size: Long,
    val method: Int,
)

fun parseTelegramZipEntries(zip: ByteArray): List<TelegramZipEntry> =
    parseTelegramZipEntries(zip.size.toLong()) { offset, length ->
        val start = offset.toInt().coerceIn(0, zip.size)
        val end = (start + length).coerceAtMost(zip.size)
        zip.copyOfRange(start, end)
    }

fun parseTelegramZipEntries(
    zipSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): List<TelegramZipEntry> {
    if (zipSize < 22) return emptyList()
    val tailLen = minOf(262_144L, zipSize).toInt()
    val tail = read(zipSize - tailLen, tailLen) ?: return emptyList()
    val cd = locateCentralDirectory(tail, zipSize - tailLen, zipSize) ?: return emptyList()
    val cdBytes = if (cd.offset >= zipSize - tailLen) {
        val rel = (cd.offset - (zipSize - tailLen)).toInt()
        tail.copyOfRange(rel, minOf(rel + cd.size.toInt(), tail.size))
    } else {
        read(cd.offset, cd.size.toInt().coerceAtLeast(46)) ?: return emptyList()
    }
    return parseCentralDirectoryRecords(cdBytes, cd.entryCount, zipSize, read)
}

private data class CentralDirectoryLocation(
    val offset: Long,
    val size: Long,
    val entryCount: Int,
)

private fun locateCentralDirectory(tail: ByteArray, tailBase: Long, zipSize: Long): CentralDirectoryLocation? {
    val eocd = lastIndexOf(tail, byteArrayOf(0x50, 0x4b, 0x05, 0x06)) ?: return null
    var cdOffset = u32(tail, eocd + 16)
    var cdSize = u32(tail, eocd + 12)
    var entries = u16(tail, eocd + 10)
    val z64loc = lastIndexOf(tail, byteArrayOf(0x50, 0x4b, 0x06, 0x07))
    if (cdOffset == 0xFFFFFFFFL && z64loc != null) {
        val z64EocdOff = u64(tail, z64loc + 8)
        val rel = (z64EocdOff - tailBase).toInt()
        if (rel in 0 until tail.size - 56 && tail.copyOfRange(rel, rel + 4).contentEquals(byteArrayOf(0x50, 0x4b, 0x06, 0x06))) {
            entries = u64(tail, rel + 32).toInt()
            cdSize = u64(tail, rel + 40)
            cdOffset = u64(tail, rel + 48)
        }
    }
    if (cdOffset < 0 || cdOffset >= zipSize) return null
    return CentralDirectoryLocation(offset = cdOffset, size = if (cdSize > 0) cdSize else zipSize - cdOffset, entryCount = entries)
}

private fun parseCentralDirectoryRecords(
    bytes: ByteArray,
    entryCount: Int,
    zipSize: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): List<TelegramZipEntry> {
    val entries = ArrayList<TelegramZipEntry>(entryCount.coerceAtLeast(0))
    var offset = 0
    var remaining = if (entryCount > 0) entryCount else Int.MAX_VALUE
    while (remaining > 0 && offset + 46 <= bytes.size && bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4b.toByte() &&
        bytes[offset + 2] == 0x01.toByte() && bytes[offset + 3] == 0x02.toByte()
    ) {
        val method = u16(bytes, offset + 10)
        var comp = u32(bytes, offset + 20)
        var uncomp = u32(bytes, offset + 24)
        val nameLen = u16(bytes, offset + 28)
        val extraLen = u16(bytes, offset + 30)
        val commentLen = u16(bytes, offset + 32)
        var localOffset = u32(bytes, offset + 42)
        val nameStart = offset + 46
        val nameEnd = nameStart + nameLen
        if (nameEnd > bytes.size) break
        val name = bytes.decodeToString(nameStart, nameEnd)
        val extra = bytes.copyOfRange(nameEnd, (nameEnd + extraLen).coerceAtMost(bytes.size))
        if (uncomp == 0xFFFFFFFFL || comp == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
            val resolved = zip64Sizes(extra, uncomp, comp, needOffset = true, offset = localOffset)
            uncomp = resolved.first
            comp = resolved.second
            localOffset = resolved.third
        }
        val header = read(localOffset, 4096)?.let(::parseLocalHeader)
        val dataOffset = if (header != null) localOffset + header.dataOffset else localOffset + 30 + nameLen + extraLen
        if (dataOffset + uncomp <= zipSize) {
            entries += TelegramZipEntry(
                name = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { name },
                dataOffset = dataOffset,
                size = uncomp,
                method = method,
            )
        }
        offset = nameEnd + extraLen + commentLen
        remaining -= 1
    }
    return entries
}

internal data class ZipLocalHeader(
    val method: Int,
    val name: String,
    val dataOffset: Long,
    val size: Long,
    val hasDescriptor: Boolean,
)

internal fun parseLocalHeader(buf: ByteArray): ZipLocalHeader? {
    if (buf.size < 30 || buf[0] != 0x50.toByte() || buf[1] != 0x4b.toByte() || buf[2] != 0x03.toByte() || buf[3] != 0x04.toByte()) {
        return null
    }
    val flag = u16(buf, 6)
    val method = u16(buf, 8)
    var comp = u32(buf, 18)
    var uncomp = u32(buf, 22)
    val nameLen = u16(buf, 26)
    val extraLen = u16(buf, 28)
    if (30 + nameLen > buf.size) return null
    val name = buf.decodeToString(30, 30 + nameLen)
    val extraEnd = (30 + nameLen + extraLen).coerceAtMost(buf.size)
    val extra = buf.copyOfRange(30 + nameLen, extraEnd)
    if (uncomp == 0xFFFFFFFFL || comp == 0xFFFFFFFFL) {
        val resolved = zip64Sizes(extra, uncomp, comp)
        uncomp = resolved.first
        comp = resolved.second
    }
    return ZipLocalHeader(
        method = method,
        name = name,
        dataOffset = 30L + nameLen + extraLen,
        size = uncomp,
        hasDescriptor = flag and 0x08 != 0,
    )
}

private fun zip64Sizes(
    extra: ByteArray,
    uncomp: Long,
    comp: Long,
    needOffset: Boolean = false,
    offset: Long = 0,
): Triple<Long, Long, Long> {
    var i = 0
    var outUncomp = uncomp
    var outComp = comp
    var outOffset = offset
    while (i + 4 <= extra.size) {
        val hid = u16(extra, i)
        val hsz = u16(extra, i + 2)
        val body = extra.copyOfRange(i + 4, (i + 4 + hsz).coerceAtMost(extra.size))
        if (hid == 1) {
            var k = 0
            fun next(): Long {
                val value = u64(body, k)
                k += 8
                return value
            }
            if (outUncomp == 0xFFFFFFFFL && k + 8 <= body.size) outUncomp = next()
            if (outComp == 0xFFFFFFFFL && k + 8 <= body.size) outComp = next()
            if (needOffset && outOffset == 0xFFFFFFFFL && k + 8 <= body.size) outOffset = next()
            break
        }
        i += 4 + hsz
    }
    return Triple(outUncomp, outComp, outOffset)
}

private fun lastIndexOf(haystack: ByteArray, needle: ByteArray): Int? {
    if (needle.isEmpty() || haystack.size < needle.size) return null
    for (i in haystack.size - needle.size downTo 0) {
        var match = true
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) return i
    }
    return null
}

private fun u16(bytes: ByteArray, offset: Int): Int {
    if (offset + 1 >= bytes.size) return 0
    return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}

private fun u32(bytes: ByteArray, offset: Int): Long {
    if (offset + 3 >= bytes.size) return 0
    return (bytes[offset].toLong() and 0xff) or
        ((bytes[offset + 1].toLong() and 0xff) shl 8) or
        ((bytes[offset + 2].toLong() and 0xff) shl 16) or
        ((bytes[offset + 3].toLong() and 0xff) shl 24)
}

private fun u64(bytes: ByteArray, offset: Int): Long {
    if (offset + 7 >= bytes.size) return 0
    var value = 0L
    for (i in 0 until 8) {
        value = value or ((bytes[offset + i].toLong() and 0xff) shl (8 * i))
    }
    return value
}
