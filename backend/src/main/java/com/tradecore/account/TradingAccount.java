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
}
