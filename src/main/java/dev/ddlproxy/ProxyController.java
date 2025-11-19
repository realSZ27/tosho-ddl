package dev.ddlproxy;

import dev.ddlproxy.service.DownloadService;
import dev.ddlproxy.service.FileWatcherService;
import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@RestController
public class ProxyController {
    private static final Logger logger = LoggerFactory.getLogger(ProxyController.class);
    private static final String CAPABILITIES_XML = """
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
            """;

    private int retryCount = 0;
    private final int MAX_RETRY_ATTEMPTS = 10;

    private final String jdownloaderApiUrl;
    private final String sonarrDownloadFolder;
    private final String sonarrBlackholeFolder;

    private final DownloadService downloadService;

    public ProxyController(
            @Value("${jdownloader.api.url}") String jdownloaderApiUrl,
            @Value("${download.folder}") String downloadFolder,
            @Value("${blackhole.folder}") String blackholeFolder,
            @Value("${base.url}") String baseUrl
    ) {
        this.sonarrDownloadFolder = downloadFolder;
        this.sonarrBlackholeFolder = blackholeFolder;
        String baseUrlFixed = baseUrl.replaceAll("/$", "");
        this.jdownloaderApiUrl = jdownloaderApiUrl.replaceAll("/$", "");
        this.downloadService = new DownloadService(baseUrlFixed);
    }

    public static void main(String[] args) {
        SpringApplication.run(ProxyController.class, args);
    }

    @GetMapping(value = "/api", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleRequest(@RequestParam MultiValueMap<String, String> allParams) {
        logger.debug("Got request for: {}", allParams.toString());
        String type = allParams.getFirst("t");
        if ("caps".equalsIgnoreCase(type)) {
            return CAPABILITIES_XML;
        }
        try {
            return downloadService.handleQuery(allParams);
        } catch (Exception ex) {
            logger.error("Error handling query: {}", allParams, ex);
            return "<error>Unable to process the request</error>";
        }
    }

    @GetMapping("/download/{filename}")
    public ResponseEntity<ByteArrayResource> downloadFile(@PathVariable String filename) throws IOException {
        String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("fake.torrent")) {
            if (is == null) {
                throw new FileNotFoundException("fake.torrent not found");
            }
            byte[] fileContent = is.readAllBytes();

            if (fileContent == null) {
                throw new RuntimeException("could not read content of torrent file");
            }

            ByteArrayResource resource = new ByteArrayResource(fileContent);
            return ResponseEntity.ok()
                    .contentLength(fileContent.length)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + decodedFilename + "\"")
                    .contentType(MediaType.parseMediaType("application/x-bittorrent"))
                    .body(resource);
        }
    }

    // extra recovery protection
    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.SECONDS)
    @PostConstruct
    public void startWatcher() {
        try {
            Runnable watcher = new FileWatcherService(sonarrBlackholeFolder, sonarrDownloadFolder, jdownloaderApiUrl, downloadService);
            new Thread(watcher).start();
            retryCount = 0;
        } catch (Exception e) {
            retryCount++;
            logger.error("Failed to start the file watcher, retrying... (Attempt {}/{}). Error {}", retryCount, MAX_RETRY_ATTEMPTS, e);
            
            if (retryCount >= MAX_RETRY_ATTEMPTS) {
                logger.error("Max retry attempts reached. Exiting application...");
                System.exit(1);
            }
        }
    }
}