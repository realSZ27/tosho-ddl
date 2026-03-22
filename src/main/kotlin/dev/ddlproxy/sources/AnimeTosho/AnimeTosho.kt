package dev.ddlproxy.sources.AnimeTosho

import dev.ddlproxy.AppConfig
import dev.ddlproxy.model.Release
import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.model.LinkGroup
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.time.Instant

class AnimeToshoSource(
    private val objectMapper: ObjectMapper = ObjectMapper()
) : DownloadSource {

    override val name = AppConfig.Source.AnimeTosho

    private val logger = LoggerFactory.getLogger(AnimeToshoSource::class.java)
    private val client: HttpClient = HttpClient.newBuilder().build()

    private val baseUrl = "https://feed.animetosho.org/json"

    override suspend fun search(query: String): List<Release> {
        val results = getJson("$baseUrl?q=${encode(query)}")

        if (!results.isArray || results.isEmpty) {
            logger.warn("No results found for query: {}", query)
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

    private fun processResult(result: JsonNode): Release {
        val torrentId = result.path("id").asInt()
        val title = result.path("title").asString()
        val webpageLink = result.path("link").asString()
        val publishTime = Instant.fromEpochSeconds(result.path("timestamp").asLong())

        return Release(
            title = title,
            source = name,
            webpageLink = webpageLink,
            identifier = torrentId.toString(),
            pubDate = publishTime
        )
    }

    override fun getLinks(identifier: String): List<LinkGroup> {
        val torrentId = identifier.toIntOrNull()
        if (torrentId == null) {
            logger.error("Invalid identifier: {}", identifier)
            return emptyList()
        }

        val torrentData = getJson("$baseUrl?show=torrent&id=$torrentId")

        val linkGroups = extractLinkGroups(torrentData)

        logger.trace("Extracted link groups: {}", linkGroups)

        return linkGroups
    }

    override fun getHostPriority(host: String): Int {
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

    private fun getJson(url: String): JsonNode {
        logger.trace("GET {}", url)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return objectMapper.readTree(response.body())
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}