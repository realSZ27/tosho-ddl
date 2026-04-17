package dev.ddlproxy.sources.AnimePahe

import dev.ddlproxy.AppConfig
import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.model.LinkGroup
import dev.ddlproxy.model.Release
import dev.ddlproxy.service.KwikDownloader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimePaheSource(
    private val client: HttpClient,
    private val objectMapper: ObjectMapper,
    private val kwikDownloader: KwikDownloader,
) : DownloadSource {

    override val name = AppConfig.Source.AnimePahe

    private val logger = LoggerFactory.getLogger(AnimePaheSource::class.java)

    private val baseUrl = "https://animepahe.si"

    override suspend fun search(query: String, season: Int?, episode: Int?): List<Release> {
        logger.debug("Got search request: $query S${season}E${episode}")
        val url = "$baseUrl/api?m=search&q=${encode(query)}"
        val root = getJson(url)

        val total = root.path("total").asInt(0)
        if (total == 0) return emptyList()

        val data = root.path("data")
        if (!data.isArray) return emptyList()


        return data.map { entry ->
            coroutineScope {
                async {
                    val animeSlug = entry.path("session").asString()
                    val title = entry.path("title").asString()
                    val lastPage = entry.path("last_page").asInt(1)

                    val entries = (1..lastPage).map { page ->
                        async {
                            val url = "$baseUrl/api?m=release&id=$animeSlug&page=$page&sort=episode_asc"
                            val pageRoot = getJson(url)
                            val pageData = pageRoot.path("data")
                            if (!pageData.isArray) emptyList<JsonNode>() else pageData.toList()
                        }
                    }.awaitAll().flatten()

                    entries.map { releaseEntry ->
                        val episodeSlug = releaseEntry.path("session").asString()
                        val episodeNum = releaseEntry.path("episode").asInt()

                        val time = getInstant(releaseEntry.path("created_at").asString())

                        val fullTitle = "[AnimePahe] $title Episode $episodeNum" // can't get real group

                        Release(
                            title = fullTitle,
                            source = name,
                            webpageLink = "$baseUrl/anime/$animeSlug",
                            identifier = "$animeSlug:$episodeSlug:$fullTitle",
                            pubDate = time
                        )
                    }
                }
            }
        }.awaitAll().flatten()
    }

    private fun getInstant(input: String): Instant {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val localDateTime = LocalDateTime.parse(input, formatter)
        return localDateTime.toInstant(ZoneOffset.UTC)
    }

    override suspend fun getRecent(): List<Release> {
        val url = "$baseUrl/api?m=airing&page=1"
        val root = getJson(url)

        val data = root.path("data")
        if (!data.isArray) return emptyList()

        return data.mapNotNull { entry ->
            val title = entry.path("anime_title").asString("")
            val fansub = entry.path("fansub").asString("")

            val animeSlug = entry.path("anime_session").asString("")
            val episodeSlug = entry.path("session").asString("")
            val episodeNum = entry.path("episode").asInt(1)

            val time = getInstant(entry.path("created_at").asString(""))

            val fullTitle = "[$fansub] $title Episode $episodeNum"

            Release(
                title = fullTitle,
                source = name,
                webpageLink = "$baseUrl/anime/$animeSlug",
                identifier = "$animeSlug:$episodeSlug:$fullTitle",
                pubDate = time
            )
        }
    }

    override suspend fun download(identifier: String) {
        logger.trace(identifier)
        val (identifier, session, title) = identifier.split(":", limit = 3)

        val playHtml = client.get("$baseUrl/play/$identifier/$session") {
            header(HttpHeaders.Cookie, "__ddg2_=${generateRandomCookie()}")
        }.bodyAsText() 
        
        val kwikUrl = Jsoup.parse(playHtml).select("button[data-src][data-av1=\"0\"]").firstOrNull()?.attr("data-src")
            ?: error("No kwik link found")

        kwikDownloader.download(kwikUrl, "$title.mp4")
    }

    private suspend fun getJson(url: String): JsonNode {
        logger.trace("GET {}", url)

        val response = client.get(url) {
            header(HttpHeaders.Cookie, "__ddg2_=${generateRandomCookie()}")
        }
        val body = response.bodyAsText()

        return objectMapper.readTree(body)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun generateRandomCookie(): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..16).map { chars.random() }.joinToString("")
    }
}
