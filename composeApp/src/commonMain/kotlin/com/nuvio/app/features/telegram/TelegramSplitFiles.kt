package com.nuvio.app.features.telegram

data class TelegramSplitInfo(
    val groupKey: String,
    val partNumber: Int,
    val isZip: Boolean,
    val displayName: String,
)

private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv")
private val TRAILING_SPLIT = Regex("""(?i)\.(mkv|mp4|avi|ts|m4v|mov|wmv|webm|flv|m2ts|mpg|mpeg|zip)\.(\d{2,3})(?=$|\D)""")
private val ZIP_SPLIT = Regex("""^(.+)\.zip\.(\d{2,3})$""", RegexOption.IGNORE_CASE)
private val STANDALONE_MULTIPART = Regex("""(?i)(?:part|cd|disc|disk)[s._-]*\d+(?=\.\w+$)""")
private val SEPARATORS = Regex("""[\.\-_ ]+""")
private val INNER_SXXEXX = Regex("""(?i)s(\d{1,3})[.x_\- ]*e(\d{1,4})""")
private val INNER_EXX = Regex("""(?i)(?:^|[^\d])e(\d{1,4})(?:[^\d]|$)""")

fun parseTelegramSplitInfo(filename: String?): TelegramSplitInfo? {
    val name = filename?.trim().orEmpty()
    if (name.isEmpty() || STANDALONE_MULTIPART.containsMatchIn(name)) return null

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
    if (mimeType?.startsWith("video/", ignoreCase = true) == true) return true
    return fileName.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
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
    fileName.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

fun mimeTypeForFileName(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
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
