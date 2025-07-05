package dev.ddlproxy.service;

import dev.ddlproxy.model.DownloadLinks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;

public class FileWatcherService implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(FileWatcherService.class);

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

                    Path createdFile = folderPath.resolve((Path) event.context());
                    File file = createdFile.toFile();
                    String name = file.getName();

                    if (!name.toLowerCase().endsWith(".torrent")) {
                        logger.trace("New file wasn't torrent");
                        continue;
                    }

                    int extensionIndex = name.lastIndexOf('.');
                    if (extensionIndex == -1) continue;

                    String title = name.substring(0, extensionIndex);
                    logger.trace("Title is: {}", title);

                    DownloadLinks links = downloadService.getLinks(title);
                    if (links == null) {
                        logger.error("Couldn't find release in search.");
                        continue;
                    }

                    JDownloaderController jDownloader = new JDownloaderController(jdownloaderApiUrl);

                    for (ArrayList<String> link : links.getLinksInPriority()) {
                        if (!jDownloader.isLinkOnline(link)) {
                            logger.trace("Link \"{}\" is not online... Trying next host", link);
                            continue;
                        }

                        jDownloader.download(link, downloadFolder);
                        logger.debug("Link \"{}\" is online", link);
                        break;
                    }

                    if (!file.delete()) {
                        logger.error("Couldn't delete: {}", file.getName());
                    } else {
                        logger.debug("Successfully deleted: {}", file.getName());
                    }
                }

                if (!key.reset()) break;
            }

        } catch (IOException e) {
            logger.error("Error accessing Sonarr blackhole folder", e);
            throw new RuntimeException("Can't access blackhole folder", e);
        } catch (InterruptedException e) {
            logger.error("Filesystem watch service was interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

}
