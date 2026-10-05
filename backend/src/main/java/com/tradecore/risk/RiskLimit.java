package com.tradecore.risk;

import com.tradecore.account.TradingAccount;
import com.tradecore.market.Instrument;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_limit")
public class RiskLimit {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id") private TradingAccount account;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "instrument_id") private Instrument instrument;
    @Column(nullable = false, length = 16) private String scope;
    @Column(name = "limit_type", nullable = false, length = 32) private String limitType;
    @Column(name = "limit_value", nullable = false, precision = 19, scale = 4) private BigDecimal limitValue;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "effective_from") private Instant effectiveFrom;
    @Column(name = "effective_until") private Instant effectiveUntil;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected RiskLimit() {}

    public Instrument getInstrument() { return instrument; }
    public String getScope() { return scope; }
    public String getLimitType() { return limitType; }
    public BigDecimal getLimitValue() { return limitValue; }
    public boolean isEnabled() { return enabled; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveUntil() { return effectiveUntil; }
}
