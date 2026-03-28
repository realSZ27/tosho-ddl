package dev.ddlproxy.service

import dev.ddlproxy.model.Release
import dev.ddlproxy.model.DownloadSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.*
import java.io.StringWriter
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@Service
class DownloadService(
    private val sources: List<DownloadSource>,
    @param:Value($$"${base.url}") private val rawBaseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(DownloadService::class.java)

    private val thisBaseUrl = rawBaseUrl.trim().trimEnd('/')

    suspend fun handleQuery(params: MultiValueMap<String, String>): String {
        return try {
            val qValues = mutableListOf<String>()
            var season: Int? = null
            var episode: Int? = null

            for ((key, values) in params) {
                when {
                    key.equals("season", true) && values.isNotEmpty() -> {
                        season = values.first().toIntOrNull()
                    }

                    key.equals("ep", true) && values.isNotEmpty() -> {
                        episode = values.first().toIntOrNull()
                    }

                    key.equals("q", true) -> qValues.addAll(values)
                }
            }

            if (qValues.isEmpty()) return ""

            val query = qValues.joinToString(" ")

            val results = coroutineScope {
                sources.map { source ->
                    async(Dispatchers.IO) {
                        runCatching {
                            if (query.isBlank()) {
                                source.getRecent()
                            } else {
                                source.search(query, season, episode)
                            }
                        }.getOrElse {
                            logger.warn("Source failed: {}", source.name, it)
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }

            buildTorznabFeed(results)

        } catch (e: Exception) {
            createErrorXml(e.message ?: "Unexpected server error")
        }
    }

    suspend fun downloadRelease(sourceName: String, identifier: String) {
        val source = sources.find { it.name.name == sourceName }

        if (source == null) {
            logger.error("Unknown source: {}", sourceName)
            return
        }

        val links = try {
            source.download(identifier)
        } catch (e: Exception) {
            logger.error("Failed to fetch links for {}:{}", sourceName, identifier, e)
            return
        }
    }

    private fun buildTorznabFeed(releases: List<Release>): String {
        val docFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = docFactory.newDocumentBuilder()
        val doc = docBuilder.newDocument()

        // <rss>
        val rss = doc.createElement("rss")
        rss.setAttribute("version", "2.0")
        rss.setAttribute("xmlns:torznab", "http://torznab.com/schemas/2015/feed")
        rss.setAttribute("xmlns:atom", "http://www.w3.org/2005/Atom")
        doc.appendChild(rss)

        // <channel>
        val channel = doc.createElement("channel")
        rss.appendChild(channel)

        fun appendText(parent: org.w3c.dom.Element, name: String, value: String) {
            val el = doc.createElement(name)
            el.appendChild(doc.createTextNode(value))
            parent.appendChild(el)
        }

        appendText(channel, "title", "DDL Proxy")
        appendText(channel, "description", "Latest releases feed")

        val atomLink = doc.createElement("atom:link")
        atomLink.setAttribute("href", "$thisBaseUrl/api")
        atomLink.setAttribute("rel", "self")
        atomLink.setAttribute("type", "application/rss+xml")
        channel.appendChild(atomLink)

        appendText(channel, "link", "https://github.com/realSZ27/tosho-ddl/")
        appendText(channel, "language", "en-us")
        appendText(channel, "ttl", "30")

        releases.forEach { release ->
            val downloadUrl = buildDownloadUrl(release)

            val item = doc.createElement("item")
            channel.appendChild(item)

            appendText(item, "title", release.title)
            appendText(
                item, "pubDate",
                DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(release.pubDate.atOffset(ZoneOffset.UTC))
            )

            if (release.webpageLink != null)
                appendText(item, "comments", release.webpageLink)

            fun torznabAttr(name: String, value: String) {
                val attr = doc.createElement("torznab:attr")
                attr.setAttribute("name", name)
                attr.setAttribute("value", value)
                item.appendChild(attr)
            }

            torznabAttr("category", "2020")
            torznabAttr("category", "5070")
            torznabAttr("files", "1")
            torznabAttr("seeders", "10000")
            torznabAttr("leechers", "0")
            torznabAttr("peers", "10000")

            if (release.fileSize != null)
                torznabAttr("size", release.fileSize.toString())

            val enclosure = doc.createElement("enclosure")
            enclosure.setAttribute("url", downloadUrl)
            enclosure.setAttribute("type", "application/x-bittorrent")
            enclosure.setAttribute("length", "0")
            item.appendChild(enclosure)

            appendText(item, "description", "Source: ${release.source}")
        }

        // Convert to string with XML declaration
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.METHOD, "xml")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")

        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))

        return writer.toString()
    }

    private fun buildDownloadUrl(release: Release): String {
        val encoded = URLEncoder.encode(
            "${release.source}|${release.identifier}",
            StandardCharsets.UTF_8
        )
        return "$thisBaseUrl/download/$encoded.torrent"
    }

    private fun createErrorXml(message: String): String {
        val docFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = docFactory.newDocumentBuilder()
        val doc = docBuilder.newDocument()

        // <error>
        val error = doc.createElement("error")
        doc.appendChild(error)

        // <description>
        val description = doc.createElement("description")
        description.appendChild(doc.createTextNode(message))
        error.appendChild(description)

        // Convert to string
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.METHOD, "xml")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")

        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))

        return writer.toString()
    }
}