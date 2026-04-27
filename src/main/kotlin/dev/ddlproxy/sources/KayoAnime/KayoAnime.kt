package dev.ddlproxy.sources.KayoAnime

import dev.ddlproxy.AppConfig
import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.model.LinkGroup
import dev.ddlproxy.model.Release
import dev.ddlproxy.service.JDownloaderController
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class KayoAnimeSource(
    private val client: HttpClient,
    private val jDownloaderController: JDownloaderController,
) : DownloadSource {

    override val name = AppConfig.Source.KayoAnime

    private val logger = LoggerFactory.getLogger(KayoAnimeSource::class.java)
    private val baseUrl = "https://kayoanime.com"

    // ----------------------------
    // Public API
    // ----------------------------

    override suspend fun search(query: String, season: Int?, episode: Int?): List<Release> {
        val pages: List<PageStub> = emptyList()

        return pages.flatMap { processPage(it) }
    }

    override suspend fun getRecent(): List<Release> {
        data class EntryStub(val title: String, val link: String, val time: Instant)
        
        val recentPage = fetch("$baseUrl/ongoing-animes/")

        val entries = recentPage
            .select(".post-details")
            .mapNotNull { div ->
                val dateDiv = div.selectFirst(".date") ?: return@mapNotNull null
                val time = parseTime(dateDiv.text().trim())

                val titleTag = div.selectFirst(".post-title a") ?: return@mapNotNull null
                val title = titleTag.text().trim()
                val url = titleTag.attr("abs:href")

                if (url.isBlank()) {
                    logger.warn("Url for $title is blank")
                    logger.trace(titleTag.outerHtml())
                    return@mapNotNull null
                }

                EntryStub(title, url, time)
            }

        val pages: List<PageStub> = coroutineScope {
            val deferredResults = entries.map { entry ->
                async {
                    // fetch info for each entry
                    logger.debug("Fetching details for '${entry.title}' at ${entry.link}")
                    val doc = fetch(entry.link)

                    val buttons = doc.select("""a[href^="https://drive.google.com/drive/folders/"]""")
                        .mapNotNull {
                            ButtonStub(
                                it.text().trim(),
                                it.attr("abs:href")
                            )
                        }

                    PageStub(entry.title, buttons)
                }
            }
            deferredResults.awaitAll().also {
                logger.debug("Total releases found: ${it.size}")
            }
        }

        return pages.flatMap { processPage(it) }
    }

    override suspend fun download(identifier: String) {
        // identifier = direct download link
        jDownloaderController.download(
            LinkGroup("Google Drive", listOf(identifier))
        )
    }

    // ----------------------------
    // Core Pipeline
    // ----------------------------

    private fun processPage(page: PageStub): List<Release> {
        val global = parseGlobalMeta(page.title)

        val entries = page.buttons.flatMap { button ->
            val meta = parseButtonMeta(button.label)
            val merged = mergeMeta(meta, global)

            merged.map { it.copy(downloadUrl = button.url) }
        }

        return entries
            .groupBy { it.toKey() }
            .map { (key, group) ->
                toRelease(key, group.first())
            }
    }

    // ----------------------------
    // Parsing
    // ----------------------------

    private fun parseGlobalMeta(title: String): GlobalMeta {
        val seasonRegex = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
        val qualityRegex = Regex("""\b(480p|720p|1080p|2160p)\b""", RegexOption.IGNORE_CASE)

        val seasons = seasonRegex.findAll(title)
            .map { it.groupValues[1].toInt() }
            .toList()

        val quality = qualityRegex.find(title)?.value

        return GlobalMeta(
            baseTitle = title.trim(),
            seasons = seasons,
            quality = quality,
            source = null,
            extras = emptyList()
        )
    }

    private fun parseButtonMeta(label: String): ButtonMeta {
        val normalized = normalizeSeasonText(label)

        val seasons = extractSeasons(normalized)

        val quality = Regex("""(480p|720p|1080p|2160p)""", RegexOption.IGNORE_CASE)
            .find(label)?.value

        val source = Regex("""(BluRay|WEB[- ]DL|HDRip)""", RegexOption.IGNORE_CASE)
            .find(label)?.value

        val extras = mutableListOf<String>()

        if (seasons.isEmpty() && quality == null && source == null) {
            extras.add(label)
        }

        return ButtonMeta(
            seasons = seasons,
            quality = quality,
            source = source,
            extras = extras
        )
    }

    // ----------------------------
    // Merge + Expansion
    // ----------------------------

    private fun mergeMeta(
        button: ButtonMeta,
        global: GlobalMeta
    ): List<ParsedEntry> {

        val seasons = when {
            button.seasons.isNotEmpty() -> button.seasons
            global.seasons.size == 1 -> global.seasons
            else -> return emptyList() // ambiguous → skip
        }

        val quality = button.quality ?: global.quality
        val source = button.source ?: global.source

        return seasons.map { season ->
            ParsedEntry(
                title = global.baseTitle,
                season = season,
                quality = quality,
                source = source,
                extras = button.extras + global.extras,
                downloadUrl = ""
            )
        }
    }

    // ----------------------------
    // Season Handling
    // ----------------------------

    private fun normalizeSeasonText(input: String): String {
        return input
            .replace("&", "+")
            .replace("Season", "S", ignoreCase = true)
            .replace("\\s*\\+\\s*".toRegex(), "+")
            .replace("\\s+".toRegex(), "")
    }

    private fun extractSeasons(input: String): List<Int> {
        val range = Regex("""S(\d+)-(\d+)""")
        val multi = Regex("""S(\d+(?:\+S?\d+)*)""")

        range.find(input)?.let {
            val start = it.groupValues[1].toInt()
            val end = it.groupValues[2].toInt()
            return (start..end).toList()
        }

        multi.find(input)?.let {
            return it.groupValues[1]
                .split("+")
                .map { s -> s.replace("S", "") }
                .mapNotNull { s -> s.toIntOrNull() }
        }

        val single = Regex("""S(\d+)""")
        return single.findAll(input)
            .map { it.groupValues[1].toInt() }
            .toList()
    }

    // ----------------------------
    // Grouping
    // ----------------------------

    private fun ParsedEntry.toKey(): ReleaseKey {
        return ReleaseKey(
            title = title,
            season = season,
            quality = quality,
            source = source,
            extras = extras.sorted()
        )
    }

    // ----------------------------
    // Release Conversion
    // ----------------------------

    private fun toRelease(key: ReleaseKey, entry: ParsedEntry): Release {
        return Release(
            title = buildTitle(key),
            source = AppConfig.Source.KayoAnime,
            webpageLink = null,
            identifier = entry.downloadUrl,
            pubDate = Instant.now(),
            fileSize = null
        )
    }

    private fun buildTitle(key: ReleaseKey): String {
        val base = key.title.replace(" ", ".")

        val season = key.season?.let { "S" + it.toString().padStart(2, '0') }

        return listOfNotNull(
            base,
            season,
            key.quality,
            key.source,
            key.extras.joinToString(".").takeIf { it.isNotBlank() }
        ).joinToString(".")
    }

    suspend fun fetch(url: String, parser: Parser = Parser.htmlParser()): Document {
        logger.trace("Fetching url: $url")

        val body = client.get(url).bodyAsText()

        return Jsoup.parse(body, baseUrl, parser)
    }

    fun parseTime(input: String, now: ZonedDateTime = Instant.now().atZone(ZoneId.systemDefault())): Instant {
        val trimmed = input.trim().lowercase()

        if (trimmed.contains("ago")) {
            val regex = Regex("""(\d+)\s+(hour|day|week|month|year)s?\s+ago""")
            val match = regex.find(trimmed)
                ?: run {
                    logger.error("Unrecognized relative time: $input")
                    return Instant.now()
                }

            val value = match.groupValues[1].toLong()
            return when (val unit = match.groupValues[2]) {
                "hour" -> now.minusHours(value).toInstant()
                "day" -> now.minusDays(value).toInstant()
                "week" -> now.minusWeeks(value).toInstant()
                "month" -> now.minusMonths(value).toInstant()
                "year" -> now.minusYears(value).toInstant()
                else -> run {
                    logger.error("Unknown unit: $unit")
                    return Instant.now()
                }
            }
        }

        val formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")

        val localDate = LocalDate.parse(input, formatter)

        return localDate
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
    }

    // ----------------------------
    // Internal Models
    // ----------------------------

    private data class PageStub(
        val title: String,
        val buttons: List<ButtonStub>
    )

    private data class ButtonStub(
        val label: String,
        val url: String
    )

    private data class GlobalMeta(
        val baseTitle: String,
        val seasons: List<Int>,
        val quality: String?,
        val source: String?,
        val extras: List<String>
    )

    private data class ButtonMeta(
        val seasons: List<Int>,
        val quality: String?,
        val source: String?,
        val extras: List<String>
    )

    private data class ParsedEntry(
        val title: String,
        val season: Int?,
        val quality: String?,
        val source: String?,
        val extras: List<String>,
        val downloadUrl: String
    )

    private data class ReleaseKey(
        val title: String,
        val season: Int?,
        val quality: String?,
        val source: String?,
        val extras: List<String>
    )
}