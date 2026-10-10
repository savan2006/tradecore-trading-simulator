package com.tradecore.watchlist;

import com.tradecore.identity.User;
import com.tradecore.identity.UserRepository;
import com.tradecore.market.Instrument;
import com.tradecore.market.InstrumentRepository;
import com.tradecore.market.MarketQuote;
import com.tradecore.market.MarketQuoteRepository;
import com.tradecore.market.MarketQuoteResponse;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WatchlistService {
    private static final int MAX_WATCHLISTS_PER_USER = 20;
    private static final int MAX_ITEMS_PER_WATCHLIST = 80;
    private final WatchlistRepository watchlists;
    private final WatchlistItemRepository items;
    private final UserRepository users;
    private final InstrumentRepository instruments;
    private final MarketQuoteRepository quotes;

    public WatchlistService(WatchlistRepository watchlists, WatchlistItemRepository items, UserRepository users,
            InstrumentRepository instruments, MarketQuoteRepository quotes) {
        this.watchlists = watchlists; this.items = items; this.users = users;
        this.instruments = instruments; this.quotes = quotes;
    }

    @Transactional
    public WatchlistResponse create(String email, WatchlistRequest request) {
        User user = users.findByEmailForWatchlistUpdate(normalize(email))
                .orElseThrow(() -> notFound("User was not found"));
        if (watchlists.countByUser_Id(user.getId()) >= MAX_WATCHLISTS_PER_USER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A user may have at most " + MAX_WATCHLISTS_PER_USER + " watchlists");
        }
        Instant now = Instant.now();
        try {
            return response(watchlists.saveAndFlush(new Watchlist(user, request.name().trim(), now)), List.of());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A watchlist with that name already exists", e);
        }
    }

    @Transactional(readOnly = true)
    public List<WatchlistResponse> list(String email) {
        String owner = normalize(email);
        List<Watchlist> owned = watchlists.findAllByUser_EmailOrderByCreatedAtDescIdDesc(owner);
        List<WatchlistItem> allItems = items.findAllByWatchlist_User_EmailOrderByWatchlist_IdAscSortOrderAscCreatedAtAsc(owner);
        return mapWatchlists(owned, allItems);
    }

    @Transactional(readOnly = true)
    public WatchlistResponse get(String email, UUID id) {
        Watchlist watchlist = owned(id, email);
        return response(watchlist, items.findAllByWatchlist_IdOrderBySortOrderAscCreatedAtAsc(id));
    }

    @Transactional
    public WatchlistResponse rename(String email, UUID id, WatchlistRequest request) {
        Watchlist watchlist = watchlists.findOwnedForUpdate(id, normalize(email))
                .orElseThrow(() -> notFound("Watchlist was not found"));
        watchlist.rename(request.name().trim(), Instant.now());
        try {
            watchlists.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A watchlist with that name already exists", e);
        }
        return response(watchlist, items.findAllByWatchlist_IdOrderBySortOrderAscCreatedAtAsc(id));
    }

    @Transactional
    public void delete(String email, UUID id) {
        Watchlist watchlist = watchlists.findOwnedForUpdate(id, normalize(email))
                .orElseThrow(() -> notFound("Watchlist was not found"));
        watchlists.delete(watchlist);
    }

    @Transactional
    public WatchlistResponse add(String email, UUID id, WatchlistItemRequest request) {
        Watchlist watchlist = watchlists.findOwnedForUpdate(id, normalize(email))
                .orElseThrow(() -> notFound("Watchlist was not found"));
        String exchange = request.exchange().trim().toUpperCase(Locale.ROOT);
        String symbol = request.symbol().trim().toUpperCase(Locale.ROOT);
        Instrument instrument = instruments.findByExchangeAndSymbol(exchange, symbol).filter(Instrument::isTradable)
                .orElseThrow(() -> notFound("Supported instrument was not found"));
        if (items.findByWatchlist_IdAndInstrument_Id(id, instrument.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instrument is already in this watchlist");
        }
        if (items.countByWatchlist_Id(id) >= MAX_ITEMS_PER_WATCHLIST) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A watchlist may contain at most " + MAX_ITEMS_PER_WATCHLIST + " instruments");
        }
        items.saveAndFlush(new WatchlistItem(watchlist, instrument, items.countByWatchlist_Id(id), Instant.now()));
        return response(watchlist, items.findAllByWatchlist_IdOrderBySortOrderAscCreatedAtAsc(id));
    }

    @Transactional
    public void remove(String email, UUID id, String symbol) {
        watchlists.findOwnedForUpdate(id, normalize(email))
                .orElseThrow(() -> notFound("Watchlist was not found"));
        Instrument instrument = instruments.findByExchangeAndSymbol("NSE", symbol.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> notFound("Instrument was not found"));
        items.deleteByWatchlist_IdAndInstrument_Id(id, instrument.getId());
    }

    private Watchlist owned(UUID id, String email) {
        return watchlists.findByIdAndUser_Email(id, normalize(email))
                .orElseThrow(() -> notFound("Watchlist was not found"));
    }
    private List<WatchlistResponse> mapWatchlists(List<Watchlist> owned, List<WatchlistItem> allItems) {
        Map<UUID, List<WatchlistItem>> grouped = allItems.stream().collect(Collectors.groupingBy(
                item -> item.getWatchlist().getId()));
        List<UUID> instrumentIds = allItems.stream().map(i -> i.getInstrument().getId()).distinct().toList();
        Map<UUID, MarketQuote> quoteMap = quoteMap(instrumentIds);
        return owned.stream().map(w -> response(w, grouped.getOrDefault(w.getId(), List.of()), quoteMap)).toList();
    }
    private WatchlistResponse response(Watchlist watchlist, List<WatchlistItem> watchlistItems) {
        Collection<UUID> instrumentIds = watchlistItems.stream().map(i -> i.getInstrument().getId()).toList();
        return response(watchlist, watchlistItems, quoteMap(instrumentIds));
    }
    private Map<UUID, MarketQuote> quoteMap(Collection<UUID> instrumentIds) {
        return instrumentIds.isEmpty() ? Map.of()
                : quotes.findAllByInstrument_IdIn(instrumentIds).stream().collect(Collectors.toMap(
                        q -> q.getInstrument().getId(), Function.identity()));
    }
    private WatchlistResponse response(Watchlist watchlist, List<WatchlistItem> watchlistItems,
            Map<UUID, MarketQuote> quoteMap) {
        Instant now = Instant.now();
        List<WatchlistItemResponse> itemResponses = watchlistItems.stream().map(item -> {
            Instrument instrument = item.getInstrument();
            MarketQuote quote = quoteMap.get(instrument.getId());
            MarketQuoteResponse quoteResponse = quote == null
                    ? MarketQuoteResponse.unavailable(instrument) : MarketQuoteResponse.from(quote, now);
            return new WatchlistItemResponse(item.getId(), instrument.getSymbol(), instrument.getExchange(),
                    instrument.getCompanyName(), quoteResponse, item.getCreatedAt());
        }).toList();
        return new WatchlistResponse(watchlist.getId(), watchlist.getName(), watchlist.getCreatedAt(),
                watchlist.getUpdatedAt(), itemResponses);
    }
    private static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
