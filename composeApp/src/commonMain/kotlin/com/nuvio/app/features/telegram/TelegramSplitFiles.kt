package com.nuvio.app.features.telegram

data class TelegramSplitInfo(
    val groupKey: String,
    val partNumber: Int,
    val isZip: Boolean,
    val displayName: String,
) {
    /** True when the parts reassemble into a disc image rather than a ZIP archive. */
    val isIso: Boolean get() = !isZip && hasTelegramExtension(displayName, ISO_EXTENSIONS)
}

/** Extension of [fileName] lowercased, or an empty string when it has none. */
internal fun telegramExtension(fileName: String): String =
    fileName.substringAfterLast('.', "").lowercase()

internal fun hasTelegramExtension(fileName: String, extensions: Set<String>): Boolean =
    telegramExtension(fileName) in extensions

/** True for a disc image, whether stored whole (`.iso`) or as split volumes (`.iso.001`). */
fun isTelegramIsoName(fileName: String): Boolean =
    hasTelegramExtension(fileName, ISO_EXTENSIONS) || parseTelegramIsoSplit(fileName) != null

/** Matches `Name.iso.001` / `Name.img.002` but not a plain `Name.iso`. */
private fun parseTelegramIsoSplit(filename: String?): TelegramSplitInfo? {
    val name = filename?.trim().orEmpty()
    val match = ISO_SPLIT.matchEntire(name) ?: return null
    val base = match.groupValues[1]
    val ext = match.groupValues[2]
    val part = match.groupValues[3].toIntOrNull() ?: return null
    return TelegramSplitInfo(
        groupKey = "${normalizeTelegramBase(base)}.${ext.lowercase()}",
        partNumber = part,
        isZip = false,
        displayName = "$base.$ext",
    )
}

private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv")

/** Disc-image extensions. An `.iso` is a filesystem in a file, not a video stream. */
internal val ISO_EXTENSIONS = setOf("iso", "img")

/** Video payload extensions that are meaningful *inside* a disc image. */
private val DISC_STREAM_EXTENSIONS = setOf("m2ts", "mts", "vob", "mpg", "mpeg", "mkv", "mp4", "avi", "ts")

private val TRAILING_SPLIT = Regex("""(?i)\.(mkv|mp4|avi|ts|m4v|mov|wmv|webm|flv|m2ts|mpg|mpeg|zip|iso|img)\.(\d{2,3})(?=$|\D)""")
private val ZIP_SPLIT = Regex("""^(.+)\.zip\.(\d{2,3})$""", RegexOption.IGNORE_CASE)
private val ISO_SPLIT = Regex("""^(.+)\.(iso|img)\.(\d{2,3})$""", RegexOption.IGNORE_CASE)
private val STANDALONE_MULTIPART = Regex("""(?i)(?:part|cd|disc|disk)[s._-]*\d+(?=\.\w+$)""")
private val SEPARATORS = Regex("""[\.\-_ ]+""")
private val INNER_SXXEXX = Regex("""(?i)s(\d{1,3})[.x_\- ]*e(\d{1,4})""")
private val INNER_EXX = Regex("""(?i)(?:^|[^\d])e(\d{1,4})(?:[^\d]|$)""")

fun parseTelegramSplitInfo(filename: String?): TelegramSplitInfo? {
    val name = filename?.trim().orEmpty()
    if (name.isEmpty() || STANDALONE_MULTIPART.containsMatchIn(name)) return null

    ISO_SPLIT.matchEntire(name)?.let { match ->
        val baseRaw = match.groupValues[1]
        val ext = match.groupValues[2]
        val part = match.groupValues[3].toIntOrNull() ?: return null
        return TelegramSplitInfo(
            groupKey = "${normalizeTelegramBase(baseRaw)}.${ext.lowercase()}",
            partNumber = part,
            isZip = false,
            displayName = "$baseRaw.$ext",
        )
    }

    ZIP_SPLIT.matchEntire(name)?.let { match ->
        val baseRaw = match.groupValues[1]
        val part = match.groupValues[2].toIntOrNull() ?: return null
        val display = "$baseRaw.zip"
        return TelegramSplitInfo(
            groupKey = "${normalizeTelegramBase(baseRaw)}.zip",
            partNumber = part,
            isZip = true,
            displayName = display,
        )
    }

    TRAILING_SPLIT.find(name)?.let { match ->
        val ext = match.groupValues[1]
        val part = match.groupValues[2].toIntOrNull() ?: return null
        val remainder = name.substring(0, match.range.first) + "." + ext
        return TelegramSplitInfo(
            groupKey = normalizeTelegramBase(remainder),
            partNumber = part,
            isZip = ext.equals("zip", ignoreCase = true),
            displayName = remainder,
        )
    }

    if (name.endsWith(".zip", ignoreCase = true)) {
        val base = name.dropLast(4)
        return TelegramSplitInfo(
            groupKey = "${normalizeTelegramBase(base)}.zip",
            partNumber = 1,
            isZip = true,
            displayName = name,
        )
    }

    return null
}

fun isTelegramStreamableName(fileName: String, mimeType: String?): Boolean {
    if (parseTelegramSplitInfo(fileName) != null) return true
    if (fileName.endsWith(".zip", ignoreCase = true)) return true
    if (isTelegramIsoName(fileName)) return true
    if (mimeType?.startsWith("video/", ignoreCase = true) == true) return true
    return telegramExtension(fileName) in VIDEO_EXTENSIONS
}

fun telegramSearchQueries(title: String, season: Int?, episode: Int?): List<String> {
    val normalizedTitle = title
        .replace(Regex("""[\\/:*?\"<>|]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (normalizedTitle.isEmpty()) return emptyList()
    if (season == null) return listOf(normalizedTitle)

    val paddedSeason = season.toString().padStart(2, '0')
    val queries = mutableListOf(
        "$normalizedTitle S$paddedSeason",
        "$normalizedTitle.S$paddedSeason",
        "$normalizedTitle Season $season",
    )
    if (episode != null) {
        val paddedEpisode = episode.toString().padStart(2, '0')
        queries += listOf(
            "$normalizedTitle S${paddedSeason}E$paddedEpisode",
            "$normalizedTitle S${season}E${episode}",
            "$normalizedTitle ${season}x$paddedEpisode",
            "$normalizedTitle Season $season Episode $episode",
        )
    }
    return queries.distinct()
}

fun isTelegramVideoFileName(fileName: String): Boolean =
    telegramExtension(fileName) in VIDEO_EXTENSIONS

/** True for a payload that can be handed to the player once a disc image is opened. */
fun isTelegramDiscStreamFileName(fileName: String): Boolean =
    telegramExtension(fileName) in DISC_STREAM_EXTENSIONS

fun mimeTypeForFileName(fileName: String): String = when (telegramExtension(fileName)) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "m2ts" -> "video/mp2t"
    "ts" -> "video/mp2t"
    "vob" -> "video/mpeg"
    "mpg", "mpeg" -> "video/mpeg"
    "iso", "img" -> "application/x-iso9660-image"
    else -> "video/x-matroska"
}

fun telegramInnerMatchesEpisode(fileName: String, season: Int?, episode: Int?): Boolean {
    val parsed = parseInnerSeasonEpisode(fileName)
    if (episode == null) return parsed?.episode == null
    if (parsed == null) return false
    if (parsed.episode != episode) return false
    if (parsed.season != null && season != null && parsed.season != season) return false
    return true
}

fun selectTelegramZipEntry(
    entries: List<TelegramZipEntry>,
    season: Int?,
    episode: Int?,
): TelegramZipEntry? {
    val videos = entries.filter { it.method == ZIP_STORED && it.size > 0 && isTelegramVideoFileName(it.name) }
    if (videos.isEmpty()) return null
    if (episode == null) return videos.singleOrNull()
    return videos.firstOrNull { telegramInnerMatchesEpisode(it.name, season, episode) }
}

fun contiguousTelegramParts(parts: Collection<Int>): Boolean {
    if (parts.isEmpty()) return false
    val sorted = parts.sorted()
    if (sorted.first() != 1) return false
    return sorted.indices.all { index -> sorted[index] == index + 1 }
}

internal fun normalizeTelegramBase(value: String): String =
    SEPARATORS.replace(value.trim(), ".").trim('.').lowercase()

internal data class InnerSeasonEpisode(
    val season: Int?,
    val episode: Int,
)

internal fun parseInnerSeasonEpisode(fileName: String): InnerSeasonEpisode? {
    val leaf = fileName.substringAfterLast('/').substringAfterLast('\\')
    INNER_SXXEXX.find(leaf)?.let { match ->
        val season = match.groupValues[1].toIntOrNull() ?: return@let
        val episode = match.groupValues[2].toIntOrNull() ?: return@let
        return InnerSeasonEpisode(season, episode)
    }
    INNER_EXX.find(leaf)?.let { match ->
        val episode = match.groupValues[1].toIntOrNull() ?: return@let
        return InnerSeasonEpisode(null, episode)
    }
    return null
}
