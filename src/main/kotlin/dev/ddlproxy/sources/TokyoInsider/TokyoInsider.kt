package dev.ddlproxy.sources.TokyoInsider

import dev.ddlproxy.AppConfig
import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.model.LinkGroup
import dev.ddlproxy.model.Release
import dev.ddlproxy.service.JDownloaderController
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class TokyoInsiderSource(
    private val client: HttpClient,
    private val jDownloaderController: JDownloaderController,
) : DownloadSource {

    override val name = AppConfig.Source.TokyoInsider

    private val logger = LoggerFactory.getLogger(TokyoInsiderSource::class.java)

    private val baseUrl = "https://www.tokyoinsider.com"

    override suspend fun search(query: String, season: Int?, episode: Int?): List<Release> {
        logger.debug("Starting search for query: '$query'")
        val url = "$baseUrl/anime/list"

        val document = fetch(url)

        val entries = document
            .select("div[class~=c_h2b?] a[href^=/anime/]")
            .mapNotNull { a ->
                val title = a.text().trim()
                val url = a.attr("abs:href")

                if (url.isBlank()) {
                    logger.warn("Url for {} is blank", title)
                    logger.trace(a.outerHtml())
                    return@mapNotNull null
                }

                title to url
            }

        logger.debug("Found ${entries.size} entries on the anime list page")

        val top = entries
            .asSequence()
            .map { (title, url) ->
                val s = score(query, title)
                Triple(title, url, s)
            }
            .filter { it.third > 0 }
            .sortedByDescending { it.third }
            .take(3)
            .toList()

        logger.debug("Top ${top.size} matching entries selected")

        if (top.isEmpty()) {
            logger.warn("No entries matched query '$query'")
            return emptyList()
        }

        return coroutineScope {
            val deferredResults = top.map { entry ->
                async {
                    logger.debug("Fetching details for '${entry.first}' at ${entry.second}")
                    val doc = fetch(entry.second)
                    extractReleases(doc, query)
                }
            }
            deferredResults.awaitAll().flatten().also {
                logger.debug("Total releases found: ${it.size}")
            }
        }
    }

    override suspend fun getRecent(): List<Release> {
        val url = "$baseUrl/rss"
        val doc = fetch(url, Parser.xmlParser())

        val items = doc.select("item").toList()
        return coroutineScope {
            val deferred = items.mapNotNull { item ->
                async {
                    val title = item.selectFirst("description")?.text()?.trim() ?: return@async null
                    val episodeLink = item.selectFirst("link")?.text()?.trim() ?: return@async null
                    logger.trace("episode link: \"$episodeLink\"")
                    val episodePage = fetch(episodeLink, Parser.xmlParser())
                    val releaseDiv = episodePage.selectFirst(
                        "div.c_h2:has(a:contains($title)), div.c_h2b:has(a:contains($title))"
                    ) ?: run {
                        logger.debug("Missing release div.")
                        return@async null
                    }

                    parseRelease(releaseDiv, episodeLink)
                }
            }
            deferred.awaitAll().filterNotNull()
        }

    }

    override suspend fun download(identifier: String) {
        jDownloaderController.download(
            LinkGroup(
                host = "TokyoInsider",
                links = listOf(identifier)
            )
        )
    }

    private suspend fun extractReleases(animePageDoc: Document, query: String, episode: Int? = null): List<Release> {
        logger.trace("Extracting releases from document")
        val episodes = animePageDoc
            .select(".download-link")
            .map { a ->
                val episode = a.selectFirst("strong")?.text()?.trim()
                val title = a.ownText().trim()
                val newTitle = "$title episode $episode"
                val url = a.attr("abs:href")
                newTitle to url
            }

        val top = episodes
            .asSequence()
            .map { (title, url) ->
                val s = score(query, title) +
                        if (episode != null && url.contains("/episode/$episode")) 100 else 0

                Triple(title, url, s)
            }
            .filter { it.third > 0 }
            .maxByOrNull { it.third } ?: run {
            logger.warn("No episodes matched query '$query'")
            return emptyList()
        }

        logger.debug("Selected top episode: '${top.first}' at ${top.second}")

        val episodePage = fetch(top.second)

        val releases = episodePage
            .select("div.c_h2, div.c_h2b")
            .mapNotNull { container ->
                parseRelease(container, top.second)
            }


        logger.debug("Extracted ${releases.size} releases from episode page")
        return releases
    }

    private fun parseRelease(container: Element, link: String): Release? {
        logger.trace("Processing container: ${container.className()}")

        val linkDiv = container.selectFirst("div:not(.finfo)") ?: run {
            logger.warn("Missing link div")
            return null
        }

        val a = linkDiv.selectFirst("a[href^=https://media.tokyoinsider.com]") ?: run {
            logger.warn("Couldn't find download link")
            return null
        }

        val title = a.text().trim()
        val downloadUrl = a.attr("abs:href")

        val finfo = container.selectFirst("div.finfo") ?: run {
            logger.warn("Couldn't find finfo for '$title'")
            return null
        }

        val values = finfo.select("b").map { it.text().trim() }

        val sizeRaw = values.getOrNull(0) ?: run {
            logger.warn("Skipping '$title': missing size")
            return null
        }

        val dateRaw = values.getOrNull(3) ?: run {
            logger.warn("Skipping '$title': missing date")
            return null
        }

        val dateInstant = getInstant(dateRaw)
        val sizeLong = parseSizeToBytes(sizeRaw)

        logger.trace("Parsed release -> title: $title, size: $sizeRaw, date: $dateRaw, download: $downloadUrl")

        return Release(
            title = title,
            source = AppConfig.Source.TokyoInsider,
            webpageLink = link,
            identifier = downloadUrl,
            pubDate = dateInstant,
            fileSize = sizeLong
        ).also {
            logger.trace("Extracted release: $it")
        }
    }

    private fun score(query: String, text: String): Int {
        var score = 0

        if (text.contains(query, ignoreCase = true)) score += 100

        val qWords = query.split(Regex("\\s+"))
        for (word in qWords) {
            if (word.isNotBlank() && text.contains(word, ignoreCase = true)) {
                score += 10
            }
        }

        return score
    }

    private fun getInstant(input: String): Instant {
        return try {
            val formatter = DateTimeFormatter.ofPattern("MM/dd/yy")
            val localDate = LocalDate.parse(input, formatter)
            val zone = ZoneId.of("America/Chicago") // TokyoInsider is on GMT-5
            localDate
                .atStartOfDay(zone)
                .toInstant()
        } catch (e: Exception) {
            logger.error("Failed to parse date '$input': ${e.message}")
            Instant.now()
        }
    }

    fun parseSizeToBytes(size: String): Long {
        val regex = Regex("""([\d.]+)\s*(B|KB|MB|GB|TB)""", RegexOption.IGNORE_CASE)
        val match = regex.find(size.trim()) ?: run {
            logger.warn("Failed to parse size: '$size'")
            return 0L
        }

        val number = match.groupValues[1].toDouble()
        val unit = match.groupValues[2].uppercase()

        val bytes = when (unit) {
            "B" -> number
            "KB" -> number * 1024
            "MB" -> number * 1024 * 1024
            "GB" -> number * 1024 * 1024 * 1024
            "TB" -> number * 1024 * 1024 * 1024 * 1024
            else -> 0.0
        }

        return bytes.toLong()
    }

    suspend fun fetch(url: String, parser: Parser = Parser.htmlParser()): Document {
        logger.trace("Fetching url: $url")

        val body = client.get(url) {
            headers {
                append("User-Agent", "Mozilla/5.0")
            }
        }.bodyAsText()

        return Jsoup.parse(body, baseUrl, parser)
    }
}