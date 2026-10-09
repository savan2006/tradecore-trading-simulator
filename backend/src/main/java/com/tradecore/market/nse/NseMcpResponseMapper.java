package com.tradecore.market.nse;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.tradecore.market.MarketCandleSnapshot;
import com.tradecore.market.MarketDataFreshness;
import com.tradecore.market.MarketDataProviderException;
import com.tradecore.market.MarketDataProviderException.Category;
import com.tradecore.market.MarketInstrument;
import com.tradecore.market.MarketQuoteSnapshot;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

final class NseMcpResponseMapper {

    private static final String EXCHANGE = "NSE";
    private static final String CM_SOURCE = "NSE_MCP_CM_MARKET";
    private static final String BHAVCOPY_SOURCE = "NSE_MCP_BHAVCOPY";
    private static final DateTimeFormatter CM_TRADE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private NseMcpResponseMapper() {
    }

    static com.tradecore.market.MarketDataConnectivity.EndpointStatus map(
            InitializeResult initialization, ListToolsResult toolListing) {
        return new com.tradecore.market.MarketDataConnectivity.EndpointStatus(
                true,
                initialization.protocolVersion(),
                toolListing.tools().size(),
                null);
    }

    static com.tradecore.market.MarketDataConnectivity.EndpointStatus failure(String category) {
        return new com.tradecore.market.MarketDataConnectivity.EndpointStatus(false, null, 0, category);
    }

    static List<MarketInstrument> mapSymbolLookup(CallToolResult result) {
        JsonNode payload = payload(result);
        JsonNode symbols = payload.path("symbols");
        if (!symbols.isArray()) {
            throw malformed("Symbol lookup response has no symbols array");
        }
        List<MarketInstrument> matches = new ArrayList<>();
        for (JsonNode symbol : symbols) {
            if (!symbol.isTextual() || symbol.asText().isBlank()) {
                throw malformed("Symbol lookup returned an invalid symbol");
            }
            matches.add(new MarketInstrument(EXCHANGE, symbol.asText(), null));
        }
        return List.copyOf(matches);
    }

    static MarketQuoteSnapshot mapQuote(CallToolResult result) {
        JsonNode payload = payload(result);
        JsonNode stock = payload.path("stock");
        if ((payload.has("stock") && stock.isNull()) || payload.has("error")) {
            throw new MarketDataProviderException(Category.UNAVAILABLE_DATA,
                    "NSE MCP has no quote available for the requested symbol");
        }
        if (!stock.isObject() || !text(stock, "symbol")) {
            throw malformed("Quote response has no stock symbol");
        }
        return mapStock(stock, payload.path("updatedAt"));
    }

    static List<MarketQuoteSnapshot> mapEquityStocks(CallToolResult result) {
        JsonNode payload = payload(result);
        JsonNode stocks = payload.path("stocks");
        if (!stocks.isArray()) {
            throw malformed("Equity-list response has no stocks array");
        }
        List<MarketQuoteSnapshot> quotes = new ArrayList<>();
        for (JsonNode stock : stocks) {
            if (!stock.isObject() || !text(stock, "symbol")) {
                throw malformed("Equity-list response contains a stock without a symbol");
            }
            quotes.add(mapStock(stock, payload.path("updatedAt")));
        }
        return List.copyOf(quotes);
    }

    private static MarketQuoteSnapshot mapStock(JsonNode stock, JsonNode updatedAt) {
        return new MarketQuoteSnapshot(
                EXCHANGE,
                stock.path("symbol").asText(),
                null,
                instant(stock.path("latestTimestamp"), CM_TRADE_TIME),
                decimal(stock, "openPrice"),
                decimal(stock, "highPrice"),
                decimal(stock, "lowPrice"),
                null,
                decimal(stock, "preClosePrice"),
                integer(stock, "volume"),
                decimal(stock, "lastTradedPrice"),
                CM_SOURCE,
                instant(updatedAt, DateTimeFormatter.ISO_DATE_TIME));
    }

    static List<MarketCandleSnapshot> mapHistory(CallToolResult result) {
        JsonNode payload = payload(result);
        JsonNode data = payload.path("data");
        if (!data.isArray()) {
            throw malformed("Historical response has no data array");
        }
        List<MarketCandleSnapshot> candles = new ArrayList<>();
        for (JsonNode row : data) {
            String symbol = requiredText(row, "symbol");
            LocalDate tradingDate = requiredDate(row, "date");
            candles.add(new MarketCandleSnapshot(
                    EXCHANGE,
                    symbol,
                    null,
                    tradingDate,
                    null,
                    requiredDecimal(row, "open"),
                    requiredDecimal(row, "high"),
                    requiredDecimal(row, "low"),
                    requiredDecimal(row, "close"),
                    requiredLong(row, "volume"),
                    decimal(row, "ltp"),
                    BHAVCOPY_SOURCE,
                    null));
        }
        return List.copyOf(candles);
    }

    static MarketDataFreshness mapFreshness(CallToolResult result) {
        JsonNode payload = payload(result);
        if (!payload.path("available").isBoolean()) {
            throw malformed("Market data status response has no availability flag");
        }
        return new MarketDataFreshness(
                CM_SOURCE,
                payload.path("available").asBoolean(),
                instant(payload.path("lastCrawled"), DateTimeFormatter.ISO_DATE_TIME),
                payload.path("crawlIntervalMinutes").canConvertToLong()
                        ? Duration.ofMinutes(payload.path("crawlIntervalMinutes").asLong()) : null);
    }

    private static JsonNode payload(CallToolResult result) {
        if (result == null) {
            throw malformed("NSE MCP returned no tool result");
        }
        if (result.isError()) {
            throw new MarketDataProviderException(Category.PROVIDER_ERROR, "NSE MCP reported a tool error");
        }
        if (result.content() == null || result.content().isEmpty()
                || !(result.content().get(0) instanceof io.modelcontextprotocol.spec.McpSchema.TextContent text)) {
            throw malformed("NSE MCP tool response did not contain its documented JSON text payload");
        }
        try {
            return JSON.readTree(text.text());
        } catch (Exception exception) {
            throw new MarketDataProviderException(Category.MALFORMED_RESPONSE,
                    "NSE MCP tool response was not valid JSON", exception);
        }
    }

    private static boolean text(JsonNode node, String field) {
        return node.path(field).isTextual() && !node.path(field).asText().isBlank();
    }

    private static String requiredText(JsonNode node, String field) {
        if (!text(node, field)) {
            throw malformed("Historical row is missing " + field);
        }
        return node.path(field).asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual() && !value.asText().isBlank()) {
            try {
                return new BigDecimal(value.asText());
            } catch (NumberFormatException ignored) {
                throw malformed("NSE MCP returned a non-numeric " + field);
            }
        }
        return null;
    }

    private static BigDecimal requiredDecimal(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        if (value == null) {
            throw malformed("Historical row is missing numeric " + field);
        }
        return value;
    }

    private static Long integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.canConvertToLong() && value.isIntegralNumber() ? value.asLong() : null;
    }

    private static long requiredLong(JsonNode node, String field) {
        Long value = integer(node, field);
        if (value == null) {
            throw malformed("Historical row is missing integer " + field);
        }
        return value;
    }

    private static LocalDate requiredDate(JsonNode node, String field) {
        try {
            return LocalDate.parse(requiredText(node, field));
        } catch (RuntimeException exception) {
            throw new MarketDataProviderException(Category.MALFORMED_RESPONSE,
                    "Historical row has an invalid " + field, exception);
        }
    }

    private static Instant instant(JsonNode node, DateTimeFormatter formatter) {
        if (!node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        try {
            String value = node.asText();
            if (formatter == CM_TRADE_TIME) {
                return LocalDateTime.parse(value, formatter).atZone(INDIA).toInstant();
            }
            return java.time.OffsetDateTime.parse(value, formatter).toInstant();
        } catch (RuntimeException exception) {
            throw new MarketDataProviderException(Category.MALFORMED_RESPONSE,
                    "NSE MCP returned an invalid timestamp", exception);
        }
    }

    private static MarketDataProviderException malformed(String message) {
        return new MarketDataProviderException(Category.MALFORMED_RESPONSE, message);
    }
}
