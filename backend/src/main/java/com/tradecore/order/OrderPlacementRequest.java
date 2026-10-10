package com.tradecore.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "A simulated order request for a supported NSE instrument. Prices use decimal precision; no real broker or money is involved.")
public record OrderPlacementRequest(
        @NotBlank @Size(max = 16) String exchange,
        @NotBlank @Size(max = 32) String symbol,
        @NotBlank @Size(max = 8) String side,
        @NotBlank @Size(max = 12) String orderType,
        @NotBlank @Size(max = 12) String tradingMode,
        @Positive long quantity,
        @Digits(integer = 19, fraction = 6) BigDecimal limitPrice,
        @Digits(integer = 19, fraction = 6) BigDecimal triggerPrice) {

    public OrderPlacementRequest(String exchange, String symbol, String side, String orderType,
            String tradingMode, long quantity, BigDecimal limitPrice) {
        this(exchange, symbol, side, orderType, tradingMode, quantity, limitPrice, null);
    }
}
