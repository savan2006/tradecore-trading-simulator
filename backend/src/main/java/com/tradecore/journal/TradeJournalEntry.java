package com.tradecore.journal;

import com.tradecore.identity.User;
import com.tradecore.order.TradingOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trade_journal", uniqueConstraints = @UniqueConstraint(name = "uq_trade_journal_order", columnNames = "order_id"))
public class TradeJournalEntry {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id", nullable = false) private TradingOrder order;
    @Column(nullable = false, columnDefinition = "text") private String thesis;
    @Column(name = "strategy_tag", length = 80) private String strategyTag;
    @Column(name = "went_well", columnDefinition = "text") private String wentWell;
    @Column(name = "went_wrong", columnDefinition = "text") private String wentWrong;
    @Column(name = "lesson_learned", columnDefinition = "text") private String lessonLearned;
    private Short rating;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected TradeJournalEntry() { }

    public TradeJournalEntry(User user, TradingOrder order, String thesis, String strategyTag,
            String wentWell, String wentWrong, String lessonLearned, Short rating, Instant now) {
        this.user = user;
        this.order = order;
        this.createdAt = now;
        update(thesis, strategyTag, wentWell, wentWrong, lessonLearned, rating, now);
    }

    public void update(String thesis, String strategyTag, String wentWell, String wentWrong,
            String lessonLearned, Short rating, Instant now) {
        this.thesis = thesis;
        this.strategyTag = strategyTag;
        this.wentWell = wentWell;
        this.wentWrong = wentWrong;
        this.lessonLearned = lessonLearned;
        this.rating = rating;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public TradingOrder getOrder() { return order; }
    public String getThesis() { return thesis; }
    public String getStrategyTag() { return strategyTag; }
    public String getWentWell() { return wentWell; }
    public String getWentWrong() { return wentWrong; }
    public String getLessonLearned() { return lessonLearned; }
    public Short getRating() { return rating; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
