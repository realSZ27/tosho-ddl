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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

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
import java.util.stream.Collectors;


@SpringBootApplication
@RestController
@EnableScheduling
public class ProxyController {

    private static final Map<String, DownloadLinks> TITLE_TO_LINKS = new ConcurrentHashMap<>();

    private static final Logger logger  = LoggerFactory.getLogger(ProxyController.class);

    @Value("${jdownloader.api.url}") private final String JDOWNLOADER_API_URL;
    @Value("${download.folder}") private final String SONARR_DOWNLOAD_FOLDER;
    @Value("${blackhole.folder}") private final String SONARR_BLACKHOLE_FOLDER;
    @Value("${base.url}") private final String THIS_BASE_URL;

    private final RestTemplate restTemplate = new RestTemplate();

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
            @Value("${base.url}") String baseUrl) {
        this.JDOWNLOADER_API_URL = jdownloaderApiUrl;
        this.SONARR_DOWNLOAD_FOLDER = downloadFolder;
        this.SONARR_BLACKHOLE_FOLDER = blackholeFolder;
        this.THIS_BASE_URL = baseUrl;
    }

    public static void main(String[] args) {
        SpringApplication.run(ProxyController.class, args);
    }

    record DownloadLinks(
            String buzzheavier,
            String gofile,
            String krakenfiles,
            List<String> multiup,
            String title
    ) {}

    @GetMapping(value = "/api", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleNewznabRequest(@RequestParam MultiValueMap<String, String> allParams) {
        System.out.println("Got request for: " + allParams);

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

                String fakeUrl = THIS_BASE_URL + "/download/" + URLEncoder.encode(title, StandardCharsets.UTF_8);

                // Replace the magnet link in the XML
                Elements magnetUrlElements = item.select("torznab\\:attr[name=magneturl]");
                for (Element magnetUrlElement : magnetUrlElements) {
                    magnetUrlElement.remove();
                }

                item.select("enclosure").forEach(enclosure -> {
                    String type = enclosure.attr("type");

                    // Determine extension based on MIME type
                    String extension = switch (type) {
                        case "application/x-bittorrent" -> ".torrent";
                        case "application/x-nzb" -> ".nzb";
                        default -> ".bin"; // fallback or unknown type
                    };

                    enclosure.attr("url", fakeUrl + extension);
                });

//                item.select("torznab\\:attr[name=infohash]").remove();

                Map<String, String> descLink = parseDescriptionLinks(desc.html());
                DownloadLinks links = new DownloadLinks(
                        descLink.getOrDefault("buzzheavier", ""),
                        descLink.getOrDefault("gofile", ""),
                        descLink.getOrDefault("krakenfiles", ""),
                        List.of(),
                        title);

                if (!title.isBlank()) {
                    TITLE_TO_LINKS.put(title.trim(), links);
                }
            }

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
            } catch (IOException | InterruptedException e) {
                e.printStackTrace(); // Log the exception
            }
        });
        // watcherThread.setDaemon(true); // Optional: allows JVM to exit if this is the only thread left
        watcherThread.start();
    }

    public void checkSonarrBlackhole() throws IOException, InterruptedException {
        System.out.println("blackhole watcher starting");

        WatchService watchService = FileSystems.getDefault().newWatchService();
        Path folderPath = Paths.get(SONARR_BLACKHOLE_FOLDER);
        folderPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

        while (true) {
            WatchKey key = watchService.take(); // wait for a file to be created

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    System.out.println("New file created in blackhole folder");
                    Path createdFile = folderPath.resolve((Path) event.context());
                    File file = createdFile.toFile();

                    String name = file.getName();
                    if (name.toLowerCase().endsWith(".torrent") || name.toLowerCase().endsWith(".nzb")) {
                        int extensionIndex = name.lastIndexOf('.');
                        if (extensionIndex == -1) continue;

                        String title = name.substring(0, extensionIndex);
                        System.out.println("Title is: " + title);
                        DownloadLinks links = TITLE_TO_LINKS.get(title);
                        if (links != null) {
                            sendToJDownloader(links);
                            file.delete();
                        } else {
                            System.out.println("Links was null (couldnt find release)");
                        }
                    } else {
                        System.out.println("New file wasnt torrent or nzb");
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) break;
        }
    }

    @GetMapping(value = "/links", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, DownloadLinks> getLinks() {
        return TITLE_TO_LINKS;
    }

    // Function to make a fake download
    @GetMapping("/download/{filename}")
    public ResponseEntity<ByteArrayResource> downloadFile(@PathVariable String filename) throws IOException {
        filename = URLDecoder.decode(filename, StandardCharsets.UTF_8);

        // Fake torrent file
        Path path = Paths.get("src/main/resources/fake.torrent");
        byte[] fileContent = Files.readAllBytes(path);

        ByteArrayResource resource = new ByteArrayResource(fileContent);

        // Guess content type
        String contentType = "application/octet-stream";
        if (filename.endsWith(".torrent")) contentType = "application/x-bittorrent";
        else if (filename.endsWith(".nzb")) contentType = "application/x-nzb";

        return ResponseEntity.ok()
                .contentLength(fileContent.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private void sendToJDownloader(DownloadLinks links) {
        String[] linksInOrder = {links.gofile(), links.buzzheavier(), links.krakenfiles()};
                download(linksInOrder[0]);
    }

    private boolean isAvailable(String link) {
        if (link == null || link.isBlank()) return false;

        try {
            // 1. Add link to temporary package (disabled)
            Map<String, Object> addPayload = Map.of(
                    "links", link,
                    "enabled", false,
                    "autostart", false
            );

            ResponseEntity<Map<String, Object>> addResponse = restTemplate.exchange(
                    JDOWNLOADER_API_URL + "/linkgrabberv2/addLinks",
                    HttpMethod.POST,
                    new HttpEntity<>(addPayload, createHeaders()),
                    new ParameterizedTypeReference<>() {}
            );

            if (addResponse.getBody() == null || !addResponse.getStatusCode().is2xxSuccessful()) {
                return false;
            }

            // 2. Get job ID from response
            Map<String, Object> responseData = (Map<String, Object>) addResponse.getBody().get("data");
            if (responseData == null) return false;

            Long jobId = ((Number) responseData.get("id")).longValue();
            if (jobId == null) return false;

            // 3. Wait for job to complete and links to be processed
            boolean jobCompleted = false;
            for (int i = 0; i < 5; i++) { // Retry up to 5 times
                Thread.sleep(2000);

                Map<String, Object> jobQuery = Map.of("jobIds", Collections.singletonList(jobId));
                ResponseEntity<Map<String, Object>> jobStatusResponse = restTemplate.exchange(
                        JDOWNLOADER_API_URL + "/linkgrabberv2/queryLinkCrawlerJobs",
                        HttpMethod.POST,
                        new HttpEntity<>(jobQuery, createHeaders()),
                        new ParameterizedTypeReference<>() {}
                );

                if (jobStatusResponse.getBody() != null) {
                    List<Map<String, Object>> jobs = (List<Map<String, Object>>) jobStatusResponse.getBody().get("data");
                    if (jobs != null && !jobs.isEmpty()) {
                        Map<String, Object> job = jobs.get(0);
                        if ("finished".equals(job.get("status"))) {
                            jobCompleted = true;
                            break;
                        }
                    }
                }
            }

            if (!jobCompleted) return false;

            // 4. Query all links in linkgrabber with this URL
            Map<String, Object> linkQuery = Map.of(
                    "url", true,
                    "maxResults", 100,
                    "urlDisplayTypes", Collections.singletonList("ORIGIN")
            );

            ResponseEntity<Map<String, Object>> linksResponse = restTemplate.exchange(
                    JDOWNLOADER_API_URL + "/linkgrabberv2/queryLinks",
                    HttpMethod.POST,
                    new HttpEntity<>(linkQuery, createHeaders()),
                    new ParameterizedTypeReference<>() {}
            );

            if (linksResponse.getBody() == null) return false;

            List<Map<String, Object>> links = (List<Map<String, Object>>) linksResponse.getBody().get("data");
            boolean isAvailable = links.stream()
                    .filter(l -> link.equals(l.get("url")))
                    .anyMatch(l -> "online".equalsIgnoreCase((String) l.get("availability")));

            // 5. Cleanup temporary links
            List<Long> linkIds = links.stream()
                    .filter(l -> link.equals(l.get("url")))
                    .map(l -> ((Number) l.get("uuid")).longValue())
                    .collect(Collectors.toList());

            if (!linkIds.isEmpty()) {
                restTemplate.postForEntity(
                        JDOWNLOADER_API_URL + "/linkgrabberv2/removeLinks",
                        new HttpEntity<>(Map.of("linkIds", linkIds), createHeaders()),
                        Void.class
                );
            }

            return isAvailable;
        } catch (Exception e) {
            logger.error("Availability check failed for {}", link, e);
            return false;
        }
    }

    private void download(String link) {
        try {
            // Add link with autostart and proper folder
            Map<String, Object> payload = Map.of(
                    "links", link,
                    "autostart", true,
                    "destinationFolder", SONARR_DOWNLOAD_FOLDER,
                    "enabled", true
            );

            restTemplate.postForEntity(
                    JDOWNLOADER_API_URL + "/linkgrabberv2/addLinks",
                    new HttpEntity<>(payload, createHeaders()),
                    Void.class
            );

            logger.info("Started download: {}", link);
        } catch (Exception e) {
            logger.error("Failed to start download for {}", link, e);
        }
    }

    // Helper method for headers
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}