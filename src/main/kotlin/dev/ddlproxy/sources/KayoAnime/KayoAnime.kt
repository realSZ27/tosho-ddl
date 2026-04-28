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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

    override suspend fun search(query: String, season: Int?, episode: Int?): List<Release> {
        val page = fetch("$baseUrl/?s=${encode(query)}")

        val (requestedEntries, normalEntries) = parsePostPage(page)
            .partition { it.title.contains("Requested", ignoreCase = true) }

        val pages = coroutineScope {
            val requestedDeferred = requestedEntries.map { entry ->
                async {
                    logger.debug("Handling REQUESTED '${entry.title}'")
                    handleRequested(entry)
                }
            }

            val normalDeferred = normalEntries.map { entry ->
                async {
                    logger.debug("Handling NORMAL '${entry.title}'")
                    listOf(buildPageStub(entry, fetch(entry.link)))
                }
            }

            (requestedDeferred + normalDeferred).awaitAll().flatten()
        }

        return pages.flatMap { processPage(it) }
    }

    override suspend fun getRecent(): List<Release> {
        val recentPage = fetch("$baseUrl/ongoing-animes/")
        val entries = parsePostPage(recentPage)

        val pages = coroutineScope {
            val deferredResults = entries.map { entry ->
                async {
                    buildPageStub(entry, fetch(entry.link))
                }
            }
            deferredResults.awaitAll()
        }

        return pages.flatMap { processPage(it) }
    }

    override suspend fun download(identifier: String) {
        jDownloaderController.download(
            LinkGroup("Google Drive", listOf(identifier))
        )
    }

    private suspend fun handleRequested(entry: EntryStub): List<PageStub> {
        val doc = fetch(entry.link)

        return doc.select(".toggle").mapNotNull { toggle ->
            val title = toggle.selectFirst(".toggle-head")?.text()?.trim()
                ?: return@mapNotNull null

            val buttons = toggle.select(".toggle-content a")
                .mapNotNull { a ->
                    val url = a.attr("abs:href").trim()
                    val label = a.text().trim()

                    if (url.isBlank()) return@mapNotNull null

                    ButtonStub(label, url)
                }

            if (buttons.isEmpty()) return@mapNotNull null

            PageStub(
                title = title,
                buttons = buttons,
                pubDate = entry.time,
                webpageLink = entry.link
            )
        }
    }

    private fun processPage(page: PageStub): List<Release> {
        val global = parseGlobalMeta(page.title)

        val entries = page.buttons.flatMap { button ->
            val meta = parseButtonMeta(button.label)
            val merged = mergeMeta(meta, global)

            merged.map {
                it.copy(
                    downloadUrl = button.url,
                    pubDate = page.pubDate,
                    webpageLink = page.webpageLink
                )
            }
        }

        return entries
            .groupBy { it.toKey() }
            .map { (key, group) ->
                toRelease(key, group.first())
            }
    }

    private fun ParsedEntry.toKey(): ReleaseKey {
        return ReleaseKey(
            title = title,
            season = season,
            quality = quality,
            source = source,
            extras = extras.sorted()
        )
    }

    private fun buildPageStub(entry: EntryStub, doc: Document): PageStub {
        val buttons = doc.select("""a[href^="https://drive.google.com/drive/folders/"]""")
            .mapNotNull {
                ButtonStub(
                    it.text().trim(),
                    it.attr("abs:href")
                )
            }

        return PageStub(entry.title, buttons, entry.time, entry.link)
    }

    private fun parsePostPage(doc: Document): List<EntryStub> {
        return doc
            .select(".post-details")
            .mapNotNull { div ->
                val dateDiv = div.selectFirst(".date") ?: return@mapNotNull null
                val time = parseTime(dateDiv.text().trim())

                val titleTag = div.selectFirst(".post-title a") ?: return@mapNotNull null
                val title = titleTag.text().trim()
                val url = titleTag.attr("abs:href")

                if (url.isBlank()) return@mapNotNull null

                EntryStub(title, url, time)
            }
    }

    private fun parseGlobalMeta(title: String): GlobalMeta {
        val seasonRegex = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
        val qualityRegex = Regex("""\b(480p|720p|1080p|2160p)\b""", RegexOption.IGNORE_CASE)

        val seasons = seasonRegex.findAll(title)
            .map { it.groupValues[1].toInt() }
            .toList()

        val quality = qualityRegex.find(title)?.value

        return GlobalMeta(title.trim(), seasons, quality, null, emptyList())
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

        return ButtonMeta(seasons, quality, source, extras)
    }

    private fun mergeMeta(button: ButtonMeta, global: GlobalMeta): List<ParsedEntry> {

        val seasons = when {
            button.seasons.isNotEmpty() -> button.seasons
            global.seasons.isNotEmpty() -> global.seasons
            else -> listOf(1)
        }

        val quality = button.quality ?: global.quality
        val source = button.source ?: global.source

        return seasons.map { season ->
            ParsedEntry(
                title = cleanTitle(global.baseTitle),
                season = season,
                quality = quality,
                source = source,
                extras = (button.extras + global.extras).distinct(),
                downloadUrl = "",
                pubDate = Instant.now(),
                webpageLink = null
            )
        }
    }

    private fun buildTitle(key: ReleaseKey): String {
        val base = "[KayoAnime] ${key.title}"

        val season = key.season?.let {
            "S" + it.toString().padStart(2, '0')
        }

        val quality = key.quality?.let { "[$it]" }
        val source = key.source?.let { "[$it]" }

        val extras = key.extras.joinToString("") { "[$it]" }

        return listOfNotNull(base, "-", season, quality, source, extras)
            .joinToString(" ")
            .trim()
    }

    private fun toRelease(key: ReleaseKey, entry: ParsedEntry): Release {
        return Release(
            title = buildTitle(key),
            source = AppConfig.Source.KayoAnime,
            webpageLink = entry.webpageLink,
            identifier = entry.downloadUrl,
            pubDate = entry.pubDate,
            fileSize = null
        )
    }


    private fun cleanTitle(title: String): String {
        return title
            // Remove episode info
            .replace(Regex("""\|?\s*Episode\s*\d+""", RegexOption.IGNORE_CASE), "")

            // Remove season info (including ranges)
            .replace(Regex("""\(?\s*Season\s*\d+(-\d+)?\s*\)?""", RegexOption.IGNORE_CASE), "")

            // Remove known metadata
            .replace(Regex("""English\s*Subbed""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""Dual\s*Audio""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""HEVC|x265""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(480p|720p|1080p|2160p)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""BluRay|WEB[- ]DL|HDRip""", RegexOption.IGNORE_CASE), "")

            // --- CLEANUP PASS ---

            // Remove empty parentheses
            .replace(Regex("""\(\s*\)"""), "")

            // Remove dangling separators like "|" or "-"
            .replace(Regex("""[\|\-]\s*$"""), "")
            .replace(Regex("""^\s*[\|\-]"""), "")

            // Remove multiple separators left behind
            .replace(Regex("""\s*[\|\-]\s*"""), " ")

            // Normalize whitespace
            .replace(Regex("""\s+"""), " ")

            .trim()
    }

    private fun normalizeSeasonText(input: String): String =
        input.replace("&", "+")
            .replace("Season", "S", true)
            .replace("\\s+".toRegex(), "")

    private fun extractSeasons(input: String): List<Int> {
        val single = Regex("""S(\d+)""")
        return single.findAll(input).map { it.groupValues[1].toInt() }.toList()
    }

    suspend fun fetch(url: String, parser: Parser = Parser.htmlParser()): Document {
        val body = client.get(url).bodyAsText()
        return Jsoup.parse(body, baseUrl, parser)
    }

    fun parseTime(input: String, now: ZonedDateTime = Instant.now().atZone(ZoneId.systemDefault())): Instant {
        val trimmed = input.trim().lowercase()

        if (trimmed.contains("ago")) {
            val regex = Regex("""(\d+)\s+(hour|day|week|month|year)s?\s+ago""")
            val match = regex.find(trimmed) ?: return Instant.now()

            val value = match.groupValues[1].toLong()

            return when (match.groupValues[2]) {
                "hour" -> now.minusHours(value).toInstant()
                "day" -> now.minusDays(value).toInstant()
                "week" -> now.minusWeeks(value).toInstant()
                "month" -> now.minusMonths(value).toInstant()
                "year" -> now.minusYears(value).toInstant()
                else -> Instant.now()
            }
        }

        val formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")
        return LocalDate.parse(input, formatter)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private data class PageStub(
        val title: String,
        val buttons: List<ButtonStub>,
        val pubDate: Instant,
        val webpageLink: String
    )

    private data class ButtonStub(val label: String, val url: String)

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
        val downloadUrl: String,
        val pubDate: Instant,
        val webpageLink: String?
    )

    private data class ReleaseKey(
        val title: String,
        val season: Int?,
        val quality: String?,
        val source: String?,
        val extras: List<String>
    )

    private data class EntryStub(val title: String, val link: String, val time: Instant)
}