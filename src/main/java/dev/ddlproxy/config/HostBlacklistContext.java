package dev.ddlproxy.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HostBlacklistContext {
    private static HostBlacklistProperties instance;

    @Autowired
    public void init(HostBlacklistProperties props) {
        HostBlacklistContext.instance = props;
    }

    public static HostBlacklistProperties get() {
        return instance;
    }
}
