package dev.ddlproxy.service

import dev.ddlproxy.model.LinkGroup
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import tools.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import java.nio.charset.StandardCharsets

sealed class LinkState {
    object Online : LinkState()
    object Offline : LinkState()
    object Mixed : LinkState()
    object Unknown : LinkState()
    object Error : LinkState()
}

@Service
class JDownloaderController(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper,
    @Value($$"${jdownloader.api.url}") jdownloaderApiUrlRaw: String
) {

    private val logger = LoggerFactory.getLogger(JDownloaderController::class.java)

    private val jdownloaderApiUrl = jdownloaderApiUrlRaw.removeSuffix("/")

    fun isLinkOnline(links: LinkGroup): Boolean {
        addLinks(links.links)

        var result: LinkState
        var attempt = 0
        val maxRetries = 100

        do {
            result = queryLinks()
            attempt++

            when (result) {
                is LinkState.Online -> {
                    clearList()
                    return true
                }

                is LinkState.Offline -> {
                    clearList()
                    return false
                }

                is LinkState.Mixed -> {
                    logger.trace("Some links are not fully online, continuing to wait...")
                }

                is LinkState.Unknown -> {
                    logger.trace("Query returned UNKNOWN state, retrying...")
                }

                is LinkState.Error -> {
                    logger.error("Error encountered while querying links.")
                    break
                }
            }

            logger.trace("Query result is UNKNOWN, retrying...")

            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

        } while (attempt < maxRetries)

        clearList()
        logger.warn("Max retries reached on links: {}", links)
        return false
    }

    private fun addLinks(links: List<String>) {
        logger.debug("Adding links: {}", links)

        val url = "$jdownloaderApiUrl/linkgrabberv2/addLinks"

        val payload = mapOf(
            "links" to links.joinToString("\n"),
            "autostart" to false,
            "url" to true,
            "enabled" to true
        )

        restTemplate.postForEntity(
            url,
            HttpEntity(payload, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            Void::class.java
        )
    }

    private fun queryLinks(): LinkState {
        val url = "$jdownloaderApiUrl/linkgrabberv2/queryLinks"

        val payload = mapOf(
            "availability" to true,
            "enabled" to true
        )

        return try {
            val responseEntity: ResponseEntity<String> = restTemplate.postForEntity(
                url,
                HttpEntity(payload, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
                String::class.java
            )

            val jsonResponse = responseEntity.body ?: return LinkState.Unknown
            logger.trace("Response: {}", jsonResponse)

            val rootNode = objectMapper.readTree(jsonResponse)
            val dataArray = rootNode.path("data")

            if (!dataArray.isArray || dataArray.isEmpty) {
                logger.debug("No data returned from linkgrabber query")
                return LinkState.Unknown
            }

            var anyOnline = false
            var anyOffline = false

            for (data in dataArray) {
                val availability = data.path("availability").asString()

                when {
                    availability.equals("ONLINE", ignoreCase = true) -> anyOnline = true
                    availability.equals("OFFLINE", ignoreCase = true) -> anyOffline = true
                }
            }

            return when {
                anyOnline && !anyOffline -> LinkState.Online
                !anyOnline && anyOffline -> LinkState.Offline
                anyOnline && anyOffline -> LinkState.Mixed
                else -> LinkState.Unknown
            }

        } catch (e: Exception) {
            logger.error("Error during queryLinks", e)
            LinkState.Error
        }
    }

    private fun clearList() {
        val url = "$jdownloaderApiUrl/linkgrabberv2/clearList"

        val response: ResponseEntity<String> = restTemplate.getForEntity(url, String::class.java)

        if (response.statusCode != HttpStatus.OK) {
            logger.error("Failed to clear linkgrabber list: {}", response.body)
            throw RuntimeException("Clear list failed: ${response.statusCode}")
        }

        logger.debug("Linkgrabber list cleared successfully")
    }

    fun download(links: LinkGroup, destinationFolder: String) {
        clearList()

        val payload = mapOf(
            "links" to links.links.joinToString("\n"),
            "autostart" to true,
            "destinationFolder" to destinationFolder,
            "enabled" to true,
            "autoExtract" to true,
            "overwritePackagizerRules" to true
        )

        try {
            val response: ResponseEntity<String> = restTemplate.postForEntity(
                "$jdownloaderApiUrl/linkgrabberv2/addLinks",
                HttpEntity(payload, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
                String::class.java
            )

            logger.trace("JDownloader response: {}", response.body)
            logger.info("Started download: {}", links)
        } catch (e: Exception) {
            logger.error("Failed to start download: {}", links, e)
        }
    }
}
