package com.tradecore.order;

import com.tradecore.account.TradingAccount;
import com.tradecore.market.Instrument;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trading_order",
        indexes = @Index(name = "ix_order_account_created", columnList = "account_id,created_at"),
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_trading_order_account_id", columnNames = {"account_id", "id"}),
                @UniqueConstraint(name = "uq_trading_order_execution_key", columnNames = {"id", "instrument_id", "side", "trading_mode"})
        })
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
    @Column(name = "reserved_amount", nullable = false, precision = 19, scale = 4) private BigDecimal reservedAmount;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    protected TradingOrder() {}

    public TradingOrder(TradingAccount account, Instrument instrument, String side, String orderType,
            String tradingMode, long quantity, BigDecimal limitPrice, BigDecimal reservedAmount, Instant now) {
        this.account = account;
        this.instrument = instrument;
        this.side = side;
        this.orderType = orderType;
        this.tradingMode = tradingMode;
        this.requestedQuantity = quantity;
        this.executedQuantity = 0;
        this.remainingQuantity = quantity;
        this.limitPrice = limitPrice;
        this.reservedAmount = reservedAmount == null ? BigDecimal.ZERO.setScale(4) : reservedAmount;
        this.status = "PENDING";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public TradingAccount getAccount() { return account; }
    public Instrument getInstrument() { return instrument; }
    public String getSide() { return side; }
    public String getOrderType() { return orderType; }
    public String getTradingMode() { return tradingMode; }
    public long getRequestedQuantity() { return requestedQuantity; }
    public long getExecutedQuantity() { return executedQuantity; }
    public long getRemainingQuantity() { return remainingQuantity; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public BigDecimal getReservedAmount() { return reservedAmount; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void fill(long quantity, Instant now) {
        if (!"PENDING".equals(status) || quantity != remainingQuantity || quantity <= 0) {
            throw new IllegalStateException("Only a complete pending order fill is supported");
        }
        executedQuantity += quantity;
        remainingQuantity = 0;
        status = "FILLED";
        updatedAt = now;
    }

    public void cancel(Instant now) {
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Only pending orders can be cancelled");
        }
        status = "CANCELLED";
        updatedAt = now;
    }
}
