package com.tradecore.market;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "market_session")
public class MarketSession {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "trading_date", nullable = false, unique = true) private LocalDate tradingDate;
    @Column(name = "session_state", nullable = false, length = 24) private String sessionState;
    @Column(name = "opens_at") private Instant opensAt;
    @Column(name = "closes_at") private Instant closesAt;
    @Column(name = "square_off_at") private Instant squareOffAt;
    @Column(nullable = false) private boolean holiday;
    @Column(length = 240) private String description;
    @Column(nullable = false) private boolean active = true;
    protected MarketSession() {}

    public MarketSession(LocalDate tradingDate, boolean holiday, Instant opensAt, Instant closesAt,
            String description) {
        this.tradingDate = tradingDate;
        this.holiday = holiday;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.description = description;
        this.sessionState = holiday ? "HOLIDAY" : "OPEN";
        this.active = true;
    }

    public void update(boolean holiday, Instant opensAt, Instant closesAt, String description) {
        this.holiday = holiday;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.description = description;
        this.sessionState = holiday ? "HOLIDAY" : "OPEN";
    }

    public void deactivate() { this.active = false; }
    public UUID getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public String getSessionState() { return sessionState; }
    public Instant getOpensAt() { return opensAt; }
    public Instant getClosesAt() { return closesAt; }
    public boolean isHoliday() { return holiday; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
}
