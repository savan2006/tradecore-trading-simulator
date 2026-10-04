package com.tradecore.market.nse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tradecore.market.MarketDataProviderException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NseMcpMarketDataProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void initializesBothStreamableHttpEndpointsAndMapsTheirToolListings() throws Exception {
        try (var server = new LocalMcpServer(false)) {
            var connectivity = provider(server, Duration.ofSeconds(1)).checkConnectivity();

            assertThat(connectivity.connected()).isTrue();
            assertThat(connectivity.endpoints()).containsKeys("market-live", "bhavcopy");
            assertThat(connectivity.endpoints().get("market-live").protocolVersion()).isEqualTo("2025-11-25");
            assertThat(connectivity.endpoints().get("market-live").availableOperations()).isEqualTo(2);
            assertThat(connectivity.endpoints().get("bhavcopy").availableOperations()).isEqualTo(1);
            assertThat(server.initializeRequests()).isEqualTo(2);
            assertThat(server.toolListingRequests()).isEqualTo(2);
        }
    }

    @Test
    void reportsRequestTimeoutsAsUnavailableInsteadOfReturningPlaceholderData() throws Exception {
        try (var server = new LocalMcpServer(true)) {
            var connectivity = provider(server, Duration.ofMillis(150)).checkConnectivity();

            assertThat(connectivity.connected()).isFalse();
            assertThat(connectivity.endpoints().values())
                    .allSatisfy(endpoint -> assertThat(endpoint.failureCategory()).isEqualTo("TIMEOUT"));
        }
    }

    @Test
    void mapsMarketDataRequestTimeoutToExplicitProviderFailure() throws Exception {
        try (var server = new LocalMcpServer(true)) {
            var provider = provider(server, Duration.ofMillis(150));

            assertThatThrownBy(() -> provider.getQuote("TCS"))
                    .isInstanceOfSatisfying(MarketDataProviderException.class,
                            error -> assertThat(error.category()).isEqualTo(MarketDataProviderException.Category.TIMEOUT));
        }
    }

    @Test
    void routesQuoteRequestThroughProviderBoundaryAndReturnsNormalizedValues() throws Exception {
        try (var server = new LocalMcpServer(false)) {
            var quote = provider(server, Duration.ofSeconds(1)).getQuote("TCS");

            assertThat(server.lastToolName()).isEqualTo("cm_get_stock_quote");
            assertThat(server.lastToolArguments().path("symbol").asText()).isEqualTo("TCS");
            assertThat(quote.symbol()).isEqualTo("TCS");
            assertThat(quote.lastPrice()).isEqualByComparingTo("2075.0");
            assertThat(quote.dataSource()).isEqualTo("NSE_MCP_CM_MARKET");
        }
    }

    @Test
    void doesNotInferMarketSessionStateFromDataAvailability() throws Exception {
        try (var server = new LocalMcpServer(false)) {
            assertThat(provider(server, Duration.ofSeconds(1)).getMarketSessionStatus()).isEmpty();
        }
    }

    private NseMcpMarketDataProvider provider(LocalMcpServer server, Duration requestTimeout) {
        String base = "http://127.0.0.1:" + server.port();
        return new NseMcpMarketDataProvider(new NseMcpProperties(
                URI.create(base + "/cmmkt/mcp"),
                URI.create(base + "/bhavcopy/cm/mcp"),
                Duration.ofSeconds(1),
                requestTimeout));
    }

    private final class LocalMcpServer implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final boolean delayInitialize;
        private volatile int initializeRequests;
        private volatile int toolListingRequests;
        private volatile String lastToolName;
        private volatile JsonNode lastToolArguments;

        private LocalMcpServer(boolean delayInitialize) throws IOException {
            this.delayInitialize = delayInitialize;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private int initializeRequests() {
            return initializeRequests;
        }

        private int toolListingRequests() {
            return toolListingRequests;
        }

        private String lastToolName() {
            return lastToolName;
        }

        private JsonNode lastToolArguments() { return lastToolArguments; }

        private void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }

            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }

            if ("initialize".equals(method)) {
                initializeRequests++;
                if (delayInitialize) {
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                sendResult(exchange, request.path("id"), Map.of(
                        "protocolVersion", "2025-11-25",
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "nse-mcp-test", "version", "1.0.0")), true);
                return;
            }

            if ("tools/list".equals(method)) {
                toolListingRequests++;
                int operationCount = exchange.getRequestURI().getPath().contains("bhavcopy") ? 1 : 2;
                var tools = java.util.stream.IntStream.range(0, operationCount)
                        .mapToObj(index -> Map.of(
                                "name", "market_operation_" + index,
                                "description", "Test operation",
                                "inputSchema", Map.of("type", "object", "properties", Map.of())))
                        .toList();
                sendResult(exchange, request.path("id"), Map.of("tools", tools), false);
                return;
            }

            if ("tools/call".equals(method)) {
                JsonNode params = request.path("params");
                lastToolName = params.path("name").asText();
                lastToolArguments = params.path("arguments");
                sendResult(exchange, request.path("id"), Map.of(
                        "content", java.util.List.of(Map.of("type", "text", "text",
                                "{\"updatedAt\":\"2026-10-04T03:32:27.734817172Z\",\"stock\":{\"symbol\":\"TCS\",\"openPrice\":2052.6,\"highPrice\":2093.9,\"lowPrice\":2045.1,\"lastTradedPrice\":2075.0,\"volume\":3428501,\"latestTimestamp\":\"2026-10-01 16:00:28\"}}")),
                        "isError", false), false);
                return;
            }

            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private void sendResult(HttpExchange exchange, JsonNode id, Object result, boolean initialize)
                throws IOException {
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "jsonrpc", "2.0",
                    "id", objectMapper.treeToValue(id, Object.class),
                    "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (initialize) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", "test-session");
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
