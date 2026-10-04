package com.tradecore.watchlist;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WatchlistResponse(UUID id, String name, Instant createdAt, Instant updatedAt,
        List<WatchlistItemResponse> items) { }
