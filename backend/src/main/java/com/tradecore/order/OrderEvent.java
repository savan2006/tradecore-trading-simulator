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
}
