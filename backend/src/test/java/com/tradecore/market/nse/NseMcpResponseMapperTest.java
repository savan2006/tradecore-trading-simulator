package com.tradecore.market.nse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.tradecore.market.MarketDataProviderException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NseMcpResponseMapperTest {

    @Test
    void mapsLiveSymbolLookupAndQuoteWithSeparateTradeAndUpdateTimes() throws Exception {
        var symbols = NseMcpResponseMapper.mapSymbolLookup(result(fixture("bhavcopy-symbols.json")));
        var quote = NseMcpResponseMapper.mapQuote(result(fixture("cm-quote.json")));

        assertThat(symbols).containsExactly(
                new com.tradecore.market.MarketInstrument("NSE", "TCS", null),
                new com.tradecore.market.MarketInstrument("NSE", "WSTCSTPAPR", null));
        assertThat(quote.exchange()).isEqualTo("NSE");
        assertThat(quote.symbol()).isEqualTo("TCS");
        assertThat(quote.lastPrice()).isEqualByComparingTo("2075.0");
        assertThat(quote.open()).isEqualByComparingTo("2052.6");
        assertThat(quote.high()).isEqualByComparingTo("2093.9");
        assertThat(quote.low()).isEqualByComparingTo("2045.1");
        assertThat(quote.close()).isNull();
        assertThat(quote.previousClose()).isEqualByComparingTo("2050.6");
        assertThat(quote.volume()).isEqualTo(3428501L);
        assertThat(quote.tradingTimestamp()).isEqualTo(Instant.parse("2026-10-01T10:30:28Z"));
        assertThat(quote.dataUpdatedAt()).isEqualTo(Instant.parse("2026-10-04T03:32:27.734817172Z"));
        assertThat(quote.dataSource()).isEqualTo("NSE_MCP_CM_MARKET");
    }

    @Test
    void mapsObservedCmEquityListResponseIntoNormalizedQuotes() throws Exception {
        var quotes = NseMcpResponseMapper.mapEquityStocks(result(fixture("cm-equity-stocks.json")));

        assertThat(quotes).hasSize(1);
        assertThat(quotes.get(0).symbol()).isEqualTo("TCS");
        assertThat(quotes.get(0).lastPrice()).isEqualByComparingTo("2075.0");
        assertThat(quotes.get(0).dataUpdatedAt()).isEqualTo(Instant.parse("2026-10-04T03:32:27.734817172Z"));
    }

    @Test
    void mapsDailyHistoricalOhlcvWithoutInventingIntradayTimestamps() throws Exception {
        var candles = NseMcpResponseMapper.mapHistory(result(fixture("bhavcopy-history.json")));

        assertThat(candles).hasSize(1);
        var candle = candles.get(0);
        assertThat(candle.tradingDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(candle.tradingTimestamp()).isNull();
        assertThat(candle.open()).isEqualByComparingTo("2052.6");
        assertThat(candle.high()).isEqualByComparingTo("2093.9");
        assertThat(candle.low()).isEqualByComparingTo("2045.1");
        assertThat(candle.close()).isEqualByComparingTo("2075.0");
        assertThat(candle.lastPrice()).isEqualByComparingTo("2075.0");
        assertThat(candle.volume()).isEqualTo(3428501L);
        assertThat(candle.dataUpdatedAt()).isNull();
    }

    @Test
    void preservesOptionalMissingQuoteFieldsAndProviderFreshness() throws Exception {
        var quote = NseMcpResponseMapper.mapQuote(result("{\"stock\":{\"symbol\":\"TCS\"}}"));
        var freshness = NseMcpResponseMapper.mapFreshness(result(fixture("cm-freshness.json")));

        assertThat(quote.lastPrice()).isNull();
        assertThat(quote.tradingTimestamp()).isNull();
        assertThat(quote.dataUpdatedAt()).isNull();
        assertThat(freshness.available()).isTrue();
        assertThat(freshness.dataUpdatedAt()).isEqualTo(Instant.parse("2026-10-04T03:32:27.734817172Z"));
        assertThat(freshness.expectedRefreshInterval()).hasSeconds(60);
    }

    @Test
    void rejectsMalformedAndUnexpectedProviderPayloadsAndProviderReportedErrors() {
        assertThatThrownBy(() -> NseMcpResponseMapper.mapQuote(result("not-json")))
                .isInstanceOfSatisfying(MarketDataProviderException.class,
                        error -> assertThat(error.category()).isEqualTo(MarketDataProviderException.Category.MALFORMED_RESPONSE));
        assertThatThrownBy(() -> NseMcpResponseMapper.mapHistory(result("{\"data\":[{\"symbol\":\"TCS\"}]}")))
                .isInstanceOfSatisfying(MarketDataProviderException.class,
                        error -> assertThat(error.category()).isEqualTo(MarketDataProviderException.Category.MALFORMED_RESPONSE));
        assertThatThrownBy(() -> NseMcpResponseMapper.mapQuote(result("{\"stock\":null}")))
                .isInstanceOfSatisfying(MarketDataProviderException.class,
                        error -> assertThat(error.category()).isEqualTo(MarketDataProviderException.Category.UNAVAILABLE_DATA));
        assertThatThrownBy(() -> NseMcpResponseMapper.mapQuote(new CallToolResult(
                List.of(new TextContent("tool failed")), true, null, null)))
                .isInstanceOfSatisfying(MarketDataProviderException.class,
                        error -> assertThat(error.category()).isEqualTo(MarketDataProviderException.Category.PROVIDER_ERROR));
    }

    private static CallToolResult result(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false, null, null);
    }

    private static String fixture(String name) throws Exception {
        try (InputStream stream = NseMcpResponseMapperTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
