package com.tradecore.strategylab;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BacktestRequest(
        @NotBlank String symbol,
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate,
        @NotNull BacktestStrategy strategy,
        @NotNull @Positive BigDecimal startingCapital,
        Integer fastPeriod,
        Integer slowPeriod,
        Integer rsiPeriod,
        BigDecimal oversoldThreshold,
        BigDecimal overboughtThreshold) {
}
