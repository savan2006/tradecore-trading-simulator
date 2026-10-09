package com.tradecore.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-market-ws-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "tradecore.security.user=market-ws-test",
        "tradecore.security.password=market-ws-password",
        "tradecore.market-data.scheduling.quote-enabled=false",
        "tradecore.market-data.scheduling.candle-enabled=false",
        "tradecore.execution.scheduling.enabled=false"
})
class MarketQuoteWebSocketTest {

    @LocalServerPort
    private int port;

    @Autowired
    private InstrumentRepository instrumentRepository;

    @Autowired
    private MarketQuoteRepository quoteRepository;

    @Autowired
    private MarketDataIngestionService ingestionService;

    @Autowired
    private MarketQuoteWebSocketHandler handler;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MarketDataProvider provider;

    @BeforeEach
    void clearQuotesAndProviderInteractions() {
        quoteRepository.deleteAll();
        reset(provider);
    }

    @Test
    void acceptsUnauthenticatedMarketOnlyConnectionAndSendsInitialPersistedQuote() throws Exception {
        saveQuote("TCS", "3800.25", "STALE");
        Probe probe = new Probe();
        WebSocketSession session = connect(probe);
        try {
            assertThat(session.isOpen()).isTrue();
            session.sendMessage(new TextMessage("{\"action\":\"subscribe\",\"symbol\":\"tcs\"}"));

            JsonNode subscribed = next(probe);
            assertThat(subscribed.path("type").asText()).isEqualTo("subscribed");
            assertThat(subscribed.path("alreadySubscribed").asBoolean()).isFalse();
            JsonNode quote = next(probe);
            assertThat(quote.path("type").asText()).isEqualTo("quote");
            assertThat(quote.path("quote").path("symbol").asText()).isEqualTo("TCS");
            assertThat(quote.path("quote").path("lastPrice").decimalValue()).isEqualByComparingTo("3800.25");
            assertThat(quote.path("quote").path("dataStatus").asText()).isEqualTo("STALE");
            verifyNoInteractions(provider);
        } finally {
            session.close();
        }
    }

    @Test
    void reportsInvalidSymbolsAndMalformedMessages() throws Exception {
        Probe probe = new Probe();
        WebSocketSession session = connect(probe);
        try {
            session.sendMessage(new TextMessage("{\"action\":\"subscribe\",\"symbol\":\"NOTREAL\"}"));
            JsonNode invalid = next(probe);
            assertThat(invalid.path("type").asText()).isEqualTo("error");
            assertThat(invalid.path("code").asText()).isEqualTo("INVALID_SYMBOL");

            session.sendMessage(new TextMessage("not-json"));
            JsonNode malformed = next(probe);
            assertThat(malformed.path("code").asText()).isEqualTo("MALFORMED_MESSAGE");
            verifyNoInteractions(provider);
        } finally {
            session.close();
        }
    }

    @Test
    void duplicateSubscriptionDoesNotRepeatInitialQuote() throws Exception {
        Probe probe = new Probe();
        WebSocketSession session = connect(probe);
        try {
            subscribe(session, probe, "TCS");
            session.sendMessage(new TextMessage("{\"action\":\"subscribe\",\"symbol\":\"TCS\"}"));
            JsonNode duplicate = next(probe);
            assertThat(duplicate.path("type").asText()).isEqualTo("subscribed");
            assertThat(duplicate.path("alreadySubscribed").asBoolean()).isTrue();
            assertThat(probe.messages.poll(250, TimeUnit.MILLISECONDS)).isNull();
            verifyNoInteractions(provider);
        } finally {
            session.close();
        }
    }

    @Test
    void unsubscribeStopsFutureQuoteBroadcasts() throws Exception {
        Probe probe = new Probe();
        WebSocketSession session = connect(probe);
        try {
            subscribe(session, probe, "TCS");
            session.sendMessage(new TextMessage("{\"action\":\"unsubscribe\",\"symbol\":\"TCS\"}"));
            JsonNode removed = next(probe);
            assertThat(removed.path("type").asText()).isEqualTo("unsubscribed");
            assertThat(removed.path("wasSubscribed").asBoolean()).isTrue();

            Instrument instrument = instrumentRepository.findByExchangeAndSymbol("NSE", "TCS").orElseThrow();
            handler.publish(MarketQuoteResponse.unavailable(instrument));
            assertThat(probe.messages.poll(250, TimeUnit.MILLISECONDS)).isNull();
            verifyNoInteractions(provider);
        } finally {
            session.close();
        }
    }

    @Test
    void ingestionBroadcastsChangedPersistedQuoteToSubscribers() throws Exception {
        Probe probe = new Probe();
        WebSocketSession session = connect(probe);
        try {
            subscribe(session, probe, "TCS");
            Instant updatedAt = Instant.now();
            when(provider.getQuotes(any())).thenReturn(List.of(new MarketQuoteSnapshot(
                    "NSE", "TCS", "TCS", updatedAt, new BigDecimal("95"), new BigDecimal("110"),
                    new BigDecimal("90"), null, new BigDecimal("94"), 1000L, new BigDecimal("101.50"),
                    "TEST_PROVIDER", updatedAt)));
            when(provider.getDataFreshness()).thenReturn(Optional.of(
                    new MarketDataFreshness("TEST_PROVIDER", true, updatedAt, Duration.ofMinutes(5))));

            var result = ingestionService.ingestCurrentQuotes(List.of("TCS"));

            assertThat(result.inserted()).isEqualTo(1);
            JsonNode update = next(probe);
            assertThat(update.path("type").asText()).isEqualTo("quote");
            assertThat(update.path("quote").path("lastPrice").decimalValue()).isEqualByComparingTo("101.50");
            assertThat(update.path("quote").path("dataStatus").asText()).isEqualTo("LIVE");
        } finally {
            session.close();
        }
    }

    @Test
    void removesDisconnectedClientWithoutFailingQuotePublication() throws Exception {
        Probe probe = new Probe();
        WebSocketSession session = connect(probe);
        session.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (handler.connectedClientCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);

        assertThat(handler.connectedClientCount()).isZero();
        Instrument instrument = instrumentRepository.findByExchangeAndSymbol("NSE", "TCS").orElseThrow();
        handler.publish(MarketQuoteResponse.unavailable(instrument));
        verifyNoInteractions(provider);
    }

    private WebSocketSession connect(Probe probe) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        return client.execute(probe, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/market-quotes")).get(10, TimeUnit.SECONDS);
    }

    private void subscribe(WebSocketSession session, Probe probe, String symbol) throws Exception {
        session.sendMessage(new TextMessage("{\"action\":\"subscribe\",\"symbol\":\"" + symbol + "\"}"));
        assertThat(next(probe).path("type").asText()).isEqualTo("subscribed");
        assertThat(next(probe).path("type").asText()).isEqualTo("quote");
    }

    private JsonNode next(Probe probe) throws Exception {
        String message = probe.messages.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("WebSocket message received").isNotNull();
        return objectMapper.readTree(message);
    }

    private void saveQuote(String symbol, String price, String status) {
        Instant now = Instant.now();
        Instrument instrument = instrumentRepository.findByExchangeAndSymbol("NSE", symbol).orElseThrow();
        var snapshot = new MarketQuoteSnapshot("NSE", symbol, symbol, now, new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("90"), null, new BigDecimal("94"), 1000L,
                new BigDecimal(price), "TEST_PROVIDER", now);
        quoteRepository.saveAndFlush(new MarketQuote(instrument, snapshot, now, "UNKNOWN", status));
    }

    private static final class Probe extends TextWebSocketHandler {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }
    }
}
