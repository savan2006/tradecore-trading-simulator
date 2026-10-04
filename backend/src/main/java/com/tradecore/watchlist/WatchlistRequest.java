package com.tradecore.watchlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WatchlistRequest(@NotBlank @Size(max = 80) String name) { }
