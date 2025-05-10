package dev.ddlproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class JDownloaderController {
    private final String JDOWNLOADER_API_URL;

    private final RestTemplate restTemplate = new RestTemplate();
    private static final Logger logger  = LoggerFactory.getLogger(JDownloaderController.class);

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

    public boolean isLinkOnline(String link) {
        addLink(link);

        String result;
        int maxRetries = 10;
        int attempt = 0;

        do {
            result = queryLinks();
            attempt++;

            if ("ONLINE".equals(result)) {
                clearList();
                return true;
            } else if("OFFLINE".equals(result)) {
                clearList();
                return false;
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

        ResponseEntity<String> responseEntity = restTemplate.postForEntity(
                url,
                new HttpEntity<>(payload, new HttpHeaders() {{
                    setContentType(MediaType.APPLICATION_JSON);
                }}),
                String.class
        );
        String jsonResponse = responseEntity.getBody();

        logger.trace("payload: {}", payload);
        logger.trace("response: {}",jsonResponse);

        String linkState = "";
        try {
            JsonNode rootNode = new ObjectMapper().readTree(jsonResponse);
            linkState = rootNode.path("data").get(0).path("availability").asText();
        } catch (Exception e) {
            logger.error("Error parsing the queryLinks response", e);
        }

        if(linkState.isEmpty()) {
            logger.error("Couldn't get linkState");
        }

        return linkState;
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

    public void download(String link, String destinationFolder) {
        clearList();
        try {
            Map<String, Object> payload = Map.of(
                    "links", link,
                    "autostart", true,
                    "destinationFolder", destinationFolder,
                    "enabled", true
            );

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
            logger.error("Failed to start download for {}", link, e);
        }
    }
}
