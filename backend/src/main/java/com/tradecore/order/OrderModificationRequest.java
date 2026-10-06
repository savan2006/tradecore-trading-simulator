package com.tradecore.order;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Only the fields mutable for the order's existing type are accepted. */
public record OrderModificationRequest(
        @Positive Long quantity,
        BigDecimal limitPrice,
        BigDecimal triggerPrice) {

    @JsonAnySetter
    public void rejectUnsupportedField(String field, Object value) {
        throw new IllegalArgumentException("Only quantity, limitPrice, and triggerPrice may be modified");
    }
}
