package dev.ddlproxy.sources.AnimeToshoNew

import dev.ddlproxy.AppConfig
import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.model.LinkGroup
import dev.ddlproxy.model.Release
import dev.ddlproxy.service.JDownloaderController
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class AnimeToshoNewSource(
    private val client: HttpClient,
    private val objectMapper: ObjectMapper,
    private val jDownloaderController: JDownloaderController,
) : DownloadSource {
    override val name: AppConfig.Source = AppConfig.Source.AnimeToshoNew

    private val baseUrl = "https://animetosho.xyz"

    override suspend fun search(
        query: String,
        season: Int?,
        episode: Int?
    ): List<Release> {
        var fullQuery = query

        if (season != null) {
            val paddedSeason = season.toString().padStart(2, '0')
            fullQuery += " S$paddedSeason"
        }

        if (episode != null) {
            val paddedEpisode = episode.toString().padStart(2, '0')
            fullQuery += "E$paddedEpisode"
        }

        val searchPage = fetch("$baseUrl/search?q=${encode(fullQuery)}")
        return processPage(searchPage)
    }

    override suspend fun getRecent(): List<Release> {
        val recentPage = fetch("$baseUrl/")
        return processPage(recentPage)
    }

    override suspend fun download(identifier: String) {
        val links: List<LinkGroup> = objectMapper.readValue<List<LinkGroup>>(identifier)
            .sortedBy { getHostPriority(it.host) }
        jDownloaderController.download(links)
    }

    fun processPage(page: Document): List<Release> {
        val releases = page.select(".home_list_entry")

        return releases.mapNotNull { entry ->
            val title = entry.selectFirst(".link a")
                ?.text()
                ?.trim()
                ?: return@mapNotNull null

            val link = entry.selectFirst(".link a")
                ?.attr("abs:href")
                ?: return@mapNotNull null

            val date = entry.selectFirst(".date")
                ?.attr("title")
                ?.let(::parseInstant)
                ?: return@mapNotNull null

            val size = entry.selectFirst(".size")
                ?.attr("title")
                ?.let(::parseSizeToBytes)
                ?: return@mapNotNull null

            val linksContainer = entry.selectFirst(".links")
                ?: return@mapNotNull null

            val anchors = linksContainer.select("> a")

            val hostLinks = anchors
                .mapNotNull {
                    val text = it.text().trim()
                    if (text.equals("torrent", ignoreCase = true) ||
                        text.equals("magnet", ignoreCase = true) ||
                        text.equals("nzb", ignoreCase = true)) {
                        return@mapNotNull null
                    }

                    LinkGroup(
                        host = it.text(),
                        links = listOf(it.attr("abs:href"))
                    )
                }

            if (hostLinks.isEmpty()) return@mapNotNull null

            Release(
                title = title,
                source = AppConfig.Source.AnimeToshoNew,
                webpageLink = link,
                identifier = objectMapper.writeValueAsString(hostLinks),
                pubDate = date,
                fileSize = size,
            )
        }
    }

    fun parseSizeToBytes(sizeTitle: String): Long {
        return Regex("""(\d+)""")
            .find(sizeTitle)
            ?.groupValues
            ?.get(1)
            ?.toLong()
            ?: 0L
    }

    private fun getHostPriority(host: String): Int {
        return when {
            host.contains("gofile", ignoreCase = true) -> 1
            host.contains("buzzheavier", ignoreCase = true) -> 2
            else -> Int.MAX_VALUE
        }
    }

    fun parseInstant(dateText: String): Instant {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z")
        return LocalDateTime
            .parse(dateText, formatter)
            .toInstant(ZoneOffset.UTC)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    suspend fun fetch(url: String, parser: Parser = Parser.htmlParser()): Document {
        val body = client.get(url).bodyAsText()
        return Jsoup.parse(body, baseUrl, parser)
    }
}