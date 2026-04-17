package dev.ddlproxy.service

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.graalvm.polyglot.Context
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
class KwikDownloader(
    private val client: HttpClient,
    @param:Value($$"${download.folder}") private val downloadFolderRaw: String,
) {
    
    private val downloadFolder = downloadFolderRaw.trim().trimEnd('/')
    private val logger: Logger = LoggerFactory.getLogger(KwikDownloader::class.java)
    private val referer = "https://kwik.cx/"
    private val parallelism = 8

    suspend fun download(url: String, filename: String) {
        val outputFile = File(downloadFolder, filename)

        val cookie = generateRandomCookie()
        logger.info(cookie)

        client.use { client ->
            logger.info("Resolving kwik URL...")
            val m3u8Url = resolveKwikToM3U8(url, cookie)

            logger.info("M3U8: $m3u8Url")
            logger.info("Starting download...")

            downloadEpisodeStreaming(
                m3u8Url = m3u8Url,
                outputFile = outputFile,
                cookie = cookie
            )

            logger.info("Download complete: ${outputFile.absolutePath}")
        }
    }

    private suspend fun resolveKwikToM3U8(
        url: String,
        cookie: String
    ): String {

        val html = client.get(url) {
            header(HttpHeaders.Referrer, referer)
            header(HttpHeaders.Cookie, "__ddg2_=$cookie")
        }.bodyAsText()

        val scriptContent = Regex("<script>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)
            ?.groupValues?.get(1)
            ?: error("No script tag found")

        val transformedScript = scriptContent
            .replace("document", "process")
            .replace("querySelector", "exit")
            .replace("eval(", "console.log(")

        val output = runJs(transformedScript)
            ?: error("JS execution failed")

        return Regex("const source='(https?://[^']+\\.m3u8)")
            .find(output)
            ?.groupValues?.get(1)
            ?: error("No m3u8 URL found")
    }

    private fun runJs(js: String): String? {
        val outStream = ByteArrayOutputStream()

        val context = Context.newBuilder("js")
            .out(outStream)
            .allowAllAccess(true)
            .build()

        return try {
            context.eval("js", """
                globalThis.process = {};
                globalThis.process.exit = function() {};
                globalThis.exit = function() {};
                globalThis.console = {
                    log: function(...args) {
                        print(args.join(' '));
                    }
                };
            """.trimIndent())

            context.eval("js", js)

            outStream.toString()
        } catch (e: Exception) {
            logger.error("JS execution failed: {}", e.message)
            null
        } finally {
            context.close()
        }
    }

    private data class Playlist(
        val segments: List<String>,
        val keyUrl: String?
    )

    private suspend fun downloadPlaylist(m3u8Url: String, cookie: String): String {
        return client.get(m3u8Url) {
            header(HttpHeaders.Referrer, referer)
            header(HttpHeaders.Cookie, "__ddg2_=$cookie")
        }.bodyAsText()
    }

    private fun parsePlaylist(m3u8: String): Playlist {
        val lines = m3u8.lines()

        val segments = lines.filter { it.startsWith("http") }

        val keyUrl = lines
            .firstOrNull { it.startsWith("#EXT-X-KEY") }
            ?.let {
                Regex("URI=\"([^\"]+)\"")
                    .find(it)
                    ?.groupValues?.get(1)
            }

        return Playlist(segments, keyUrl)
    }

    private suspend fun downloadEpisodeStreaming(
        m3u8Url: String,
        outputFile: File,
        cookie: String
    ) = coroutineScope {

        val playlistText = downloadPlaylist(m3u8Url, cookie)
        val playlist = parsePlaylist(playlistText)

        require(playlist.segments.isNotEmpty()) {
            "No segments found"
        }

        val key = playlist.keyUrl?.let {
            downloadKey(it, cookie)
        }

        val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)

        var completed = 0
        val total = playlist.segments.size

        outputFile.outputStream().buffered().use { output ->

            playlist.segments.chunked(parallelism).forEach { batch ->

                val deferred = batch.map { url ->
                    async(dispatcher) {
                        downloadAndDecryptSegment(url, cookie, key)
                    }
                }

                val results = deferred.awaitAll()

                for (segment in results) {
                    output.write(segment)

                    completed++
                    if (completed % 25 == 0 || completed == total) {
                        logger.info("Progress: $completed / $total")
                    }
                }
            }
        }
    }

    private suspend fun downloadAndDecryptSegment(
        url: String,
        cookie: String,
        key: ByteArray?
    ): ByteArray {

        val encrypted = client.get(url) {
            header(HttpHeaders.Referrer, referer)
            header(HttpHeaders.Cookie, "__ddg2_=$cookie")
        }.body<ByteArray>()

        return if (key != null) {
            val cipher = createCipher(key)
            cipher.doFinal(encrypted)
        } else {
            encrypted
        }
    }

    private suspend fun downloadKey(
        keyUrl: String,
        cookie: String
    ): ByteArray {
        return client.get(keyUrl) {
            header(HttpHeaders.Referrer, referer)
            header(HttpHeaders.Cookie, "__ddg2_=$cookie")
        }.body()
    }

    private fun createCipher(key: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(ByteArray(16))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher
    }

    private fun generateRandomCookie(): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..16).map { chars.random() }.joinToString("")
    }
}