package com.tradecore.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record AdminMarketCalendarRequest(
        @NotNull LocalDate tradingDate,
        @NotNull Boolean holiday,
        LocalTime sessionOpen,
        LocalTime sessionClose,
        @Size(max = 240) String description) { }
