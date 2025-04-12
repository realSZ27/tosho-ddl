package dev.ddlproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.parser.Parser;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@SpringBootApplication
@RestController
@EnableScheduling
public class ProxyController {

    private static final Map<String, DownloadLinks> TITLE_TO_LINKS = new ConcurrentHashMap<>();

    private static final String SONARR_API_KEY = "8279ba5bcae34530b5f39775ece2161e";
    private static final String SONARR_BASE_URL = "http://100.90.104.92:8989";
    private static final String SONARR_FOLDER = "C:\\Users\\karst\\Downloads\\";
    private static final String JDOWNLOADER_FOLDER = "D:\\apps\\JDownloader\\folderwatch\\";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
            String queryModifier = "";
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

                // Create fake urls to send
                String fakeId = UUID.randomUUID().toString();
                String garbageUrl = "http://fakeurl.thisisnotarealdomain/" + fakeId + ".nzb";

                // Replace the magnet link in the XML
                Elements magnetUrlElements = item.select("torznab\\:attr[name=magneturl]");
                for (Element magnetUrlElement : magnetUrlElements) {
                    magnetUrlElement.attr("value", "magnet:?xt=urn:btih:" + fakeId.replace("-", ""));
                }

                item.select("enclosure").forEach(enclosure ->
                        enclosure.attr("url", garbageUrl)
                );

                String title = item.selectFirst("title") != null ? Objects.requireNonNull(item.selectFirst("title")).text() : null;

                Map<String, String> descLink = parseDescriptionLinks(desc.html());
                DownloadLinks links = new DownloadLinks(
                        descLink.getOrDefault("buzzheavier", ""),
                        descLink.getOrDefault("gofile", ""),
                        descLink.getOrDefault("krakenfiles", ""),
                        List.of(),
                        title);

                if (title != null && !title.isBlank()) {
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

    @Scheduled(fixedDelay = 30_000)
    public void checkSonarrQueue() {
        System.out.println("Polling Sonarr...");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SONARR_BASE_URL + "/api/v3/queue"))
                    .header("X-Api-Key", SONARR_API_KEY)
                    .build();

            HttpResponse<String> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode queue = OBJECT_MAPPER.readTree(res.body());
            System.out.println("Item: " + queue);

            for (JsonNode item : queue.get("records")) {
                JsonNode indexerNode = item.get("indexer");
                if (indexerNode == null || !"AnimeTosho proxy".equalsIgnoreCase(indexerNode.asText())) continue;

                String title = item.get("title").asText().trim();
                System.out.println("Title: " + title + "---------------------");
                DownloadLinks links = TITLE_TO_LINKS.get(title);
                System.out.println("Links: " + links);
                if (links == null) continue;

                sendToJDownloader(links);
                TITLE_TO_LINKS.remove(title);
                // System.out.println("✔ Sent to JDownloader: " + title);
            }

        } catch (Exception e) {
            System.err.println("Queue polling failed: " + e.getMessage());
        }
    }

    private String pickBestLink(DownloadLinks links) {
        if (!links.gofile().isEmpty()) return links.gofile();
        if (!links.buzzheavier().isEmpty()) return links.buzzheavier();
        if (!links.krakenfiles().isEmpty()) return links.krakenfiles();
        if (!links.multiup().isEmpty()) return links.multiup().getFirst();
        return null;
    }

    @GetMapping(value = "/links", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, DownloadLinks> getLinks() {
        return TITLE_TO_LINKS;
    }

    private void sendToJDownloader(DownloadLinks links) {
        try {
            Path filePath = Paths.get(JDOWNLOADER_FOLDER + links.title() + ".crawljob");
            System.out.println(filePath);
            String content = String.format("""
                    downloadFolder=%s
                    text=%s
                    enabled=TRUE
                    packageName=%s
                    autoConfirm=TRUE
                    autoStart=TRUE
                    extractAfterDownload=TRUE
                    """,
                    SONARR_FOLDER,
                    pickBestLink(links),
                    links.title());

            Files.writeString(filePath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("File written. JDownloader should be downloading: " + links.title());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}