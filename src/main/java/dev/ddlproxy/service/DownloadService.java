package dev.ddlproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import dev.ddlproxy.model.DownloadLinks;

import org.jsoup.Connection;
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

            Connection.Response response = Jsoup.connect(queryString.toString())
                    .ignoreContentType(true)
                    .parser(Parser.xmlParser())
                    .timeout(10000)
                    .execute();

            if (response.statusCode() != 200) {
                throw new IOException("Upstream returned " + response.statusCode() +
                                      " body: " + response.body());
            }

            Document doc = response.parse();

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
            Document errorDoc = Document.createShell("");
            errorDoc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);

            Element error = errorDoc.body().appendElement("error");
            error.appendElement("description").text(e.getMessage());

            return errorDoc.body().html();
        } catch (Exception e) {
            return "<error><description>Unexpected server error</description></error>";
        }
    }

    public DownloadLinks getLinks(String query) {
        String jsonEndpoint = "https://feed.animetosho.org/json";
        String finalUrl = jsonEndpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        JsonNode searchResults = JsonNodeFactory.instance.objectNode();;

        try {
            searchResults = GET(finalUrl);
        } catch (IOException | InterruptedException e) {
            logger.error("error getting searchResults: {}", e);
        }

        if (!searchResults.isArray() || searchResults.isEmpty()) {
            logger.warn("No results found for query: {}", query);
            return new DownloadLinks(new ArrayList<>(), query);
        }

        int torrentId = searchResults.get(0).get("id").asInt();
        logger.trace("Torrent ID is: {}", torrentId);

        JsonNode torrentData = JsonNodeFactory.instance.objectNode();
        try {
            torrentData = GET(jsonEndpoint + "?show=torrent&id=" + torrentId);
        } catch (IOException | InterruptedException e) {
            logger.error("error getting data for torrent id {}: {}", torrentId, e);
        }

        JsonNode filesArray = torrentData.path("files");

        ArrayList<ArrayList<String>> links = new ArrayList<>();

        if (!filesArray.isArray()) {
            logger.error("No 'files' array found for torrent ID {}", torrentId);
            return new DownloadLinks(new ArrayList<>(), query);
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
    private JsonNode GET(String URL) throws IOException, InterruptedException {
        logger.trace("Url: {}", URL);
        HttpResponse<String> response;
        HttpClient client = HttpClient.newBuilder().build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .GET()
                .build();

        response = client.send(request, HttpResponse.BodyHandlers.ofString());

        //logger.trace("Raw response: {}", response.body());

        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(response.body());
    }
}
