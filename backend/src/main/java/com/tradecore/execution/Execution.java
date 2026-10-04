package com.tradecore.execution;

import com.tradecore.account.TradingAccount;
import com.tradecore.market.Instrument;
import com.tradecore.order.TradingOrder;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution", indexes = @Index(name = "ix_execution_order_time", columnList = "order_id,executed_at"))
public class Execution {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "account_id", nullable = false) private TradingAccount account;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id", nullable = false) private TradingOrder order;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "instrument_id", nullable = false) private Instrument instrument;
    @Column(nullable = false, length = 8) private String side;
    @Column(name = "trading_mode", nullable = false, length = 12) private String tradingMode;
    @Column(nullable = false) private long quantity;
    @Column(nullable = false, precision = 19, scale = 6) private BigDecimal price;
    @Column(name = "executed_at", nullable = false) private Instant executedAt;
    @Column(name = "market_price", precision = 19, scale = 6) private BigDecimal marketPrice;
    @Column(name = "market_at") private Instant marketAt;
    @Column(name = "reference_metadata", columnDefinition = "text") private String referenceMetadata;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal fee;
    protected Execution() {}
}
