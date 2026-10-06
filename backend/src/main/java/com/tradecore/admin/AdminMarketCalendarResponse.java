package com.tradecore.admin;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AdminMarketCalendarResponse(UUID id, LocalDate tradingDate, boolean holiday,
        LocalTime sessionOpen, LocalTime sessionClose, String description, boolean active) { }
