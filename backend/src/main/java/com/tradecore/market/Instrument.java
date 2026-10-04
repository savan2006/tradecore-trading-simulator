package com.tradecore.market;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "instrument", uniqueConstraints = @UniqueConstraint(name = "uq_instrument_exchange_symbol", columnNames = {"exchange", "symbol"}))
public class Instrument {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 32) private String symbol;
    @Column(name = "company_name", nullable = false, length = 160) private String companyName;
    @Column(nullable = false, length = 16) private String exchange;
    @Column(name = "instrument_type", nullable = false, length = 24) private String instrumentType;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false) private boolean tradable;
    @Column(name = "provider_instrument_key", unique = true, length = 160) private String providerInstrumentKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Instrument() {}

    public UUID getId() { return id; }
    public String getSymbol() { return symbol; }
    public String getCompanyName() { return companyName; }
    public String getExchange() { return exchange; }
    public String getInstrumentType() { return instrumentType; }
    public String getCurrency() { return currency; }
    public boolean isTradable() { return tradable; }
}
