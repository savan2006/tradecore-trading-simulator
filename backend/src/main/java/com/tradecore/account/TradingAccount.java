package com.tradecore.account;

import com.tradecore.identity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trading_account")
public class TradingAccount {
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
}
