package com.tradecore.watchlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WatchlistItemRequest(@NotBlank @Size(max = 16) String exchange,
        @NotBlank @Size(max = 32) String symbol) { }
