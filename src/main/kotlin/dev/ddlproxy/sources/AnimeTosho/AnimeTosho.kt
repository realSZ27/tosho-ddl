package dev.ddlproxy.sources.AnimeTosho

import dev.ddlproxy.AppConfig
import dev.ddlproxy.model.Release
import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.model.LinkGroup
import dev.ddlproxy.service.JDownloaderController
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class AnimeToshoSource(
    private val client: HttpClient,
    private val objectMapper: ObjectMapper,
    private val jDownloaderController: JDownloaderController,
) : DownloadSource {

    override val name = AppConfig.Source.AnimeTosho

    private val logger = LoggerFactory.getLogger(AnimeToshoSource::class.java)

    private val baseUrl = "https://feed.animetosho.org/json"

    override suspend fun search(query: String, season: Int?, episode: Int?): List<Release> {
        logger.trace("handling search for S${season}E${episode}")
        var fullQuery = query

        if (season != null) {
            val paddedSeason = season.toString().padStart(2, '0')
            fullQuery += " S$paddedSeason"
        }

        if (episode != null) {
            val paddedEpisode = episode.toString().padStart(2, '0')
            fullQuery += "E$paddedEpisode"
        }

        val results = getJson("$baseUrl?q=${encode(fullQuery)}")

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
        val results = getJson(baseUrl)

        if (!results.isArray || results.isEmpty) return emptyList()

        return results.mapNotNull { result ->
            try {
                processResult(result)
            } catch (e: Exception) {
                logger.debug("Skipping result due to error", e)
                null
            }
        }
    }

    private fun processResult(result: JsonNode): Release? {
        val status = result.path("status").asString()
        if (!status.equals("complete", ignoreCase = true)) {
            logger.trace("Release is not done uploading")
            return null
        }

        val torrentId = result.path("id").asInt()
        val title = result.path("title").asString()
        val webpageLink = result.path("link").asString()
        val publishTime = Instant.ofEpochSecond(result.path("timestamp").asLong())
        val size = result.path("total_size").asLong()

        return Release(
            title = title,
            source = name,
            webpageLink = webpageLink,
            identifier = torrentId.toString(),
            pubDate = publishTime,
            fileSize = size
        )
    }

    override suspend fun download(identifier: String) {
        val torrentId = identifier.toIntOrNull()
        if (torrentId == null) {
            logger.error("Invalid identifier: {}", identifier)
            return
        }

        val torrentData = getJson("$baseUrl?show=torrent&id=$torrentId")

        val linkGroups = extractLinkGroups(torrentData)

        logger.trace("Extracted link groups: {}", linkGroups)

        val sortedLinks = linkGroups.sortedBy { getHostPriority(it.host) }

        jDownloaderController.download(sortedLinks)
    }

    private fun getHostPriority(host: String): Int {
        return when {
            host.contains("gofile", ignoreCase = true) -> 1
            host.contains("buzzheavier", ignoreCase = true) -> 2
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

    private suspend fun getJson(url: String): JsonNode {
        logger.trace("GET {}", url)

        val response = client.get(url)
        val body = response.bodyAsText()

        return objectMapper.readTree(body)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}