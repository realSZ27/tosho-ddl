package dev.ddlproxy.sources.TsukiHime

import dev.ddlproxy.AppConfig
import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.model.LinkGroup
import dev.ddlproxy.model.Release
import dev.ddlproxy.service.JDownloaderController
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class TsukiHimeSource(
    private val client: HttpClient,
    private val objectMapper: ObjectMapper,
    private val jDownloaderController: JDownloaderController
) : DownloadSource {
    override val name = AppConfig.Source.TsukiHime

    private val logger = LoggerFactory.getLogger(TsukiHimeSource::class.java)

    private val baseUrl = "https://api.tsukihime.org/v1"

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

        val results = getJson("$baseUrl/search/torrents?q=${fullQuery.encode()}").path("results")

        if (!results.isArray || results.isEmpty) {
            logger.warn("No results found for query: {}", fullQuery)
            return emptyList()
        }

        return results.mapNotNull { result ->
            try {
                processResult(result)
            } catch (e: Exception) {
                logger.debug("Skipping result due to error", e)
                null
            }
        }
    }

    override suspend fun getRecent(): List<Release> {
        val results = getJson("$baseUrl/torrents").path("results")

        if (!results.isArray || results.isEmpty) {
            logger.warn("No results found for recent")
            return emptyList()
        }

        return results.mapNotNull { result ->
            try {
                processResult(result)
            } catch (e: Exception) {
                logger.debug("Skipping result due to error", e)
                null
            }
        }
    }

    override suspend fun download(identifier: String) {
        val result = getJson("$baseUrl/torrents/$identifier")

        val linkGroups = extractLinkGroups(result)

        logger.trace("Extracted link groups: {}", linkGroups)

        val sortedLinks = linkGroups.sortedBy { getHostPriority(it.host) }

        jDownloaderController.download(sortedLinks)
    }

    private fun getHostPriority(host: String): Int {
        return when {
            host.contains("Gofile", ignoreCase = true) -> 1
            host.contains("BuzzHeavier", ignoreCase = true) -> 2
            host.contains("KrakenFiles", ignoreCase = true) -> 3
            else -> Int.MAX_VALUE
        }
    }

    private fun extractLinkGroups(torrentData: JsonNode): List<LinkGroup> {
        val filesArray = torrentData.path("files")
        if (!filesArray.isArray) return emptyList()

        val linkGroups = mutableListOf<LinkGroup>()

        for (fileNode in filesArray) {
            val linksNode = fileNode.path("links")
            if (!linksNode.isObject) continue

            for ((host, valueNode) in linksNode.properties()) {
                val links = if (valueNode.isArray) {
                    valueNode.map { it.asString() }
                } else {
                    listOf(valueNode.asString())
                }

                if (links.isNotEmpty()) {
                    linkGroups.add(LinkGroup(host, links))
                }
            }
        }

        return linkGroups
    }

    private fun processResult(result: JsonNode): Release? {
        val status = result.path("state").asString()

        if (status != "completed") {
            logger.debug("Skipping result with status: {}", status)
            return null
        }

        val torrentId = result.path("id").asLong()
        val title = result.path("name").asString()
        val webpageLink = "https://tsukihime.org/view/$torrentId"
        val publishTime = Instant.ofEpochSecond(result.path("added_date").asLong())
        val size = result.path("totalsize").asLong()

        return Release(
            title = title,
            source = name,
            webpageLink = webpageLink,
            identifier = torrentId.toString(),
            pubDate = publishTime,
            fileSize = size
        )
    }

    private suspend fun getJson(url: String): JsonNode {
        logger.trace("GET {}", url)

        val response = client.get(url)
        val body = response.bodyAsText()

        return objectMapper.readTree(body)
    }

    private fun String.encode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8)
}