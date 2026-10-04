package com.tradecore.account;

import com.tradecore.identity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trading_account")
public class TradingAccount {
    public static final BigDecimal INITIAL_VIRTUAL_BALANCE = new BigDecimal("100000.0000");
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false, unique = true) private User user;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4) private BigDecimal availableBalance;
    @Column(name = "reserved_balance", nullable = false, precision = 19, scale = 4) private BigDecimal reservedBalance;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    protected TradingAccount() {}

    public TradingAccount(User user, Instant now) {
        this.user = user;
        this.status = "ACTIVE";
        this.currency = "INR";
        this.availableBalance = INITIAL_VIRTUAL_BALANCE;
        this.reservedBalance = BigDecimal.ZERO.setScale(4);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getStatus() { return status; }
    public String getCurrency() { return currency; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public BigDecimal getReservedBalance() { return reservedBalance; }
    public Instant getCreatedAt() { return createdAt; }

    public void reserveFunds(BigDecimal amount, Instant now) {
        if (!"ACTIVE".equals(status)) {
            throw new IllegalStateException("Trading account is not active");
        }
        if (amount == null || amount.signum() <= 0 || availableBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient available virtual funds");
        }
        availableBalance = availableBalance.subtract(amount);
        reservedBalance = reservedBalance.add(amount);
        updatedAt = now;
    }

    public void settleBuy(BigDecimal reservedAmount, BigDecimal actualAmount, Instant now) {
        if (reservedAmount == null || actualAmount == null || reservedAmount.signum() < 0 || actualAmount.signum() <= 0
                || reservedBalance.compareTo(reservedAmount) < 0) {
            throw new IllegalStateException("Invalid BUY reservation settlement");
        }
        BigDecimal topUp = actualAmount.subtract(reservedAmount).max(BigDecimal.ZERO);
        if (availableBalance.compareTo(topUp) < 0) {
            throw new IllegalStateException("Insufficient available balance for execution price");
        }
        availableBalance = availableBalance.subtract(topUp)
                .add(reservedAmount.subtract(actualAmount).max(BigDecimal.ZERO));
        reservedBalance = reservedBalance.subtract(reservedAmount);
        updatedAt = now;
    }

    public void creditSale(BigDecimal amount, Instant now) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Sale proceeds must be positive");
        availableBalance = availableBalance.add(amount);
        updatedAt = now;
    }

    public void releaseReservedFunds(BigDecimal amount, Instant now) {
        if (amount == null || amount.signum() <= 0 || reservedBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("Invalid or unavailable BUY reservation");
        }
        reservedBalance = reservedBalance.subtract(amount);
        availableBalance = availableBalance.add(amount);
        updatedAt = now;
    }
}
