package dev.ddlproxy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ddlproxy.model.DownloadLinks;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DownloadService {
    private static final Logger logger = LoggerFactory.getLogger(DownloadService.class);
    private final String thisBaseUrl;

    public DownloadService(String thisBaseUrl) {
        this.thisBaseUrl = thisBaseUrl;
    }

    public String handleQuery(MultiValueMap<String, String> allParams) {
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
                } else if (!key.equalsIgnoreCase("t")) {
                    for (String value : values) {
                        if (!queryString.toString().endsWith("?")) {
                            queryString.append("&");
                        }
                        queryString.append(URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20"))
                                .append("=")
                                .append(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
                    }
                }
            }

            if (!qValues.isEmpty()) {
                String suffix = (seasonStr != null || episodeStr != null)
                        ? " " + (seasonStr != null ? seasonStr : "") + (episodeStr != null ? episodeStr : "")
                        : "";
                for (String value : qValues) {
                    if (!queryString.toString().endsWith("?")) {
                        queryString.append("&");
                    }
                    queryString.append("q=").append(URLEncoder.encode(value + suffix, StandardCharsets.UTF_8).replace("+", "%20"));
                }
            }

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
                String fakeUrl = thisBaseUrl + "/download/" + URLEncoder.encode(title, StandardCharsets.UTF_8) + ".torrent";

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

    public DownloadLinks getLinks(String query) {
        String jsonEndpoint = "https://feed.animetosho.org/json";
        String finalUrl = jsonEndpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        JsonNode searchResults = GET(finalUrl);
        if (!searchResults.isArray() || searchResults.isEmpty()) {
            logger.warn("No results found for query: {}", query);
            return new DownloadLinks(new ArrayList<>(), query);
        }

        int torrentId = searchResults.get(0).get("id").asInt();
        logger.trace("Torrent ID is: {}", torrentId);

        JsonNode torrentData = GET(jsonEndpoint + "?show=torrent&id=" + torrentId);
        JsonNode filesArray = torrentData.path("files");

        ArrayList<ArrayList<String>> links = new ArrayList<>();

        if (!filesArray.isArray()) {
            logger.error("No 'files' array found for torrent ID {}", torrentId);
            throw new RuntimeException("Invalid JSON");
        }

        for (JsonNode fileNode : filesArray) {
            JsonNode linksNode = fileNode.path("links");
            if (linksNode != null && linksNode.isObject()) {
                for (Map.Entry<String, JsonNode> entry : linksNode.properties()) {
                    JsonNode valueNode = entry.getValue();
                    ArrayList<String> innerList = new ArrayList<>();
                    if (valueNode.isArray()) {
                        for (JsonNode part : valueNode) {
                            innerList.add(part.asText());
                        }
                    } else {
                        innerList.add(valueNode.asText());
                    }
                    if (!innerList.isEmpty()) {
                        links.add(innerList);
                    }
                }
            }
        }

        logger.trace("Final links list: {}", links);

        return new DownloadLinks(links, query);
    }

    // I really tried to get RestTemplate to work here but for some reason it just didn't -_-
    private JsonNode GET(String URL) {
        logger.trace("Url: {}", URL);
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().build()) {

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .GET()
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        //logger.trace("Raw response: {}", response.body());

        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}