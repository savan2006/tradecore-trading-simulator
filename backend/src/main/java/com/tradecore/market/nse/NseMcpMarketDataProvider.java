package com.tradecore.market.nse;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.tradecore.market.MarketDataConnectivity;
import com.tradecore.market.MarketDataConnectivity.EndpointStatus;
import com.tradecore.market.MarketDataProvider;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NseMcpMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(NseMcpMarketDataProvider.class);

    private final NseMcpProperties properties;

    public NseMcpMarketDataProvider(NseMcpProperties properties) {
        this.properties = properties;
    }

    @Override
    public MarketDataConnectivity checkConnectivity() {
        var endpoints = new LinkedHashMap<String, EndpointStatus>();
        endpoints.put("market-live", checkEndpoint(properties.marketLiveEndpoint()));
        endpoints.put("bhavcopy", checkEndpoint(properties.bhavcopyEndpoint()));
        boolean connected = endpoints.values().stream().allMatch(EndpointStatus::connected);
        return new MarketDataConnectivity(connected, Instant.now(), endpoints);
    }

    private EndpointStatus checkEndpoint(URI endpoint) {
        McpSyncClient client = null;
        try {
            URI origin = URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority());
            var transport = HttpClientStreamableHttpTransport.builder(origin.toString())
                    .endpoint(endpoint.getRawPath())
                    .connectTimeout(properties.connectTimeout())
                    .build();
            client = McpClient.sync(transport)
                    .requestTimeout(properties.requestTimeout())
                    .build();

            InitializeResult initialization = client.initialize();
            ListToolsResult toolListing = client.listTools();
            return NseMcpResponseMapper.map(initialization, toolListing);
        } catch (RuntimeException exception) {
            String category = failureCategory(exception);
            log.warn("NSE MCP connectivity check failed for {} endpoint ({})",
                    endpoint.getPath(), category);
            return NseMcpResponseMapper.failure(category);
        } finally {
            if (client != null) {
                try {
                    client.closeGracefully();
                } catch (RuntimeException exception) {
                    log.debug("NSE MCP client close failed ({})", exception.getClass().getSimpleName());
                }
            }
        }
    }

    private static String failureCategory(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause.getClass().getSimpleName().toLowerCase().contains("timeout")
                    || (cause.getMessage() != null && cause.getMessage().toLowerCase().contains("timed out"))) {
                return "TIMEOUT";
            }
        }
        if (failure instanceof McpTransportException) {
            return "TRANSPORT_FAILURE";
        }
        return "CONNECTION_OR_PROTOCOL_FAILURE";
    }
}
