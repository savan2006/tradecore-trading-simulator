package com.tradecore.watchlist;

import com.tradecore.market.MarketQuoteResponse;
import java.time.Instant;
import java.util.UUID;

public record WatchlistItemResponse(UUID id, String symbol, String exchange, String companyName,
        MarketQuoteResponse quote, Instant addedAt) { }
