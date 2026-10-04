package com.tradecore.order;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_event", indexes = @Index(name = "ix_order_event_order_time", columnList = "order_id,occurred_at"))
public class OrderEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id", nullable = false) private TradingOrder order;
    @Column(name = "previous_state", length = 24) private String previousState;
    @Column(name = "new_state", nullable = false, length = 24) private String newState;
    @Column(name = "event_type", nullable = false, length = 32) private String eventType;
    @Column(length = 500) private String reason;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    protected OrderEvent() {}

    public OrderEvent(TradingOrder order, String previousState, String newState,
            String eventType, String reason, Instant occurredAt) {
        this.order = order;
        this.previousState = previousState;
        this.newState = newState;
        this.eventType = eventType;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public TradingOrder getOrder() { return order; }
    public String getPreviousState() { return previousState; }
    public String getNewState() { return newState; }
    public String getEventType() { return eventType; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
}
