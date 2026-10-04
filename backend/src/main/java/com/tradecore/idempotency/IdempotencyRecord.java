package com.tradecore.idempotency;

import com.tradecore.account.TradingAccount;
import com.tradecore.order.TradingOrder;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_record", uniqueConstraints = @UniqueConstraint(name = "uq_idempotency_account_key", columnNames = {"account_id", "idempotency_key"}))
public class IdempotencyRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "account_id", nullable = false) private TradingAccount account;
    @Column(name = "idempotency_key", nullable = false, length = 160) private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 128) private String requestFingerprint;
    @Column(nullable = false, length = 20) private String state;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "original_order_id", unique = true) private TradingOrder originalOrder;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at") private Instant expiresAt;
    protected IdempotencyRecord() {}

    public IdempotencyRecord(TradingAccount account, String idempotencyKey, String requestFingerprint, Instant now) {
        this.account = account;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.state = "IN_PROGRESS";
        this.createdAt = now;
    }

    public String getRequestFingerprint() { return requestFingerprint; }
    public String getState() { return state; }
    public TradingOrder getOriginalOrder() { return originalOrder; }

    public void complete(TradingOrder order) {
        this.originalOrder = order;
        this.state = "COMPLETED";
    }
}
