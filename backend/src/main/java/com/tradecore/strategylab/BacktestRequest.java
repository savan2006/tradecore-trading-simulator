package com.tradecore.strategylab;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Parameters for an educational historical simulation using persisted daily candles and simulated test capital.")
public record BacktestRequest(
        @NotBlank @Size(max = 32) String symbol,
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate,
        @NotNull BacktestStrategy strategy,
        @NotNull @Positive @Digits(integer = 19, fraction = 6) BigDecimal startingCapital,
        @Min(2) @Max(100) Integer fastPeriod,
        @Min(2) @Max(100) Integer slowPeriod,
        @Min(2) @Max(100) Integer rsiPeriod,
        @Digits(integer = 3, fraction = 4)
        BigDecimal oversoldThreshold,
        @Digits(integer = 3, fraction = 4)
        BigDecimal overboughtThreshold) {
}
