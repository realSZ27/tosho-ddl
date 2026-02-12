package dev.ddlproxy.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

public class JDownloaderController {
    private final String JDOWNLOADER_API_URL;

    private final RestTemplate restTemplate = new RestTemplate();
    private static final Logger logger = LoggerFactory.getLogger(JDownloaderController.class);

    public JDownloaderController(String baseURL) {
        this.JDOWNLOADER_API_URL = baseURL;

        restTemplate.getInterceptors().add((request, body, execution) -> {
            logger.trace("Request URI: {}", request.getURI());
            logger.trace("Request Method: {}", request.getMethod());
            logger.trace("Request Headers: {}", request.getHeaders());
            logger.trace("Request Body: {}", new String(body, StandardCharsets.UTF_8));
            return execution.execute(request, body);
        });
    }

    public boolean isLinkOnline(ArrayList<String> link) {
        addLink(link.toString());

        String result;
        int maxRetries = 100;
        int attempt = 0;

        do {
            result = queryLinks();
            attempt++;

            switch (result) {
                case "ONLINE":
                    clearList();
                    return true;
                case "OFFLINE":
                    clearList();
                    return false;
                case "MIXED":
                    logger.trace("Some links are not fully online, continuing to wait...");
                    break;
                case "UNKNOWN":
                    logger.trace("Query returned UNKNOWN state, retrying...");
                    break;
                case "ERROR":
                    logger.error("Error encountered while querying links.");
                    break;
                default:
                    logger.warn("Unexpected link state: {}", result);
                    break;
            }

            logger.trace("Query result is UNKNOWN, retrying...");

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } while (attempt < maxRetries);
        clearList();
        logger.warn("maxRetries reached on link \"{}\"", link);
        return false;
    }

    private void addLink(String link) {
        logger.debug("Adding link \"{}\"", link);
        String url = JDOWNLOADER_API_URL + "/linkgrabberv2/addLinks";

        Map<String, Object> payload = Map.of(
                "links", link,
                "autostart", false,
                "url", true,
                "enabled", true
        );

        restTemplate.postForEntity(
                url,
                new HttpEntity<>(payload, new HttpHeaders() {{
                    setContentType(MediaType.APPLICATION_JSON);
                }}),
                Void.class
        );
    }

    private String queryLinks() {
        String url = JDOWNLOADER_API_URL + "/linkgrabberv2/queryLinks";

        Map<String, Object> payload = Map.of(
                "availability", true,
                "enabled", true
        );

        try {
            ResponseEntity<String> responseEntity = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(payload, new HttpHeaders() {{
                        setContentType(MediaType.APPLICATION_JSON);
                    }}),
                    String.class
            );

            String jsonResponse = responseEntity.getBody();
            logger.trace("payload: {}", payload);
            logger.trace("response: {}", jsonResponse);

            JsonNode rootNode = new ObjectMapper().readTree(jsonResponse);
            JsonNode dataArray = rootNode.path("data");

            if (!dataArray.isArray() || dataArray.isEmpty()) {
                logger.debug("No data returned from linkgrabber query");
                return "UNKNOWN";
            }

            boolean anyOffline = false;
            boolean allOnline = true;

            for (JsonNode data : dataArray) {
                String availability = data.path("availability").asString();
                if (!availability.equalsIgnoreCase("ONLINE")) {
                    allOnline = false;
                    if (availability.equalsIgnoreCase("OFFLINE")) {
                        anyOffline = true;
                    }
                }
            }

            if (allOnline) return "ONLINE";
            if (anyOffline) return "OFFLINE";
            return "MIXED";

        } catch (Exception e) {
            logger.error("Error during queryLinks", e);
            return "ERROR";
        }
    }

    private void clearList() {
        String url = JDOWNLOADER_API_URL + "/linkgrabberv2/clearList";

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            logger.error("Failed to clear linkgrabber list: {}", response.getBody());
            throw new RuntimeException("Clear list failed: " + response.getStatusCode());
        }
        logger.debug("Linkgrabber list cleared successfully");
    }

    public void download(ArrayList<String> link, String destinationFolder) {
        clearList();

        Map<String, Object> payload = Map.of(
                "links", String.join("\n", link),
                "autostart", true,
                "destinationFolder", destinationFolder,
                "enabled", true,
                "autoExtract", true,
                "overwritePackagizerRules", true
        );

        try {
            ResponseEntity<String> jdownloaderResponse = restTemplate.postForEntity(
                    JDOWNLOADER_API_URL + "/linkgrabberv2/addLinks",
                    new HttpEntity<>(payload, new HttpHeaders() {{
                        setContentType(MediaType.APPLICATION_JSON);
                    }}),
                    String.class
            );

            logger.trace("JDownloader response: {}", jdownloaderResponse.getBody());
            logger.info("Started download: {}", link);
        } catch (Exception e) {
            logger.error("Failed to start download: {}", link, e);
        }
    }
}
