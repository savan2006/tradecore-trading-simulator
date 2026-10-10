package com.tradecore.market;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Optional instrument subset and period for an administrator-triggered historical backfill. */
public record HistoricalBackfillRequest(@Min(1) @Max(36) Integer months,
        @Size(max = 80) List<@Size(min = 1, max = 32) String> symbols) {
}
