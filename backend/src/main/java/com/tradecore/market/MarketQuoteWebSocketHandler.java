package com.tradecore.market;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** In-process stream of provider-neutral quotes already persisted by the market-data flow. */
@Component
public class MarketQuoteWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketQuoteWebSocketHandler.class);
    private static final String EXCHANGE = "NSE";
    private static final int MAX_SUBSCRIPTIONS = 100;
    private static final int MAX_MESSAGE_BYTES = 1024;
    private static final int MAX_MESSAGES_PER_WINDOW = 60;
    private static final long MESSAGE_WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
    private static final int SEND_TIME_LIMIT_MILLIS = 5_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 64 * 1024;

    private final InstrumentRepository instruments;
    private final MarketDataQueryService marketData;
    private final ObjectMapper objectMapper;
    private final Map<String, ClientState> clients = new ConcurrentHashMap<>();

    public MarketQuoteWebSocketHandler(InstrumentRepository instruments,
            MarketDataQueryService marketData, ObjectMapper objectMapper) {
        this.instruments = instruments;
        this.marketData = marketData;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession boundedSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES);
        clients.put(session.getId(), new ClientState(boundedSession));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientState client = clients.get(session.getId());
        if (client == null) return;
        synchronized (client) {
            long now = System.nanoTime();
            if (now - client.messageWindowStartedAt >= MESSAGE_WINDOW_NANOS) {
                client.messageWindowStartedAt = now;
                client.messagesInWindow = 0;
            }
            if (client.messagesInWindow >= MAX_MESSAGES_PER_WINDOW) {
                sendError(client, "MESSAGE_RATE_LIMIT", "Too many messages; try again shortly.");
                return;
            }
            client.messagesInWindow++;
        }
        if (message.getPayloadLength() > MAX_MESSAGE_BYTES) {
            sendError(client, "MALFORMED_MESSAGE", "Client messages must be 1 KB or smaller.");
            return;
        }

        JsonNode command;
        try {
            command = objectMapper.readTree(message.getPayload());
        } catch (JacksonException malformed) {
            sendError(client, "MALFORMED_MESSAGE", "Send a JSON object with action and symbol fields.");
            return;
        }
        if (command == null || !command.isObject()
                || !command.path("action").isTextual() || !command.path("symbol").isTextual()) {
            sendError(client, "MALFORMED_MESSAGE", "Send a JSON object with action and symbol fields.");
            return;
        }

        String action = command.path("action").asText().trim().toLowerCase(Locale.ROOT);
        String symbol = command.path("symbol").asText().trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty() || symbol.length() > 32) {
            sendError(client, "MALFORMED_MESSAGE", "Symbol must contain between 1 and 32 characters.");
            return;
        }
        if (!"subscribe".equals(action) && !"unsubscribe".equals(action)) {
            sendError(client, "INVALID_ACTION", "Action must be subscribe or unsubscribe.");
            return;
        }
        if (instruments.findByExchangeAndSymbol(EXCHANGE, symbol).filter(Instrument::isTradable).isEmpty()) {
            sendError(client, "INVALID_SYMBOL", "Symbol is not supported for market streaming.");
            return;
        }

        synchronized (client) {
            if (!client.session.isOpen()) {
                remove(client);
                return;
            }
            if ("subscribe".equals(action)) {
                subscribe(client, symbol);
            } else {
                boolean removed = client.subscriptions.remove(symbol);
                send(client, Map.of("type", "unsubscribed", "symbol", symbol, "wasSubscribed", removed));
            }
        }
    }

    private void subscribe(ClientState client, String symbol) {
        if (client.subscriptions.contains(symbol)) {
            send(client, Map.of("type", "subscribed", "symbol", symbol, "alreadySubscribed", true));
            return;
        }
        if (client.subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            sendError(client, "SUBSCRIPTION_LIMIT", "A client may subscribe to at most 100 symbols.");
            return;
        }
        client.subscriptions.add(symbol);
        send(client, Map.of("type", "subscribed", "symbol", symbol, "alreadySubscribed", false));
        try {
            sendQuote(client, marketData.getQuote(EXCHANGE, symbol));
        } catch (ResponseStatusException missingInstrument) {
            client.subscriptions.remove(symbol);
            sendError(client, "INVALID_SYMBOL", "Symbol is not supported for market streaming.");
        } catch (RuntimeException unavailable) {
            log.warn("Could not load persisted initial market quote for {} category={}", symbol,
                    unavailable.getClass().getSimpleName());
            sendError(client, "QUOTE_UNAVAILABLE", "The persisted quote could not be loaded.");
        }
    }

    /** Called only after the quote transaction commits. */
    public void publish(MarketQuoteResponse quote) {
        for (ClientState client : clients.values()) {
            synchronized (client) {
                if (!client.session.isOpen()) {
                    remove(client);
                } else if (client.subscriptions.contains(quote.symbol())) {
                    sendQuote(client, quote);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        ClientState client = clients.remove(session.getId());
        if (client != null && client.session.isOpen()) {
            try {
                client.session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException closeFailure) {
                log.debug("Unable to close failed market quote WebSocket session {} category={}",
                        session.getId(), closeFailure.getClass().getSimpleName());
            }
        }
    }

    int connectedClientCount() {
        return clients.size();
    }

    private void sendQuote(ClientState client, MarketQuoteResponse quote) {
        send(client, Map.of("type", "quote", "quote", quote));
    }

    private void sendError(ClientState client, String code, String message) {
        send(client, Map.of("type", "error", "code", code, "message", message));
    }

    private void send(ClientState client, Object payload) {
        try {
            client.session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException | RuntimeException failure) {
            log.debug("Removing disconnected or slow market quote WebSocket client {} category={}",
                    client.session.getId(), failure.getClass().getSimpleName());
            remove(client);
        }
    }

    private void remove(ClientState client) {
        clients.remove(client.session.getId(), client);
        if (client.session.isOpen()) {
            try {
                client.session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException closeFailure) {
                log.debug("Unable to close market quote WebSocket client {} category={}",
                        client.session.getId(), closeFailure.getClass().getSimpleName());
            }
        }
    }

    private static final class ClientState {
        private final WebSocketSession session;
        private final Set<String> subscriptions = new HashSet<>();
        private long messageWindowStartedAt = System.nanoTime();
        private int messagesInWindow;

        private ClientState(WebSocketSession session) {
            this.session = session;
        }
    }
}
