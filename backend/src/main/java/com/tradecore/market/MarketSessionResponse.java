package com.tradecore.market;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record MarketSessionResponse(LocalDate tradingDate, String status, String reason,
        LocalTime openTime, LocalTime closeTime, Instant nextOpenAt) { }
