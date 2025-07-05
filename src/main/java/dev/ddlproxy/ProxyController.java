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

@SpringBootApplication
@RestController
public class ProxyController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyController.class);
    private static final String CAPABILITIES_XML = """
            <caps>
                <server version="1.0" title="AnimeTosho DDL Proxy" strapline="AnimeTosho DDLs for Sonarr" url="https://github.com/realSZ27"/>
                <limits max="200" default="75"/>
                <retention days="9999"/>
                <registration available="no" open="yes"/>
                <searching>
                    <search available="yes" supportedParams="q"/>
                    <tv-search available="yes" supportedParams="q,ep,season"/>
                    <movie-search available="no" supportedParams="q"/>
                </searching>
                <categories>
                    <category id="5070" name="Anime" description="Anime"/>
                    <category id="2020" name="Movies/Other" description="Movies (Anime)"/>
                </categories>
            </caps>
            """;

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
        LOGGER.debug("Got request for: {}", allParams.toString());
        String type = allParams.getFirst("t");
        if ("caps".equalsIgnoreCase(type)) {
            return CAPABILITIES_XML;
        }
        try {
            return downloadService.handleQuery(allParams);
        } catch (Exception ex) {
            LOGGER.error("Error handling query: {}", allParams, ex);
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
            ByteArrayResource resource = new ByteArrayResource(fileContent);
            return ResponseEntity.ok()
                    .contentLength(fileContent.length)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + decodedFilename + "\"")
                    .contentType(MediaType.parseMediaType("application/x-bittorrent"))
                    .body(resource);
        }
    }

    @PostConstruct
    public void startWatcher() {
        Runnable watcher = new FileWatcherService(sonarrBlackholeFolder, sonarrDownloadFolder, jdownloaderApiUrl, downloadService);
        new Thread(watcher).start();
    }
}