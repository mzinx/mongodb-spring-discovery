package com.mzinx.mongodb.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@ConfigurationProperties("discovery")
@Component
public class DiscoveryProperties {
    @Data
    public class Heartbeat {
        private long interval = 5000; // 5 seconds
        private long max = 10; // 10 times
        public long getMaxTimeout() {
            return interval*max;
        }
    }
    private boolean enabled = true;
    private String hostname = System.getenv().getOrDefault("HOSTNAME", "localhost");
    private Heartbeat heartbeat = new Heartbeat();
    private String collection = "_instances";    
}
