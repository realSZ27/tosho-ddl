package dev.ddlproxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JDownloaderController {
    private final String JDOWNLOADER_API_URL;

    private final RestTemplate restTemplate = new RestTemplate();
    private static final Logger logger  = LoggerFactory.getLogger(JDownloaderController.class);
    private static final int MAX_RETRIES = 6; // 30 seconds total with 5s intervals


    // TODO: Fix all the warnings

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
        Long jobId = addLink(link);
        Long linkId = null;
        String availabilityStatus = "UNKNOWN";
        int retries = 0;

        try {
            while ("UNKNOWN".equals(availabilityStatus)) {
                List<Map<String, Object>> links = queryLinks(jobId);
                if (!links.isEmpty()) {
                    Map<String, Object> linkDetails = links.get(0);
                    availabilityStatus = (String) linkDetails.get("availability");
                    linkId = ((Number) linkDetails.get("uuid")).longValue();
                }

                if ("UNKNOWN".equals(availabilityStatus)) {
                    if (retries++ >= MAX_RETRIES) break;
                    if (linkId != null) startOnlineStatusCheck(linkId);
                    Thread.sleep(5000);
                }
            }

            clearList();
            return "ONLINE".equals(availabilityStatus);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Operation interrupted", e);
        }
    }

    private Long addLink(String link) {
        String url = JDOWNLOADER_API_URL + "/linkgrabberv2/addLinks";
        Map<String, Object> payload = new HashMap<>();
        payload.put("links", link);
        payload.put("autostart", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);

        ResponseEntity<Map> responseEntity = restTemplate.postForEntity(url, requestEntity, Map.class);
        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to add link: " + responseEntity.getStatusCode());
        }

        Map<String, Object> responseBody = responseEntity.getBody();
        if (responseBody == null) {
            throw new RuntimeException("Empty response when adding link");
        }

        Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
        if (data == null) {
            throw new RuntimeException("Missing data field in response: " + responseBody);
        }

        Number id = (Number) data.get("id");
        if (id == null) {
            throw new RuntimeException("Missing id in response data: " + data);
        }

        return id.longValue();
    }

    private List<Map<String, Object>> queryLinks(Long jobId) {
        String url = JDOWNLOADER_API_URL + "/linkgrabberv2/queryLinks";

        // The API expects a queryParams object containing the actual parameters
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("jobUUIDs", Collections.singletonList(jobId));
        queryParams.put("availability", true);
        queryParams.put("url", true);

        // Wrap query parameters in a queryParams object
        Map<String, Object> payload = new HashMap<>();
        payload.put("queryParams", queryParams);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);

        ResponseEntity<Map> responseEntity = restTemplate.postForEntity(url, requestEntity, Map.class);

        // Handle empty responses
        if (responseEntity.getBody() == null) {
            throw new RuntimeException("Empty response from queryLinks");
        }

        // Access the nested data array
        List<Map<String, Object>> links = (List<Map<String, Object>>)
                responseEntity.getBody().get("data");

        return links != null ? links : Collections.emptyList();
    }

    private void startOnlineStatusCheck(Long linkId) {
        String url = JDOWNLOADER_API_URL + "/linkgrabberv2/startOnlineStatusCheck";
        Map<String, Object> payload = new HashMap<>();
        payload.put("linkIds", Collections.singletonList(linkId));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);

        ResponseEntity<Map> responseEntity = restTemplate.postForEntity(url, requestEntity, Map.class);
        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to start status check: " + responseEntity.getStatusCode());
        }
    }

    private void clearList() {
        String url = JDOWNLOADER_API_URL + "/linkgrabberv2/clearList";

        ResponseEntity<Map> response = restTemplate.getForEntity(
                url,
                Map.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            logger.error("Failed to clear linkgrabber list: {}", response.getBody());
            throw new RuntimeException("Clear list failed: " + response.getStatusCode());
        }
        logger.info("Linkgrabber list cleared successfully");
    }

    public void download(String link, String destinationFolder) {
        try {
            // Add link with autostart and proper folder
            Map<String, Object> payload = Map.of(
                    "links", link,
                    "autostart", true,
                    "destinationFolder", destinationFolder,
                    "enabled", true
            );

            restTemplate.postForEntity(
                    JDOWNLOADER_API_URL + "/linkgrabberv2/addLinks",
                    new HttpEntity<>(payload, new HttpHeaders() {{
                        setContentType(MediaType.APPLICATION_JSON);
                    }}),
                    Void.class
            );

            logger.info("Started download: {}", link);
        } catch (Exception e) {
            logger.error("Failed to start download for {}", link, e);
        }
    }
}
