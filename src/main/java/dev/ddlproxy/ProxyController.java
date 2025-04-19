package dev.ddlproxy;

import jakarta.annotation.PostConstruct;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@SpringBootApplication
@RestController
@EnableScheduling
public class ProxyController {

    private static final Map<String, DownloadLinks> TITLE_TO_LINKS = new ConcurrentHashMap<>();

    private static final Logger logger  = LoggerFactory.getLogger(ProxyController.class);

    private final String JDOWNLOADER_API_URL;
    private final String SONARR_DOWNLOAD_FOLDER;
    private final String SONARR_BLACKHOLE_FOLDER;
    private final String THIS_BASE_URL;

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

    public ProxyController(
            @Value("${jdownloader.api.url}") String jdownloaderApiUrl,
            @Value("${download.folder}") String downloadFolder,
            @Value("${blackhole.folder}") String blackholeFolder,
            @Value("${base.url}") String baseUrl
    ) {
        this.SONARR_DOWNLOAD_FOLDER = downloadFolder;
        this.SONARR_BLACKHOLE_FOLDER = blackholeFolder;
        if(baseUrl.endsWith("/")) {
            this.THIS_BASE_URL = baseUrl.substring(0, baseUrl.length() - 1);
            logger.debug("removed / from THIS_BASE_URL");
        } else {
            this.THIS_BASE_URL = baseUrl;
        }
        if(jdownloaderApiUrl.endsWith("/")) {
            this.JDOWNLOADER_API_URL = jdownloaderApiUrl.substring(0, jdownloaderApiUrl.length() - 1);
            logger.debug("removed / from JDOWNLOADER_API_URL");
        } else {
            this.JDOWNLOADER_API_URL = jdownloaderApiUrl;
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(ProxyController.class, args);
    }

    // TODO: Support more hosts
    record DownloadLinks(
            String buzzheavier,
            String gofile,
            String krakenfiles,

            String title
    ) {
        public String[] getLinksInPriority() {
            return new String[]{gofile(), buzzheavier(), krakenfiles()};
        }
    }

    @GetMapping(value = "/api", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleRequest(@RequestParam MultiValueMap<String, String> allParams) {
        logger.debug("Got request for: {}", allParams);

        if ("caps".equalsIgnoreCase(allParams.getFirst("t"))) {
            return CAPABILITIES_XML;
        }

        try {
            StringBuilder queryString = new StringBuilder("https://feed.animetosho.org/api?");
            List<String> qValues = new ArrayList<>();
            String seasonStr = null;
            String episodeStr = null;

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
                        // Correct encoding by replacing '+' with '%20'
                        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
                        String encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
                        queryString.append(encodedKey).append("=").append(encodedValue);
                    }
                }
            }

            // Append modified 'q' parameters
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

            // Rest of the code to fetch and process XML...
            Document doc = Jsoup.connect(queryString.toString())
                    .ignoreContentType(true)
                    .parser(Parser.xmlParser())
                    .timeout(10000)
                    .get();

            Elements items = doc.select("item");

            for (Element item : items) {
                Element desc = item.selectFirst("description");
                if (desc == null) continue;

                Element titleElement = item.selectFirst("title");
                if (titleElement == null) continue;

                String title = titleElement.text();


                String fakeUrl = THIS_BASE_URL + "/download/" + URLEncoder.encode(title, StandardCharsets.UTF_8) + ".torrent";
                logger.trace("Base URL in replace torrent url: \"{}\"", THIS_BASE_URL);
                logger.trace("Full torrent url: {}", fakeUrl);

                // Replace the magnet link in the XML
                Elements magnetUrlElements = item.select("torznab\\:attr[name=magneturl]");
                for (Element magnetUrlElement : magnetUrlElements) {
                    magnetUrlElement.remove();
                }

                item.select("enclosure").forEach(enclosure -> enclosure.attr("url", fakeUrl));

                Map<String, String> descLink = parseDescriptionLinks(desc.html());
                DownloadLinks links = new DownloadLinks(
                        descLink.getOrDefault("buzzheavier", ""),
                        descLink.getOrDefault("gofile", ""),
                        descLink.getOrDefault("krakenfiles", ""),
                        title);

                if (!title.isBlank()) {
                    TITLE_TO_LINKS.put(title.trim(), links);
                }
            }

            logger.trace("Title to links: {}", TITLE_TO_LINKS);

            return doc.toString();
        } catch (IOException e) {
            return "<error><description>" + e.getMessage() + "</description></error>";
        } catch (Exception e) {
            return "<error><description>Unexpected server error</description></error>";
        }
    }

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

        return result;
    }

    @PostConstruct
    public void startWatcher() {
        Thread watcherThread = new Thread(() -> {
            try {
                checkSonarrBlackhole();
            } catch (IOException e) {
                logger.error("Error accessing Sonarr blackhole folder");
            } catch (InterruptedException e) {
                logger.error("Filesystem watch service was interrupted");
            }
        });
        watcherThread.start();
    }

    public void checkSonarrBlackhole() throws IOException, InterruptedException {
        WatchService watchService = FileSystems.getDefault().newWatchService();
        Path folderPath = Paths.get(SONARR_BLACKHOLE_FOLDER);
        folderPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

        logger.trace("Starting filesystem watch service in path: {}", folderPath);

        while (true) {
            WatchKey key = watchService.take();

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    logger.debug("New file created in blackhole folder");
                    Path createdFile = folderPath.resolve((Path) event.context());
                    File file = createdFile.toFile();

                    String name = file.getName();
                    if (name.toLowerCase().endsWith(".torrent")) {
                        int extensionIndex = name.lastIndexOf('.');
                        if (extensionIndex == -1) continue;

                        String title = name.substring(0, extensionIndex);
                        logger.trace("Title is: {}", title);
                        DownloadLinks links = TITLE_TO_LINKS.get(title);
                        if (links != null) {
                            sendToJDownloader(links);
                            if(file.delete()) {
                                logger.debug("Successfully deleted: {}", file.getName());
                            } else {
                                logger.error("Couldn't delete: {}", file.getName());
                            }
                        } else {
                            logger.warn("Couldn't find release in cache. If you just restarted this might not be an issue.");
                        }
                    } else {
                        logger.debug("New file wasn't torrent or nzb");
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) break;
        }
    }

    // Function to make a fake download
    @GetMapping("/download/{filename}")
    public ResponseEntity<ByteArrayResource> downloadFile(@PathVariable String filename) throws IOException {
        filename = URLDecoder.decode(filename, StandardCharsets.UTF_8);

        byte[] fileContent;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("fake.torrent")) {
            if (is == null) {
                throw new FileNotFoundException("fake.torrent not found in resources");
            }
            fileContent = is.readAllBytes();
        }

        ByteArrayResource resource = new ByteArrayResource(fileContent);

        return ResponseEntity.ok()
                .contentLength(fileContent.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/x-bittorrent"))
                .body(resource);
    }

    private void sendToJDownloader(DownloadLinks links) {
        JDownloaderController jDownloader = new JDownloaderController(JDOWNLOADER_API_URL);
        for(String link : links.getLinksInPriority()) {
            if(jDownloader.isLinkOnline(link)) {
                jDownloader.download(link, SONARR_DOWNLOAD_FOLDER);
                logger.debug("Link \"{}\" is online", link);
                return;
            }
        }

        // Put this on a Sonarr blacklist? For now a warning is probably fine.
        logger.warn("No valid links found for \"{}.\" This is not necessarily an error. Most likely, all the links were just expired.", links.title());
    }
}