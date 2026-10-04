package com.tradecore.market.nse;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tradecore.market-data.nse-mcp")
public record NseMcpProperties(
        URI marketLiveEndpoint,
        URI bhavcopyEndpoint,
        Duration connectTimeout,
        Duration requestTimeout) {

    public NseMcpProperties {
        validateEndpoint(marketLiveEndpoint, "marketLiveEndpoint");
        validateEndpoint(bhavcopyEndpoint, "bhavcopyEndpoint");
        validateTimeout(connectTimeout, "connectTimeout");
        validateTimeout(requestTimeout, "requestTimeout");
    }

    private static void validateEndpoint(URI endpoint, String propertyName) {
        boolean https = endpoint != null && "https".equalsIgnoreCase(endpoint.getScheme());
        boolean localHttp = endpoint != null
                && "http".equalsIgnoreCase(endpoint.getScheme())
                && ("localhost".equalsIgnoreCase(endpoint.getHost())
                    || "127.0.0.1".equals(endpoint.getHost())
                    || "::1".equals(endpoint.getHost()));
        if (endpoint == null
                || (!https && !localHttp)
                || endpoint.getHost() == null
                || endpoint.getRawPath() == null
                || endpoint.getRawPath().isBlank()
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException(propertyName + " must be an HTTPS MCP endpoint URI (loopback HTTP is allowed for tests)");
        }
    }

    private static void validateTimeout(Duration timeout, String propertyName) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be a positive duration");
        }
    }
}
