package com.tradecore.ledger;

import com.tradecore.account.TradingAccount;
import com.tradecore.execution.Execution;
import com.tradecore.order.TradingOrder;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entry", indexes = @Index(name = "ix_ledger_account_time", columnList = "account_id,occurred_at"))
public class LedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "account_id", nullable = false) private TradingAccount account;
    @Column(name = "entry_type", nullable = false, length = 24) private String entryType;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "order_id") private TradingOrder order;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "execution_id") private Execution execution;
    @Column(nullable = false, length = 500) private String description;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    protected LedgerEntry() {}
}
