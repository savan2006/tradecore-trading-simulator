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
}
