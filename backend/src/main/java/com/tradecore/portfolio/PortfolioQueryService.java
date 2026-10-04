package com.tradecore.portfolio;

import com.tradecore.account.TradingAccount;
import com.tradecore.account.TradingAccountRepository;
import com.tradecore.identity.User;
import com.tradecore.identity.UserRepository;
import com.tradecore.market.MarketQuote;
import com.tradecore.market.MarketQuoteRepository;
import com.tradecore.market.MarketQuoteResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PortfolioQueryService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);
    private final UserRepository userRepository;
    private final TradingAccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final MarketQuoteRepository quoteRepository;

    public PortfolioQueryService(UserRepository userRepository, TradingAccountRepository accountRepository,
            PositionRepository positionRepository, MarketQuoteRepository quoteRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.quoteRepository = quoteRepository;
    }

    @Transactional(readOnly = true)
    public PortfolioResponse currentPortfolio(String authenticatedEmail) {
        User user = userRepository.findByEmail(authenticatedEmail.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User account not found"));
        TradingAccount account = accountRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trading account not found"));
        List<Position> storedPositions = positionRepository
                .findAllByAccount_IdOrderByInstrument_ExchangeAscInstrument_SymbolAscTradingModeAsc(account.getId());
        List<Position> positions = storedPositions.stream().filter(position -> position.getQuantity() > 0).toList();
        Instant now = Instant.now();
        Map<UUID, MarketQuote> quoteByInstrument = positions.isEmpty() ? Map.of()
                : quoteRepository.findAllByInstrument_IdIn(positions.stream()
                                .map(position -> position.getInstrument().getId()).distinct().toList())
                        .stream().collect(Collectors.toMap(quote -> quote.getInstrument().getId(), Function.identity()));

        BigDecimal investedCost = ZERO;
        BigDecimal realizedPnl = storedPositions.stream().map(Position::getRealizedPnl)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal currentMarketValue = ZERO;
        BigDecimal unrealizedPnl = ZERO;
        boolean fullyValued = true;
        boolean hasStale = false;
        boolean hasUnavailable = false;
        var responsePositions = new java.util.ArrayList<PortfolioPositionResponse>(positions.size());
        for (Position position : positions) {
            BigDecimal averageCost = position.getAveragePrice();
            BigDecimal positionCost = value(averageCost, position.getQuantity());
            investedCost = investedCost.add(positionCost);
            MarketQuote quote = quoteByInstrument.get(position.getInstrument().getId());
            String valuationStatus;
            BigDecimal currentPrice = null;
            BigDecimal marketValue = null;
            BigDecimal positionUnrealized = null;
            if (quote == null) {
                valuationStatus = "UNAVAILABLE";
                fullyValued = false;
                hasUnavailable = true;
            } else {
                MarketQuoteResponse quoteStatus = MarketQuoteResponse.from(quote, now);
                valuationStatus = quoteStatus.dataStatus();
                if ("LIVE".equals(valuationStatus) && quote.getLastPrice() != null && quote.getLastPrice().signum() > 0) {
                    currentPrice = quote.getLastPrice();
                    marketValue = value(currentPrice, position.getQuantity());
                    positionUnrealized = currentPrice.subtract(averageCost)
                            .multiply(BigDecimal.valueOf(position.getQuantity())).setScale(4, RoundingMode.HALF_UP);
                    currentMarketValue = currentMarketValue.add(marketValue);
                    unrealizedPnl = unrealizedPnl.add(positionUnrealized);
                } else {
                    valuationStatus = "STALE".equals(valuationStatus) ? "STALE" : "UNAVAILABLE";
                    fullyValued = false;
                    hasStale |= "STALE".equals(valuationStatus);
                    hasUnavailable |= "UNAVAILABLE".equals(valuationStatus);
                }
            }
            responsePositions.add(new PortfolioPositionResponse(position.getInstrument().getExchange(),
                    position.getInstrument().getSymbol(), position.getTradingMode(), position.getQuantity(),
                    position.getReservedQuantity(), position.getQuantity() - position.getReservedQuantity(),
                    averageCost, currentPrice, marketValue, positionUnrealized, position.getRealizedPnl(), valuationStatus));
        }

        BigDecimal totalPnl = fullyValued ? realizedPnl.add(unrealizedPnl) : null;
        BigDecimal summaryMarketValue = fullyValued ? currentMarketValue : null;
        BigDecimal summaryUnrealized = fullyValued ? unrealizedPnl : null;
        String summaryStatus = positions.isEmpty() ? "EMPTY"
                : fullyValued ? "LIVE" : hasUnavailable ? "UNAVAILABLE" : hasStale ? "STALE" : "UNAVAILABLE";
        return new PortfolioResponse(account.getId(), account.getCurrency(), account.getAvailableBalance(),
                account.getReservedBalance(), investedCost, summaryMarketValue, realizedPnl,
                summaryUnrealized, totalPnl, responsePositions.size(), summaryStatus, now, List.copyOf(responsePositions));
    }

    private static BigDecimal value(BigDecimal unitPrice, long quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(4, RoundingMode.HALF_UP);
    }
}
