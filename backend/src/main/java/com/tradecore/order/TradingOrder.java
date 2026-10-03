package com.tradecore.order;

import com.tradecore.account.TradingAccount;
import com.tradecore.market.Instrument;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trading_order", indexes = @Index(name = "ix_order_account_created", columnList = "account_id,created_at"))
public class TradingOrder {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "account_id", nullable = false) private TradingAccount account;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "instrument_id", nullable = false) private Instrument instrument;
    @Column(nullable = false, length = 8) private String side;
    @Column(name = "order_type", nullable = false, length = 12) private String orderType;
    @Column(name = "trading_mode", nullable = false, length = 12) private String tradingMode;
    @Column(name = "requested_quantity", nullable = false) private long requestedQuantity;
    @Column(name = "executed_quantity", nullable = false) private long executedQuantity;
    @Column(name = "remaining_quantity", nullable = false) private long remainingQuantity;
    @Column(name = "limit_price", precision = 19, scale = 6) private BigDecimal limitPrice;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    protected TradingOrder() {}
}
