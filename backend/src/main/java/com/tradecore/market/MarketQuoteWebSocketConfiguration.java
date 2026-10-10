package com.tradecore.market;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class MarketQuoteWebSocketConfiguration implements WebSocketConfigurer {

    private final MarketQuoteWebSocketHandler handler;
    private final String[] allowedOrigins;

    public MarketQuoteWebSocketConfiguration(MarketQuoteWebSocketHandler handler,
            @Value("${tradecore.websocket.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/market-quotes")
                .setAllowedOrigins(allowedOrigins);
    }
}
