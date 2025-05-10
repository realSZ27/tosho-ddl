package dev.ddlproxy;

import jakarta.annotation.PostConstruct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;


@SpringBootApplication
@RestController
@EnableScheduling
public class ProxyController {
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

    record DownloadLinks(
            ArrayList<String> links,
            String title
    ) {
        public ArrayList<String> getLinksInPriority() {
            links.sort(new Comparator<>() {
                @Override
                public int compare(String link1, String link2) {
                    return Integer.compare(getPriority(link1), getPriority(link2));
                }

                private int getPriority(String link) {
                    if (link.contains("gofile")) return 1; // Highest priority
                    if (link.contains("buzzheavier")) return 2; // Medium priority
                    return Integer.MAX_VALUE; // Default for unknown links
                }
            });

            return links;
        }
    }

    @GetMapping(value = "/api", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleRequest(@RequestParam MultiValueMap<String, String> allParams) {
        logger.debug("Got request for: {}", allParams);

        if ("caps".equalsIgnoreCase(allParams.getFirst("t"))) {
            return CAPABILITIES_XML;
        }

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
            }

            return doc.toString();
        } catch (IOException e) {
            return "<error><description>" + e.getMessage() + "</description></error>";
        } catch (Exception e) {
            return "<error><description>Unexpected server error</description></error>";
        }
    }

    private DownloadLinks scrape(String query) {
        DownloadLinks linksObject = new DownloadLinks(new ArrayList<>(), "");
        try {
            Document pageDoc = Jsoup.connect("https://animetosho.org/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:138.0) Gecko/20100101 Firefox/138.0")
                    .get();
            Elements entries = pageDoc.select("div.home_list_entry");

            for (Element entry : entries) {
                // Extract the release name
                Element entryInfoDiv = entry.selectFirst("div.link a");
                String releaseName = Objects.requireNonNull(entryInfoDiv).text();
                if(!releaseName.equals(query)) continue;

                // Extract the download links
                Elements linksElement = entry.select("div.links a.dllink, div.links a.website");

                ArrayList<String> links = new ArrayList<>();
                for (Element linkElement : linksElement) {
                    // Filter out "Torrent" and "Magnet" links
                    String linkText = linkElement.text();
                    if (!linkText.equals("Torrent") && !linkText.equals("Magnet")) {
                        links.add(linkElement.attr("href"));
                    }
                }

                // Create a DownloadLinks object
                linksObject = new DownloadLinks(links, releaseName);
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return linksObject;
    }

    @PostConstruct
    public void startWatcher() {
        Thread watcherThread = new Thread(() -> {
            try {
                checkSonarrBlackhole();
            } catch (IOException e) {
                logger.error("Error accessing Sonarr blackhole folder");
                throw new RuntimeException("Can't access blackhole folder");
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
                        DownloadLinks links = scrape(title);
                        if (links != null) {
                            sendToJDownloader(links);
                            if(file.delete()) {
                                logger.debug("Successfully deleted: {}", file.getName());
                            } else {
                                logger.error("Couldn't delete: {}", file.getName());
                            }
                        } else {
                            logger.error("Couldn't find release in search.");
                        }
                    } else {
                        logger.trace("New file wasn't torrent or nzb");
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