package com.tradecore.market;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class MarketQuoteWebSocketConfiguration implements WebSocketConfigurer {

    private final MarketQuoteWebSocketHandler handler;

    public MarketQuoteWebSocketConfiguration(MarketQuoteWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/market-quotes")
                // This endpoint intentionally streams only non-user-specific persisted market data.
                .setAllowedOriginPatterns("*");
    }
}
