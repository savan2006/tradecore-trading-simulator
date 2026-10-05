package com.tradecore.portfolio;

import com.tradecore.account.TradingAccount;
import com.tradecore.market.Instrument;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "position", uniqueConstraints = @UniqueConstraint(name = "uq_position_account_instrument_mode", columnNames = {"account_id", "instrument_id", "trading_mode"}))
public class Position {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "account_id", nullable = false) private TradingAccount account;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "instrument_id", nullable = false) private Instrument instrument;
    @Column(name = "trading_mode", nullable = false, length = 12) private String tradingMode;
    @Column(nullable = false) private long quantity;
    @Column(name = "reserved_quantity", nullable = false) private long reservedQuantity;
    @Column(name = "average_price", nullable = false, precision = 19, scale = 6) private BigDecimal averagePrice;
    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 4) private BigDecimal realizedPnl;
    @Column(name = "reference_price", precision = 19, scale = 6) private BigDecimal referencePrice;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    protected Position() {}

    public Position(TradingAccount account, Instrument instrument, String tradingMode, Instant now) {
        this.account = account;
        this.instrument = instrument;
        this.tradingMode = tradingMode;
        this.quantity = 0;
        this.reservedQuantity = 0;
        this.averagePrice = BigDecimal.ZERO.setScale(6);
        this.realizedPnl = BigDecimal.ZERO.setScale(4);
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public TradingAccount getAccount() { return account; }
    public Instrument getInstrument() { return instrument; }
    public String getTradingMode() { return tradingMode; }
    public long getQuantity() { return quantity; }
    public long getReservedQuantity() { return reservedQuantity; }
    public BigDecimal getAveragePrice() { return averagePrice; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void reserve(long amount, Instant now) {
        if (amount <= 0 || quantity - reservedQuantity < amount) {
            throw new IllegalStateException("Insufficient sellable position quantity");
        }
        reservedQuantity += amount;
        updatedAt = now;
    }

    public void settleBuy(long amount, BigDecimal price, Instant now) {
        if (amount <= 0 || price == null || price.signum() <= 0) throw new IllegalArgumentException("Invalid BUY fill");
        BigDecimal oldCost = averagePrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal addedCost = price.multiply(BigDecimal.valueOf(amount));
        quantity += amount;
        averagePrice = oldCost.add(addedCost).divide(BigDecimal.valueOf(quantity), 6, java.math.RoundingMode.HALF_UP);
        updatedAt = now;
    }

    public void settleSell(long amount, BigDecimal price, Instant now) {
        if (amount <= 0 || amount > reservedQuantity || amount > quantity || price == null || price.signum() <= 0) {
            throw new IllegalStateException("Invalid SELL fill or reservation");
        }
        BigDecimal realized = price.subtract(averagePrice).multiply(BigDecimal.valueOf(amount))
                .setScale(4, java.math.RoundingMode.HALF_UP);
        quantity -= amount;
        reservedQuantity -= amount;
        realizedPnl = realizedPnl.add(realized);
        if (quantity == 0) averagePrice = BigDecimal.ZERO.setScale(6);
        updatedAt = now;
    }

    public void releaseReservation(long amount, Instant now) {
        if (amount <= 0 || amount > reservedQuantity) {
            throw new IllegalStateException("Invalid or unavailable SELL reservation");
        }
        reservedQuantity -= amount;
        updatedAt = now;
    }
}
