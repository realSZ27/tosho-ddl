package dev.ddlproxy;

import jakarta.annotation.PostConstruct;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main controller for the AnimeTosho Direct Download Link Proxy application.
 * Handles Newznab API requests, manages download links, and integrates with Sonarr/JDownloader.
 */
@SpringBootApplication
@RestController
@EnableScheduling
public class ProxyController {
    private static final Logger logger = LoggerFactory.getLogger(ProxyController.class);

    // In-memory store for download links keyed by title
    private static final Map<String, DownloadLinks> TITLE_TO_LINKS = new ConcurrentHashMap<>();

    // Configuration constants
    private static final String SONARR_DOWNLOAD_FOLDER = "C:\\Users\\karst\\Downloads\\";
    private static final String JDOWNLOADER_FOLDER = "D:\\apps\\JDownloader\\folderwatch\\";
    private static final String SONARR_BLACKHOLE_FOLDER = "\\\\100.90.104.92\\server\\media\\shows\\temp blackhole";
    private static final String THIS_BASE_URL = "http://100.93.85.111:8080";

    // Newznab capabilities XML response
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

    public static void main(String[] args) {
        SpringApplication.run(ProxyController.class, args);
        logger.info("AnimeTosho DDL Proxy application started");
    }

    /**
     * Record representing download links for various file hosts
     * @param buzzheavier Buzzheavier direct download link
     * @param gofile Gofile direct download link
     * @param krakenfiles KrakenFiles direct download link
     * @param multiup List of MultiUp links
     * @param title Media title
     */
    record DownloadLinks(
            String buzzheavier,
            String gofile,
            String krakenfiles,
            List<String> multiup,
            String title
    ) {}

    /**
     * Handles Newznab API requests
     * @param allParams Request parameters from Sonarr/Radarr
     * @return XML response in Newznab format
     */
    @GetMapping(value = "/api", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleNewznabRequest(@RequestParam MultiValueMap<String, String> allParams) {
        logger.debug("Received Newznab API request with parameters: {}", allParams);

        if ("caps".equalsIgnoreCase(allParams.getFirst("t"))) {
            logger.info("Serving capabilities XML");
            return CAPABILITIES_XML;
        }

        try {
            StringBuilder queryString = new StringBuilder("https://feed.animetosho.org/api?");
            List<String> qValues = new ArrayList<>();
            String seasonStr = null;
            String episodeStr = null;

            // Process parameters and build query string
            for (Map.Entry<String, List<String>> entry : allParams.entrySet()) {
                String key = entry.getKey();
                List<String> values = entry.getValue();

                if (key.equalsIgnoreCase("season") && !values.isEmpty()) {
                    seasonStr = String.format("S%02d", Integer.parseInt(values.getFirst()));
                } else if (key.equalsIgnoreCase("ep") && !values.isEmpty()) {
                    episodeStr = String.format("E%02d", Integer.parseInt(values.getFirst()));
                } else if (key.equalsIgnoreCase("q")) {
                    qValues.addAll(values);
                } else if (!key.equalsIgnoreCase("t")) { // Skip 't' parameter
                    for (String value : values) {
                        if (!queryString.toString().endsWith("?")) {
                            queryString.append("&");
                        }
                        // URL encode parameters with proper encoding
                        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
                        String encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
                        queryString.append(encodedKey).append("=").append(encodedValue);
                    }
                }
            }

            // Add season/episode suffix to search query
            if (!qValues.isEmpty()) {
                String suffix = (seasonStr != null || episodeStr != null)
                        ? " " + (seasonStr != null ? seasonStr : "") + (episodeStr != null ? episodeStr : "")
                        : "";
                for (String value : qValues) {
                    if (!queryString.toString().endsWith("?")) {
                        queryString.append("&");
                    }
                    String encodedValue = URLEncoder.encode(value + suffix, StandardCharsets.UTF_8)
                            .replace("+", "%20");
                    queryString.append("q=").append(encodedValue);
                }
            }

            logger.info("Proxying request to AnimeTosho: {}", queryString);
            Document doc = Jsoup.connect(queryString.toString())
                    .ignoreContentType(true)
                    .parser(Parser.xmlParser())
                    .timeout(10000)
                    .get();

            Elements items = doc.select("item");
            logger.debug("Processing {} items from AnimeTosho response", items.size());

            // Process each RSS item
            for (Element item : items) {
                Element desc = item.selectFirst("description");
                if (desc == null) continue;

                Element titleElement = item.selectFirst("title");
                if (titleElement == null) continue;

                String title = titleElement.text();
                String fakeUrl = THIS_BASE_URL + "/download/" + URLEncoder.encode(title, StandardCharsets.UTF_8);

                // Replace magnet links with our proxy URLs
                item.select("torznab\\:attr[name=magneturl]").forEach(Element::remove);

                // Update enclosure URLs
                item.select("enclosure").forEach(enclosure -> {
                    String type = enclosure.attr("type");
                    String extension = switch (type) {
                        case "application/x-bittorrent" -> ".torrent";
                        case "application/x-nzb" -> ".nzb";
                        default -> ".bin";
                    };
                    enclosure.attr("url", fakeUrl + extension);
                });

                // Extract and store download links
                Map<String, String> descLink = parseDescriptionLinks(desc.html());
                DownloadLinks links = new DownloadLinks(
                        descLink.getOrDefault("buzzheavier", ""),
                        descLink.getOrDefault("gofile", ""),
                        descLink.getOrDefault("krakenfiles", ""),
                        List.of(),
                        title);

                if (!title.isBlank()) {
                    TITLE_TO_LINKS.put(title.trim(), links);
                    logger.debug("Stored links for title: {}", title);
                }
            }

            return doc.toString();
        } catch (IOException e) {
            logger.error("Error processing AnimeTosho request", e);
            return "<error><description>" + e.getMessage() + "</description></error>";
        } catch (Exception e) {
            logger.error("Unexpected error processing request", e);
            return "<error><description>Unexpected server error</description></error>";
        }
    }

    /**
     * Parses download links from description HTML
     * @param html Description HTML content
     * @return Map of hoster names to URLs
     */
    private Map<String, String> parseDescriptionLinks(String html) {
        List<String> foundLinks = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?i)\"(http[^\"]+)\"").matcher(html);
        while (matcher.find()) foundLinks.add(matcher.group(1));

        Map<String, String> result = new HashMap<>();
        for (String link : foundLinks) {
            String l = link.toLowerCase();
            if (l.contains("gofile")) result.put("gofile", link);
            else if (l.contains("buzzheavier")) result.put("buzzheavier", link);
            else if (l.contains("krakenfiles")) result.put("krakenfiles", link);
        }

        logger.debug("Parsed {} links from description", result.size());
        return result;
    }

    @PostConstruct
    public void startWatcher() {
        logger.info("Initializing Sonarr blackhole folder watcher");
        Thread watcherThread = new Thread(() -> {
            try {
                checkSonarrBlackhole();
            } catch (IOException | InterruptedException e) {
                logger.error("Blackhole watcher thread failed", e);
            }
        });
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    /**
     * Monitors Sonarr's blackhole folder for new torrent/NZB files
     */
    public void checkSonarrBlackhole() throws IOException, InterruptedException {
        logger.info("Starting blackhole folder monitoring at: {}", SONARR_BLACKHOLE_FOLDER);

        WatchService watchService = FileSystems.getDefault().newWatchService();
        Path folderPath = Paths.get(SONARR_BLACKHOLE_FOLDER);
        folderPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

        while (true) {
            WatchKey key = watchService.take();

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    Path createdFile = folderPath.resolve((Path) event.context());
                    File file = createdFile.toFile();
                    logger.info("New file detected: {}", file.getName());

                    String name = file.getName();
                    if (name.toLowerCase().endsWith(".torrent") || name.toLowerCase().endsWith(".nzb")) {
                        int extensionIndex = name.lastIndexOf('.');
                        if (extensionIndex == -1) continue;

                        String title = name.substring(0, extensionIndex);
                        logger.debug("Processing title: {}", title);
                        DownloadLinks links = TITLE_TO_LINKS.get(title);

                        if (links != null) {
                            logger.info("Found matching links for title: {}", title);
                            sendToJDownloader(links);
                            if (file.delete()) {
                                logger.debug("Successfully deleted processed file: {}", name);
                            } else {
                                logger.warn("Failed to delete file: {}", name);
                            }
                        } else {
                            logger.warn("No links found for title: {}", title);
                        }
                    } else {
                        logger.debug("Ignoring non-torrent/nzb file: {}", name);
                    }
                }
            }

            if (!key.reset()) {
                logger.error("Watch key no longer valid");
                break;
            }
        }
    }

    /**
     * Selects the best available download link
     */
    private String pickBestLink(DownloadLinks links) {
        if (!links.gofile().isEmpty()) return links.gofile();
        if (!links.buzzheavier().isEmpty()) return links.buzzheavier();
        if (!links.krakenfiles().isEmpty()) return links.krakenfiles();
        if (!links.multiup().isEmpty()) return links.multiup().getFirst();
        return null;
    }

    @GetMapping(value = "/links", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, DownloadLinks> getLinks() {
        logger.debug("Request received for stored links (count: {})", TITLE_TO_LINKS.size());
        return TITLE_TO_LINKS;
    }

    /**
     * Handles fake download requests from Sonarr
     * @param filename Requested filename
     * @return Fake torrent/NZB file
     */
    @GetMapping("/download/{filename}")
    public ResponseEntity<ByteArrayResource> downloadFile(@PathVariable String filename) throws IOException {
        String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
        logger.info("Fake download requested for: {}", decodedFilename);

        // Load fake torrent content
        Path path = Paths.get("src/main/resources/fake.torrent");
        byte[] fileContent = Files.readAllBytes(path);

        // Determine content type
        String contentType = "application/octet-stream";
        if (decodedFilename.endsWith(".torrent")) {
            contentType = "application/x-bittorrent";
        } else if (decodedFilename.endsWith(".nzb")) {
            contentType = "application/x-nzb";
        }

        logger.debug("Serving fake download with content type: {}", contentType);
        return ResponseEntity.ok()
                .contentLength(fileContent.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + decodedFilename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new ByteArrayResource(fileContent));
    }

    /**
     * Creates JDownloader crawljob file with download information
     * @param links Download links to process
     */
    private void sendToJDownloader(DownloadLinks links) {
        logger.info("Creating JDownloader crawljob for: {}", links.title());
        try {
            Path filePath = Paths.get(JDOWNLOADER_FOLDER + links.title() + ".crawljob");
            String bestLink = pickBestLink(links);

            if (bestLink == null) {
                logger.error("No valid links found for title: {}", links.title());
                return;
            }

            String content = String.format("""
                    downloadFolder=%s
                    text=%s
                    enabled=TRUE
                    packageName=%s
                    autoConfirm=TRUE
                    autoStart=TRUE
                    extractAfterDownload=TRUE
                    """,
                    SONARR_DOWNLOAD_FOLDER,
                    bestLink,
                    links.title());

            Files.writeString(filePath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("JDownloader crawljob created at: {}", filePath);

        } catch (Exception e) {
            logger.error("Failed to create JDownloader crawljob", e);
        }
    }
}