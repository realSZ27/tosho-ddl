package dev.ddlproxy.sources.Hi10Anime

import dev.ddlproxy.AppConfig
import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.model.LinkGroup
import dev.ddlproxy.model.Release
import dev.ddlproxy.service.JDownloaderController
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import tools.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import tools.jackson.core.type.TypeReference

class Hi10AnimeClient(
    private val client: HttpClient,
    private val jDownloaderController: JDownloaderController,
    private val objectMapper: ObjectMapper,
    private val seleniumUrl: String,
) : DownloadSource {

    override val name = AppConfig.Source.Hi10Anime

    private val logger = LoggerFactory.getLogger(Hi10AnimeClient::class.java)

    private val baseUrl = "https://hi10anime.com"

    private val loginUrl = "$baseUrl/wp-login.php"

    private val searchUrl = "$baseUrl/?s="
    private val jToken = "jtoken=17d26554d7"
    private val username = "imbpy@hi2.in"
    private val password = "imbpy@hi2.in"
    private var cookies: Map<String, String> = emptyMap()

    private var loggedIn = false

    private suspend fun login() {
        try {
            if (seleniumUrl.isBlank()) {
                logger.error("Selenium REST url not provided. Please set up Selenium to use Hi10Anime. More info in README.md")
                return
            }

            client.post("$seleniumUrl/start")

            val body = """
                {
                    "steps": [
                        {"action":"navigate","url":"$loginUrl"},
                        {"action":"wait","by":"css","value":".rc-anchor","condition":"page_load"},
                        {"action":"wait","by":"css","value":"#user_login","condition":"clickable"},
                        {"action":"click","by":"css","value":"#user_login"},
                        {"action":"type","by":"css","value":"#user_login","text":"imbpy@hi2.in"},
                        {"action":"press","key":"TAB"},
                        {"action":"type","by":"css","value":"#user_pass","text":"imbpy@hi2.in"}
                    ]
                }
            """.trimIndent()

            val body2 = """
                {
                    "steps": [
                        {"action":"click","by":"css","value":"#wp-submit"},
                        {"action":"wait","by":"css","value":".rc-anchor","condition":"page_load"}
                    ]
                }
            """.trimIndent()

            client.post("$seleniumUrl/run") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            Thread.sleep(5000)

            val response = client.post("$seleniumUrl/run") {
                contentType(ContentType.Application.Json)
                setBody(body2)
            }

            val jsonResponse = objectMapper.readTree(response.bodyAsText())

            val cookiesNode = jsonResponse
                .path("state")
                .path("cookies")

            cookies = objectMapper.convertValue(
                cookiesNode,
                object : TypeReference<Map<String, String>>() {}
            )

            logger.info(jsonResponse.toString().take(500))
            logger.info(cookies.toString())
        } finally {
            client.post("$seleniumUrl/stop")
        }
    }

    override suspend fun search(
        query: String,
        season: Int?,
        episode: Int?
    ): List<Release> {
        if (!loggedIn) login()

        val url = searchUrl + query
        return coroutineScope {
            try {
                logger.trace("GET: $url")
                val doc = fetch(url)

                doc.select("article").flatMap { post ->
                    try {
                        val a = post.selectFirst("h1.entry-title a") ?: return@flatMap emptyList()
                        val link = a.attr("abs:href")

                        val seriesPage = fetch(link)

                        parseTablesToReleases(seriesPage, link)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                logger.error("Search error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun getRecent(): List<Release> {
        if (!loggedIn) login()

        val pagesToFetch = 1

        return coroutineScope {
            val releases = mutableListOf<Release>()

            for (page in 1..pagesToFetch) {
                val url = if (page == 1) {
                    baseUrl
                } else {
                    "$baseUrl/page/$page"
                }

                try {
                    logger.trace("Fetching recent page: $url")
                    val doc = fetch(url)

                    val articles = doc.select("article")

                    for (article in articles) {
                        try {
                            val a = article.selectFirst("h1.entry-title a") ?: continue
                            val link = a.attr("abs:href")

                            val seriesPage = fetch(link)

                            releases += parseTablesToReleases(seriesPage, link)
                        } catch (e: Exception) {
                            logger.debug("Failed article parse: ${e.message}")
                        }
                    }

                } catch (e: Exception) {
                    logger.error("Failed page fetch: ${e.message}")
                }
            }

            releases
        }
    }

    override suspend fun download(identifier: String) {
        jDownloaderController.download(LinkGroup("OUI.IO", listOf(identifier)))
    }

    private fun parseTablesToReleases(doc: Document, pageUrl: String): List<Release> {
        logger.trace("Starting table parsing on url $pageUrl")
        //logger.trace(doc.outerHtml())
        val releases = mutableListOf<Release>()

        val pubDate = doc.selectFirst("time.entry-date")
            ?.attr("datetime")
            ?.let {
                try {
                    java.time.OffsetDateTime.parse(it).toInstant()
                } catch (e: Exception) {
                    Instant.now()
                }
            } ?: Instant.now()

        val tables = doc.select("table.showLinksTable")

        if (tables.isEmpty()) {
            logger.warn("Table list is empty")
        }

        for (table in tables) {
            try {
                val tableTitle = table.selectFirst("thead strong")
                    ?.text()
                    ?.trim()
                    ?: run {
                        logger.trace("Skipping table: Couldn't get title")
                        continue
                    }

                val rows = table.select("tbody tr")

                for (row in rows) {
                    try {
                        val tds = row.select("td")
                        if (tds.size < 4) {
                            logger.trace("Skipping row: Too small")
                        }

                        val episodeRaw = tds[0].ownText().trim()
                        if (episodeRaw.isBlank()) {
                            logger.trace("Skipping row: Episode is blank")
                            continue
                        }

                        val sizeRaw = tds[1].text()
                            .replace(",", ".")
                            .trim()

                        val ouoLink = tds[3]
                            .selectFirst("a[href]")
                            ?.attr("href")
                            ?: run {
                                logger.trace("Skipping row: ouo link not found")
                                continue
                            }

                        val token = extractToken(ouoLink) ?: run {
                            logger.warn("Skipping row: Couldn't get ouo token")
                            continue
                        }

                        val title = "[Hi10] $tableTitle episode $episodeRaw"

                        val sizeBytes = parseSizeToBytes(sizeRaw)

                        releases.add(
                            Release(
                                title = title,
                                source = name,
                                webpageLink = pageUrl,
                                identifier = token,
                                pubDate = pubDate,
                                fileSize = sizeBytes
                            ).also {
                                logger.trace("Created release: $it")
                            }
                        )

                    } catch (e: Exception) {
                        logger.debug("Failed to parse row: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                logger.debug("Failed to parse table: ${e.message}")
            }
        }

        return releases
    }

    private fun extractToken(href: String): String? {
        if (!href.startsWith("https://ouo.io/")) return null

        return try {
            val token = href.substringAfter("s=").substringBefore("&")
            "$token?$jToken"
        } catch (e: Exception) {
            null
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    suspend fun fetch(url: String, parser: Parser = Parser.htmlParser()): Document {
        logger.trace("Fetching url: $url")

        val body = client.get(url) {
            if (cookies.isNotEmpty()) {
                val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                header("Cookie", cookieHeader)
            }
        }.bodyAsText()

        return Jsoup.parse(body, baseUrl, parser)
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
}
