package com.tradecore.market;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "market_candle", uniqueConstraints = @UniqueConstraint(name = "uq_market_candle_bucket", columnNames = {"instrument_id", "resolution", "bucket_start"}), indexes = @Index(name = "ix_market_candle_history", columnList = "instrument_id,resolution,bucket_start"))
public class MarketCandle {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "instrument_id", nullable = false) private Instrument instrument;
    @Column(nullable = false, length = 12) private String resolution;
    @Column(name = "bucket_start", nullable = false) private Instant bucketStart;
    @Column(name = "open_price", nullable = false, precision = 19, scale = 6) private BigDecimal openPrice;
    @Column(name = "high_price", nullable = false, precision = 19, scale = 6) private BigDecimal highPrice;
    @Column(name = "low_price", nullable = false, precision = 19, scale = 6) private BigDecimal lowPrice;
    @Column(name = "close_price", nullable = false, precision = 19, scale = 6) private BigDecimal closePrice;
    @Column(nullable = false) private long volume;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected MarketCandle() {}
}
