package com.tradecore.performance;

import com.tradecore.account.TradingAccount;
import com.tradecore.account.TradingAccountRepository;
import com.tradecore.execution.ExecutionRepository;
import com.tradecore.order.TradingOrderRepository;
import com.tradecore.portfolio.PortfolioQueryService;
import com.tradecore.portfolio.PortfolioResponse;
import com.tradecore.portfolio.Position;
import com.tradecore.portfolio.PositionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PerformanceQueryService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final TradingAccountRepository accounts;
    private final TradingOrderRepository orders;
    private final ExecutionRepository executions;
    private final PositionRepository positions;
    private final PortfolioQueryService portfolio;

    public PerformanceQueryService(TradingAccountRepository accounts, TradingOrderRepository orders,
            ExecutionRepository executions, PositionRepository positions, PortfolioQueryService portfolio) {
        this.accounts = accounts;
        this.orders = orders;
        this.executions = executions;
        this.positions = positions;
        this.portfolio = portfolio;
    }

    @Transactional(readOnly = true)
    public PerformanceResponse forUser(String authenticatedEmail) {
        String email = authenticatedEmail.trim().toLowerCase(Locale.ROOT);
        TradingAccount account = accounts.findByUser_Email(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trading account not found"));
        PortfolioResponse current = portfolio.currentPortfolio(email);
        List<Position> recentClosed = positions.findTop30ByAccount_IdAndQuantityOrderByUpdatedAtDesc(account.getId(), 0)
                .stream().sorted(java.util.Comparator.comparing(Position::getUpdatedAt)).toList();
        var best = positions.findFirstByAccount_IdAndQuantityOrderByRealizedPnlDesc(
                account.getId(), 0).map(PerformanceQueryService::closedPosition).orElse(null);
        var worst = positions.findFirstByAccount_IdAndQuantityOrderByRealizedPnlAsc(
                account.getId(), 0).map(PerformanceQueryService::closedPosition).orElse(null);
        List<PerformancePoint> series = recentClosed.stream().map(position -> new PerformancePoint(
                position.getInstrument().getSymbol(), position.getTradingMode(), position.getUpdatedAt(),
                position.getRealizedPnl())).toList();

        return new PerformanceResponse(
                orders.countByAccount_Id(account.getId()),
                orders.countByAccount_IdAndStatus(account.getId(), "FILLED"),
                orders.countByAccount_IdAndStatus(account.getId(), "CANCELLED"),
                executions.countByAccount_Id(account.getId()),
                current.positionCount(), current.realizedPnl(), current.unrealizedPnl(), current.totalPnl(),
                current.currentMarketValue(), current.valuationStatus(),
                orders.countByAccount_IdAndSide(account.getId(), "BUY"),
                orders.countByAccount_IdAndSide(account.getId(), "SELL"),
                orders.countByAccount_IdAndTradingMode(account.getId(), "DELIVERY"),
                orders.countByAccount_IdAndTradingMode(account.getId(), "INTRADAY"),
                positions.countByAccount_IdAndQuantityAndRealizedPnlGreaterThan(account.getId(), 0, ZERO),
                positions.countByAccount_IdAndQuantityAndRealizedPnlLessThan(account.getId(), 0, ZERO),
                best, worst, series);
    }

    private static ClosedPositionPerformance closedPosition(Position position) {
        return new ClosedPositionPerformance(position.getInstrument().getSymbol(), position.getTradingMode(),
                position.getRealizedPnl());
    }
}
