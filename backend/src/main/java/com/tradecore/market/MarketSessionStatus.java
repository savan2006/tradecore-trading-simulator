package com.tradecore.market;

import java.time.Instant;
import java.time.LocalDate;

/** Normalized exchange session state when the provider actually supplies it. */
public record MarketSessionStatus(
        String exchange,
        State state,
        LocalDate tradingDate,
        Instant opensAt,
        Instant closesAt,
        Instant dataUpdatedAt) {

    public enum State {
        OPEN,
        CLOSED,
        UNKNOWN
    }
}
