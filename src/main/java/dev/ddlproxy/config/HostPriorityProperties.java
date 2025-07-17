package dev.ddlproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "host.priority")
public class HostPriorityProperties {
    private Map<String, Integer> mapping = new HashMap<>();

    public Map<String, Integer> getMapping() {
        return mapping;
    }

    public void setMapping(Map<String, Integer> mapping) {
        this.mapping = mapping;
    }
}
