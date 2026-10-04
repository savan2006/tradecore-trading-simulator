package com.tradecore.market.nse;

import java.net.URI;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

import com.tradecore.market.MarketCandleSnapshot;
import com.tradecore.market.MarketDataConnectivity;
import com.tradecore.market.MarketDataConnectivity.EndpointStatus;
import com.tradecore.market.MarketDataFreshness;
import com.tradecore.market.MarketDataProviderException;
import com.tradecore.market.MarketDataProvider;
import com.tradecore.market.MarketInstrument;
import com.tradecore.market.MarketQuoteSnapshot;
import com.tradecore.market.MarketSessionStatus;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
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

    @Override
    public List<MarketInstrument> searchInstruments(String query) {
        return NseMcpResponseMapper.mapSymbolLookup(call(
                properties.bhavcopyEndpoint(), "nse_lookup_symbol", Map.of("query", query)));
    }

    @Override
    public MarketQuoteSnapshot getQuote(String symbol) {
        return NseMcpResponseMapper.mapQuote(call(
                properties.marketLiveEndpoint(), "cm_get_stock_quote", Map.of("symbol", symbol)));
    }

    @Override
    public List<MarketQuoteSnapshot> getQuotes(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty() || symbols.stream().anyMatch(symbol -> symbol == null || symbol.isBlank())) {
            throw new IllegalArgumentException("At least one non-empty market symbol is required");
        }
        Set<String> requested = Set.copyOf(symbols);
        if (requested.size() != symbols.size()) {
            throw new IllegalArgumentException("Market symbol requests must be unique");
        }
        var prefixGroups = new TreeMap<String, List<String>>();
        for (String symbol : requested) {
            prefixGroups.computeIfAbsent(symbol.substring(0, 1), ignored -> new ArrayList<>()).add(symbol);
        }

        McpSyncClient client = null;
        try {
            client = createClient(properties.marketLiveEndpoint());
            client.initialize();
            var quotes = new LinkedHashMap<String, MarketQuoteSnapshot>();
            for (var group : prefixGroups.entrySet()) {
                collectEquityQuotes(client, group.getKey(), group.getValue(), quotes);
            }
            return List.copyOf(quotes.values());
        } catch (RuntimeException exception) {
            throw providerFailure(exception);
        } finally {
            close(client);
        }
    }

    private void collectEquityQuotes(McpSyncClient client, String prefix, List<String> requested,
            Map<String, MarketQuoteSnapshot> quotes) {
        List<MarketQuoteSnapshot> response = NseMcpResponseMapper.mapEquityStocks(client.callTool(
                new CallToolRequest("cm_get_equity_stocks", Map.of("limit", 500, "symbolFilter", prefix))));
        int limit = 500;
        Set<String> requestedSet = Set.copyOf(requested);
        for (MarketQuoteSnapshot quote : response) {
            if (requestedSet.contains(quote.symbol())) {
                quotes.put(quote.symbol(), quote);
            }
        }
        if (response.size() < limit) {
            return;
        }

        var moreSpecific = new TreeMap<String, List<String>>();
        for (String symbol : requested) {
            if (symbol.length() > prefix.length()) {
                String nextPrefix = symbol.substring(0, prefix.length() + 1);
                moreSpecific.computeIfAbsent(nextPrefix, ignored -> new ArrayList<>()).add(symbol);
            }
        }
        for (var group : moreSpecific.entrySet()) {
            collectEquityQuotes(client, group.getKey(), group.getValue(), quotes);
        }
    }

    @Override
    public List<MarketCandleSnapshot> getHistoricalCandles(String symbol, int months, LocalDate endDate) {
        if (months < 1 || months > 3) {
            throw new IllegalArgumentException("NSE Bhavcopy history supports chunks of 1 to 3 months");
        }
        return NseMcpResponseMapper.mapHistory(call(properties.bhavcopyEndpoint(), "get_stock_history", Map.of(
                "symbol", symbol,
                "months", months,
                "endDate", endDate == null ? "today" : endDate.toString())));
    }

    @Override
    public Optional<MarketDataFreshness> getDataFreshness() {
        return Optional.of(NseMcpResponseMapper.mapFreshness(call(
                properties.marketLiveEndpoint(), "cm_get_allstocks_status", Map.of())));
    }

    @Override
    public Optional<MarketSessionStatus> getMarketSessionStatus() {
        // Neither live tool catalog exposes market/session state; cached-data presence is not session state.
        return Optional.empty();
    }

    private CallToolResult call(URI endpoint, String toolName, Map<String, Object> arguments) {
        McpSyncClient client = null;
        try {
            client = createClient(endpoint);
            client.initialize();
            return client.callTool(new CallToolRequest(toolName, arguments));
        } catch (RuntimeException exception) {
            throw providerFailure(exception);
        } finally {
            close(client);
        }
    }

    private McpSyncClient createClient(URI endpoint) {
        URI origin = URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority());
        var transport = HttpClientStreamableHttpTransport.builder(origin.toString())
                .endpoint(endpoint.getRawPath())
                .connectTimeout(properties.connectTimeout())
                .build();
        return McpClient.sync(transport).requestTimeout(properties.requestTimeout()).build();
    }

    private static MarketDataProviderException providerFailure(RuntimeException failure) {
        if (failure instanceof MarketDataProviderException providerFailure) {
            return providerFailure;
        }
        String category = failureCategory(failure);
        var reason = "TIMEOUT".equals(category)
                ? MarketDataProviderException.Category.TIMEOUT
                : MarketDataProviderException.Category.PROVIDER_ERROR;
        return new MarketDataProviderException(reason, "NSE MCP request failed (" + category + ")", failure);
    }

    private static void close(McpSyncClient client) {
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (RuntimeException exception) {
                log.debug("NSE MCP client close failed ({})", exception.getClass().getSimpleName());
            }
        }
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
