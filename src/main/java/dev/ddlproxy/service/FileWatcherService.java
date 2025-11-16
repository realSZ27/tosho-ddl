package dev.ddlproxy.service;

import dev.ddlproxy.model.DownloadLinks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FileWatcherService implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(FileWatcherService.class);
    private static final long FILE_DEDUPLICATION_WINDOW_MS = 30_000;

    private final Map<Path, Long> recentlyProcessedFiles = new ConcurrentHashMap<>();

    private final String blackholeFolder;
    private final String downloadFolder;
    private final String jdownloaderApiUrl;
    private final DownloadService downloadService;

    public FileWatcherService(String blackholeFolder, String downloadFolder, String jdownloaderApiUrl, DownloadService downloadService) {
        this.blackholeFolder = blackholeFolder;
        this.downloadFolder = downloadFolder;
        this.jdownloaderApiUrl = jdownloaderApiUrl;
        this.downloadService = downloadService;
    }

    @Override
    public void run() {
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            Path folderPath = Paths.get(blackholeFolder);
            folderPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            logger.trace("Starting filesystem watch service in path: {}", folderPath);

            while (true) {
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() != StandardWatchEventKinds.ENTRY_CREATE) continue;

                    Path createdPath = folderPath.resolve((Path) event.context());
                    File file = createdPath.toFile();

                    if (!file.getName().toLowerCase().endsWith(".torrent")) {
                        logger.trace("New file wasn't a torrent");
                        continue;
                    }

                    // Skip if this file was recently processed
                    if (wasRecentlyProcessed(createdPath)) {
                        logger.trace("File {} was already processed recently, skipping...", file.getName());
                        continue;
                    }

                    processTorrentFile(file);
                    markAsProcessed(createdPath);
                }

                if (!key.reset()) break;
            }

        } catch (IOException e) {
            logger.error("Error accessing Sonarr blackhole folder", e);
            throw new RuntimeException("Can't access blackhole folder", e);
        } catch (InterruptedException e) {
            logger.error("Filesystem watch service was interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Unexpected error in file watcher service", e);
        }
    }

    private boolean wasRecentlyProcessed(Path filePath) {
        long now = System.currentTimeMillis();
        Long lastProcessed = recentlyProcessedFiles.get(filePath);

        // Clean up old entries
        recentlyProcessedFiles.entrySet().removeIf(entry -> now - entry.getValue() > FILE_DEDUPLICATION_WINDOW_MS);

        return lastProcessed != null && now - lastProcessed <= FILE_DEDUPLICATION_WINDOW_MS;
    }

    private void markAsProcessed(Path filePath) {
        recentlyProcessedFiles.put(filePath, System.currentTimeMillis());
    }

    private void processTorrentFile(File file) {
        String name = file.getName();
        int extensionIndex = name.lastIndexOf('.');
        if (extensionIndex == -1) return;

        String title = name.substring(0, extensionIndex);
        logger.trace("Title is: {}", title);

        DownloadLinks links = downloadService.getLinks(title);
        if (links == null) {
            logger.error("Couldn't find release in search.");
            return;
        }

        JDownloaderController jDownloader = new JDownloaderController(jdownloaderApiUrl);

        for (ArrayList<String> link : links.getLinksInPriority()) {
            if (!jDownloader.isLinkOnline(link)) {
                logger.trace("Link \"{}\" is not online... Trying next host", link);
                continue;
            }

            logger.debug("Link \"{}\" is online", link);
            jDownloader.download(link, downloadFolder);
            break;
        }

        if (!file.delete()) {
            logger.error("Couldn't delete: {}", file.getName());
        } else {
            logger.debug("Successfully deleted: {}", file.getName());
        }
    }
}
