package dev.ddlproxy.config;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HostPriorityContext {
    private static HostPriorityProperties instance;

    @Autowired
    public void init(HostPriorityProperties props) {
        HostPriorityContext.instance = props;
    }

    public static HostPriorityProperties get() {
        return instance;
    }
}
