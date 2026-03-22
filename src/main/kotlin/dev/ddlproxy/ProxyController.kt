@file:Suppress("CanConvertToMultiDollarString")

package dev.ddlproxy

import dev.ddlproxy.service.DownloadService
import dev.ddlproxy.service.FileWatcherService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.io.FileNotFoundException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@EnableAsync
@SpringBootApplication
@RestController
class ProxyController(
    private val downloadService: DownloadService,
    private val fileWatcherService: FileWatcherService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ProxyController::class.java)

        private const val CAPABILITIES_XML = """
            <caps>
                <server version="1.0" title="AnimeTosho DDL Proxy" strapline="AnimeTosho DDLs for Sonarr" url="https://github.com/realSZ27"/>
                <limits max="200" default="75"/>
                <retention days="9999"/>
                <registration available="no" open="yes"/>
                <searching>
                    <search available="yes" supportedParams="q"/>
                    <tv-search available="yes" supportedParams="q,ep,season"/>
                    <movie-search available="yes" supportedParams="q"/>
                </searching>
                <categories>
                    <category id="5070" name="Anime" description="Anime"/>
                    <category id="2020" name="Movies/Other" description="Movies (Anime)"/>
                </categories>
            </caps>
        """
    }

    @GetMapping("/api", produces = [MediaType.APPLICATION_XML_VALUE])
    suspend fun handleRequest(@RequestParam allParams: MultiValueMap<String, String>): String {
        logger.debug("Got request for: {}", allParams)

        val type = allParams.getFirst("t")
        if (type.equals("caps", ignoreCase = true)) {
            return CAPABILITIES_XML
        }

        return try {
            downloadService.handleQuery(allParams)
        } catch (ex: Exception) {
            logger.error("Error handling query: {}", allParams, ex)
            "<error>Unable to process the request</error>"
        }
    }

    @GetMapping("/download/{payload}")
    fun downloadFile(@PathVariable payload: String): ResponseEntity<ByteArrayResource> {
        val decodedFilename = URLDecoder.decode(payload, StandardCharsets.UTF_8)

        val stream = javaClass.classLoader.getResourceAsStream("fake.torrent")
            ?: throw FileNotFoundException("fake.torrent not found")

        val originalBytes = stream.use { it.readAllBytes() }

        val marker = "##META##".toByteArray(StandardCharsets.UTF_8)
        val extraBytes = decodedFilename.toByteArray(StandardCharsets.UTF_8)

        val combinedBytes = ByteArray(originalBytes.size + marker.size + extraBytes.size)
        System.arraycopy(originalBytes, 0, combinedBytes, 0, originalBytes.size)
        System.arraycopy(marker, 0, combinedBytes, originalBytes.size, marker.size)
        System.arraycopy(extraBytes, 0, combinedBytes, originalBytes.size + marker.size, extraBytes.size)

        val resource = ByteArrayResource(combinedBytes)

        return ResponseEntity.ok()
            .contentLength(combinedBytes.size.toLong())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$decodedFilename\"")
            .contentType(MediaType.parseMediaType("application/x-bittorrent"))
            .body(resource)
    }

    @GetMapping("/error", produces = [MediaType.APPLICATION_XML_VALUE])
    fun error(): String {
        return "<error>Internal server error</error>"
    }
}

fun main(args: Array<String>) {
    runApplication<ProxyController>(*args)
}
