package com.tradecore.alert;

import com.tradecore.audit.AuditService;
import com.tradecore.identity.User;
import com.tradecore.identity.UserRepository;
import com.tradecore.market.Instrument;
import com.tradecore.market.InstrumentRepository;
import com.tradecore.market.MarketQuote;
import com.tradecore.market.MarketQuoteRepository;
import com.tradecore.notification.Notification;
import com.tradecore.notification.NotificationRepository;
import com.tradecore.watchlist.Watchlist;
import com.tradecore.watchlist.WatchlistItemRepository;
import com.tradecore.watchlist.WatchlistRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PriceAlertService {
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(600);
    private final UserRepository users;
    private final WatchlistRepository watchlists;
    private final WatchlistItemRepository items;
    private final InstrumentRepository instruments;
    private final PriceAlertRepository alerts;
    private final MarketQuoteRepository quotes;
    private final NotificationRepository notifications;
    private final AuditService auditService;

    public PriceAlertService(UserRepository users, WatchlistRepository watchlists, WatchlistItemRepository items,
            InstrumentRepository instruments, PriceAlertRepository alerts, MarketQuoteRepository quotes,
            NotificationRepository notifications, AuditService auditService) {
        this.users = users; this.watchlists = watchlists; this.items = items; this.instruments = instruments;
        this.alerts = alerts; this.quotes = quotes; this.notifications = notifications;
        this.auditService = auditService;
    }

    @Transactional
    public PriceAlertResponse create(String email, PriceAlertRequest request) {
        String ownerEmail = normalize(email);
        Watchlist watchlist = watchlists.findOwnedForUpdate(request.watchlistId(), ownerEmail)
                .orElseThrow(() -> notFound("Watchlist was not found"));
        Instrument instrument = instruments.findById(request.instrumentId()).filter(Instrument::isTradable)
                .orElseThrow(() -> notFound("Supported instrument was not found"));
        if (items.findByWatchlist_IdAndInstrument_Id(watchlist.getId(), instrument.getId()).isEmpty()) {
            throw badRequest("Instrument must be in the watchlist before creating an alert");
        }
        String condition = request.condition().trim().toUpperCase(Locale.ROOT);
        BigDecimal target = request.targetPrice().stripTrailingZeros();
        if (alerts.existsByWatchlist_IdAndInstrument_IdAndConditionAndTargetPriceAndActiveTrue(
                watchlist.getId(), instrument.getId(), condition, target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An equivalent active alert already exists");
        }
        User user = watchlist.getUser();
        return PriceAlertResponse.from(alerts.saveAndFlush(
                new PriceAlert(user, watchlist, instrument, condition, target, Instant.now())));
    }

    @Transactional(readOnly = true)
    public List<PriceAlertResponse> list(String email) {
        return alerts.findAllByUser_EmailOrderByCreatedAtDescIdDesc(normalize(email)).stream()
                .map(PriceAlertResponse::from).toList();
    }

    @Transactional
    public void delete(String email, UUID id) {
        PriceAlert alert = alerts.findOwnedForUpdate(id, normalize(email))
                .orElseThrow(() -> notFound("Price alert was not found"));
        alerts.delete(alert);
    }

    @Transactional
    public boolean process(UUID alertId, Instant now) {
        PriceAlert alert = alerts.findForUpdate(alertId).orElse(null);
        if (alert == null || !alert.isActive()) return false;
        MarketQuote quote = quotes.findByInstrument_Id(alert.getInstrument().getId()).orElse(null);
        if (!isFresh(quote, now)) return false;
        BigDecimal price = quote.getLastPrice();
        boolean triggered = "ABOVE".equals(alert.getCondition())
                ? price.compareTo(alert.getTargetPrice()) >= 0
                : price.compareTo(alert.getTargetPrice()) <= 0;
        if (!triggered) return false;
        alert.markTriggered(now);
        String title = alert.getInstrument().getSymbol() + " price alert triggered";
        String message = alert.getInstrument().getSymbol() + " reached " + price.toPlainString()
                + " and met your " + alert.getCondition() + " target of " + alert.getTargetPrice().toPlainString() + ".";
        notifications.saveAndFlush(new Notification(alert.getUser(), "PRICE_ALERT", title, message, now));
        alerts.flush();
        auditService.record(alert.getUser().getEmail(), "PRICE_ALERT_TRIGGERED", "PRICE_ALERT", alert.getId(),
                "{\"symbol\":\"" + alert.getInstrument().getSymbol() + "\",\"condition\":\""
                        + alert.getCondition() + "\"}");
        return true;
    }

    @Transactional(readOnly = true)
    public List<UUID> activeAlertIds() { return alerts.findAllActiveIds(); }

    private static boolean isFresh(MarketQuote quote, Instant now) {
        if (quote == null || quote.getLastPrice() == null || !"LIVE".equals(quote.getDataStatus())
                || quote.getProviderUpdatedAt() == null || quote.getReceivedAt() == null) return false;
        Instant cutoff = now.minus(MAX_QUOTE_AGE);
        return !quote.getProviderUpdatedAt().isBefore(cutoff) && !quote.getProviderUpdatedAt().isAfter(now)
                && !quote.getReceivedAt().isBefore(cutoff) && !quote.getReceivedAt().isAfter(now);
    }
    private static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
