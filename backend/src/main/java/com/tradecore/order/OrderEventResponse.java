package com.tradecore.order;

import java.time.Instant;

public record OrderEventResponse(String type, String previousState, String newState, Instant occurredAt) {
    static OrderEventResponse from(OrderEvent event) {
        return new OrderEventResponse(event.getEventType(), event.getPreviousState(), event.getNewState(), event.getOccurredAt());
    }
}
