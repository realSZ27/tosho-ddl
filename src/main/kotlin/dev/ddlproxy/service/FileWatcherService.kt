package dev.ddlproxy.service

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64

@Service
class FileWatcherService(
    @param:Value($$"${blackhole.folder}") private val blackholeFolder: String,
    private val downloadService: DownloadService
) : SmartLifecycle {

    companion object {
        private val logger = LoggerFactory.getLogger(FileWatcherService::class.java)
        private const val FILE_DEDUPLICATION_WINDOW_MS = 30_000
    }

    private val recentlyProcessedFiles = ConcurrentHashMap<Path, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    private var watchService: WatchService? = null
    private var job: Job? = null

    override fun start() {
        if (running) return
        running = true

        job = scope.launch {
            runWatcher()
        }

        logger.info("File watcher started")
    }

    override fun stop() {
        running = false

        try {
            watchService?.close()
        } catch (e: Exception) {
            logger.warn("Error closing WatchService", e)
        }

        job?.cancel()
        scope.cancel()

        logger.info("File watcher stopped")
    }

    override fun isRunning(): Boolean = running
    override fun isAutoStartup(): Boolean = true

    private suspend fun runWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService()
            val folderPath = Paths.get(blackholeFolder)

            folderPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE)

            logger.info("Watching folder: {}", folderPath)

            while (scope.isActive && running) {
                val key = try {
                    withContext(Dispatchers.IO) {
                        watchService!!.take()
                    }
                } catch (e: ClosedWatchServiceException) {
                    break
                } catch (e: InterruptedException) {
                    logger.info("Watcher interrupted (shutdown)")
                    break
                }

                key.pollEvents().forEach { event ->
                    if (event.kind() != StandardWatchEventKinds.ENTRY_CREATE) return@forEach

                    val createdPath = folderPath.resolve(event.context() as Path)

                    if (!createdPath.toString().lowercase().endsWith(".torrent")) return@forEach
                    if (wasRecentlyProcessed(createdPath)) return@forEach

                    processTorrentFile(createdPath)
                    markAsProcessed(createdPath)
                }

                if (!key.reset()) break
            }
        } catch (e: Exception) {
            logger.error("Watcher failed", e)
        } finally {
            watchService?.close()
        }
    }

    private fun wasRecentlyProcessed(path: Path): Boolean {
        val now = System.currentTimeMillis()

        recentlyProcessedFiles.entries.removeIf {
            now - it.value > FILE_DEDUPLICATION_WINDOW_MS
        }

        val last = recentlyProcessedFiles[path]
        return last != null && now - last <= FILE_DEDUPLICATION_WINDOW_MS
    }

    private fun markAsProcessed(path: Path) {
        recentlyProcessedFiles[path] = System.currentTimeMillis()
    }

    private suspend fun processTorrentFile(path: Path) {
        val fileName = path.fileName.toString()

        val fileBytes = try {
            Files.readAllBytes(path)
        } catch (e: Exception) {
            logger.error("Failed to read file: {}", fileName, e)
            return
        }

        try {
            Files.deleteIfExists(path)
        } catch (e: Exception) {
            logger.error("Delete failed: {}", fileName, e)
        }

        fun ByteArray.indexOfSequence(sequence: ByteArray): Int {
            outer@ for (i in 0..this.size - sequence.size) {
                for (j in sequence.indices) {
                    if (this[i + j] != sequence[j]) continue@outer
                }
                return i
            }
            return -1
        }

        val marker = "##META##".toByteArray(StandardCharsets.UTF_8)

        val markerIndex = fileBytes.indexOfSequence(marker)
        if (markerIndex == -1) {
            logger.error("Marker not found in file: {}", fileName)
            return
        }

        val payloadStart = markerIndex + marker.size
        val payloadBytes = fileBytes.copyOfRange(payloadStart, fileBytes.size)

        val decoded = try {
            val decodedBytes = Base64.UrlSafe.decode(payloadBytes)
            decodedBytes.decodeToString()
        } catch (e: Exception) {
            logger.error("Payload decode failed: {}", fileName, e)
            return
        }

        val parts = decoded.split("|", limit = 2)
        if (parts.size != 2) {
            logger.error("Invalid identifier: {}", decoded)
            return
        }

        coroutineScope {
            launch {
                downloadService.downloadRelease(parts[0], parts[1])
            }
        }
    }
}