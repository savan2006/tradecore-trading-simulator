package com.tradecore.strategylab;

import com.tradecore.market.Instrument;
import com.tradecore.market.InstrumentRepository;
import com.tradecore.market.MarketCandle;
import com.tradecore.market.MarketCandleRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Stateless educational simulation over persisted daily candles. */
@Service
@Transactional(readOnly = true)
public class StrategyBacktestService {
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String DAILY_RESOLUTION = "1D";
    private static final int MAX_RANGE_DAYS = 730;
    private static final int WARMUP_DAYS = 400;
    private static final int MAX_CANDLES = 1_600;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final InstrumentRepository instruments;
    private final MarketCandleRepository candles;

    public StrategyBacktestService(InstrumentRepository instruments, MarketCandleRepository candles) {
        this.instruments = instruments;
        this.candles = candles;
    }

    public BacktestResponse run(BacktestRequest request) {
        if (request == null) throw badRequest("Request body is required");
        String symbol = request.symbol() == null ? "" : request.symbol().trim().toUpperCase();
        if (symbol.isBlank() || symbol.length() > 32) throw badRequest("symbol must contain 1 to 32 characters");
        if (request.fromDate() == null || request.toDate() == null) throw badRequest("fromDate and toDate are required");
        if (request.toDate().isBefore(request.fromDate())) throw badRequest("toDate must be on or after fromDate");
        if (request.toDate().isAfter(LocalDate.now(EXCHANGE_ZONE))) throw badRequest("toDate cannot be in the future");
        long rangeDays = request.toDate().toEpochDay() - request.fromDate().toEpochDay() + 1;
        if (rangeDays > MAX_RANGE_DAYS) throw badRequest("date range cannot exceed " + MAX_RANGE_DAYS + " days");
        if (request.strategy() == null) throw badRequest("strategy is required");
        if (request.startingCapital() == null || request.startingCapital().signum() <= 0
                || request.startingCapital().precision() > 19 || request.startingCapital().scale() > 6) {
            throw badRequest("startingCapital must be positive and have at most 6 decimal places");
        }

        Parameters parameters = validateParameters(request);
        Instrument instrument = instruments.findByExchangeAndSymbol("NSE", symbol)
                .filter(Instrument::isTradable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unsupported NSE instrument: " + symbol));
        LocalDate warmupFrom;
        Instant from;
        Instant to;
        try {
            warmupFrom = request.fromDate().minusDays(WARMUP_DAYS);
            from = warmupFrom.atStartOfDay(EXCHANGE_ZONE).toInstant();
            to = request.toDate().plusDays(1).atStartOfDay(EXCHANGE_ZONE).toInstant();
        } catch (RuntimeException exception) {
            throw badRequest("Date range is outside the supported range");
        }
        List<MarketCandle> rows = candles
                .findAllByInstrument_IdAndResolutionAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                        instrument.getId(), DAILY_RESOLUTION, from, to, PageRequest.of(0, MAX_CANDLES));
        List<Bar> bars = rows.stream().map(row -> new Bar(
                row.getBucketStart().atZone(EXCHANGE_ZONE).toLocalDate(),
                row.getOpenPrice(), row.getHighPrice(), row.getLowPrice(), row.getClosePrice())).toList();
        if (bars.stream().anyMatch(bar -> bar.open() == null || bar.close() == null
                || bar.open().signum() <= 0 || bar.close().signum() <= 0)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Persisted candles contain an invalid open or close price");
        }
        if (bars.stream().noneMatch(bar -> inPeriod(bar.date(), request))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No persisted daily candles are available in the requested period");
        }
        int required = request.strategy() == BacktestStrategy.SIMPLE_MOVING_AVERAGE_CROSSOVER
                ? parameters.slowPeriod() + 1 : parameters.rsiPeriod() + 2;
        if (bars.size() < required) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient persisted candle history; this strategy requires at least " + required + " daily candles");
        }
        if (bars.size() == MAX_CANDLES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Historical range exceeds the supported candle limit");
        }
        return simulate(symbol, request, parameters, bars);
    }

    private static Parameters validateParameters(BacktestRequest request) {
        if (request.strategy() == BacktestStrategy.SIMPLE_MOVING_AVERAGE_CROSSOVER) {
            int fast = requiredPeriod(request.fastPeriod(), "fastPeriod", 2, 50);
            int slow = requiredPeriod(request.slowPeriod(), "slowPeriod", 3, 100);
            if (fast >= slow) throw badRequest("fastPeriod must be less than slowPeriod");
            if (request.rsiPeriod() != null || request.oversoldThreshold() != null || request.overboughtThreshold() != null) {
                throw badRequest("RSI parameters are not valid for the SMA strategy");
            }
            return new Parameters(fast, slow, 0, null, null);
        }
        int period = requiredPeriod(request.rsiPeriod(), "rsiPeriod", 2, 100);
        BigDecimal oversold = request.oversoldThreshold();
        BigDecimal overbought = request.overboughtThreshold();
        if (oversold == null || overbought == null || oversold.compareTo(ZERO) <= 0
                || overbought.compareTo(ONE_HUNDRED) >= 0 || oversold.compareTo(overbought) >= 0) {
            throw badRequest("RSI thresholds must satisfy 0 < oversoldThreshold < overboughtThreshold < 100");
        }
        if (oversold.scale() > 4 || overbought.scale() > 4) throw badRequest("RSI thresholds may have at most 4 decimal places");
        if (request.fastPeriod() != null || request.slowPeriod() != null) {
            throw badRequest("SMA parameters are not valid for the RSI strategy");
        }
        return new Parameters(0, 0, period, oversold, overbought);
    }

    private static int requiredPeriod(Integer period, String name, int min, int max) {
        if (period == null || period < min || period > max) throw badRequest(name + " must be between " + min + " and " + max);
        return period;
    }

    private static BacktestResponse simulate(String symbol, BacktestRequest request, Parameters parameters, List<Bar> bars) {
        BigDecimal cash = request.startingCapital();
        Holding holding = null;
        boolean pendingBuy = false;
        boolean pendingSell = false;
        List<BacktestResponse.EquityPoint> curve = new ArrayList<>();
        List<BacktestResponse.SimulatedTrade> trades = new ArrayList<>();
        BigDecimal realized = ZERO;
        BigDecimal peak = request.startingCapital();
        BigDecimal maxDrawdown = ZERO;

        for (int i = 0; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            boolean inPeriod = inPeriod(bar.date(), request);
            if (inPeriod && pendingBuy && holding == null) {
                long quantity = quantityFor(cash, bar.open());
                if (quantity > 0) {
                    BigDecimal invested = bar.open().multiply(BigDecimal.valueOf(quantity));
                    cash = cash.subtract(invested);
                    holding = new Holding(bar.date(), bar.open(), quantity, invested);
                }
                pendingBuy = false;
            } else if (inPeriod && pendingSell && holding != null) {
                BigDecimal proceeds = bar.open().multiply(BigDecimal.valueOf(holding.quantity()));
                BigDecimal result = proceeds.subtract(holding.invested());
                cash = cash.add(proceeds);
                realized = realized.add(result);
                trades.add(new BacktestResponse.SimulatedTrade(holding.entryDate(), holding.entryPrice(),
                        bar.date(), bar.open(), holding.quantity(), holding.invested(), result, "CLOSED"));
                holding = null;
                pendingSell = false;
            }

            BigDecimal equity = cash.add(holding == null ? ZERO
                    : bar.close().multiply(BigDecimal.valueOf(holding.quantity())));
            if (inPeriod) {
                curve.add(new BacktestResponse.EquityPoint(bar.date(), equity));
                if (equity.compareTo(peak) > 0) peak = equity;
                if (peak.signum() > 0) {
                    BigDecimal drawdown = peak.subtract(equity).multiply(ONE_HUNDRED)
                            .divide(peak, 8, RoundingMode.HALF_UP);
                    if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;
                }
            }

            if (inPeriod && i + 1 < bars.size() && inPeriod(bars.get(i + 1).date(), request)) {
                Signal signal = signalAt(i, bars, request.strategy(), parameters);
                if (holding == null && signal == Signal.BUY) pendingBuy = true;
                else if (holding != null && signal == Signal.SELL) pendingSell = true;
            }
        }

        BigDecimal ending = curve.get(curve.size() - 1).equity();
        if (holding != null) {
            trades.add(new BacktestResponse.SimulatedTrade(holding.entryDate(), holding.entryPrice(),
                    null, null, holding.quantity(), holding.invested(), null, "OPEN"));
        }
        int wins = (int) trades.stream().filter(trade -> trade.realizedResult() != null && trade.realizedResult().signum() > 0).count();
        int losses = (int) trades.stream().filter(trade -> trade.realizedResult() != null && trade.realizedResult().signum() < 0).count();
        List<BigDecimal> winningResults = trades.stream().map(BacktestResponse.SimulatedTrade::realizedResult)
                .filter(value -> value != null && value.signum() > 0).toList();
        List<BigDecimal> losingResults = trades.stream().map(BacktestResponse.SimulatedTrade::realizedResult)
                .filter(value -> value != null && value.signum() < 0).toList();
        int closedCount = wins + losses + (int) trades.stream()
                .filter(trade -> trade.realizedResult() != null && trade.realizedResult().signum() == 0).count();
        BigDecimal winRate = percent(BigDecimal.valueOf(wins), BigDecimal.valueOf(closedCount));
        List<Bar> periodBars = bars.stream().filter(bar -> inPeriod(bar.date(), request)).toList();
        BigDecimal benchmark = buyAndHoldReturn(request.startingCapital(), periodBars.get(0).open(), periodBars.get(periodBars.size() - 1).close());

        return new BacktestResponse(request.strategy(), symbol, request.fromDate(), request.toDate(), request.startingCapital(),
                ending, percent(ending.subtract(request.startingCapital()), request.startingCapital()), realized,
                closedCount, wins, losses, winRate, average(winningResults), average(losingResults), maxDrawdown,
                benchmark, List.copyOf(curve), List.copyOf(trades),
                "Signals use completed daily closes; simulated entries and exits execute at the next in-range candle open.",
                "Gross prices only; no fees, taxes, brokerage, or slippage are included.");
    }

    private static Signal signalAt(int index, List<Bar> bars, BacktestStrategy strategy, Parameters parameters) {
        if (strategy == BacktestStrategy.SIMPLE_MOVING_AVERAGE_CROSSOVER) {
            BigDecimal fast = sma(index, bars, parameters.fastPeriod());
            BigDecimal slow = sma(index, bars, parameters.slowPeriod());
            BigDecimal previousFast = sma(index - 1, bars, parameters.fastPeriod());
            BigDecimal previousSlow = sma(index - 1, bars, parameters.slowPeriod());
            if (fast == null || slow == null || previousFast == null || previousSlow == null) return Signal.NONE;
            if (previousFast.compareTo(previousSlow) <= 0 && fast.compareTo(slow) > 0) return Signal.BUY;
            if (previousFast.compareTo(previousSlow) >= 0 && fast.compareTo(slow) < 0) return Signal.SELL;
            return Signal.NONE;
        }
        BigDecimal current = rsi(index, bars, parameters.rsiPeriod());
        BigDecimal previous = rsi(index - 1, bars, parameters.rsiPeriod());
        if (current == null || previous == null) return Signal.NONE;
        if (previous.compareTo(parameters.oversold()) < 0 && current.compareTo(parameters.oversold()) >= 0) return Signal.BUY;
        if (previous.compareTo(parameters.overbought()) < 0 && current.compareTo(parameters.overbought()) >= 0) return Signal.SELL;
        return Signal.NONE;
    }

    private static BigDecimal sma(int end, List<Bar> bars, int period) {
        if (end < period - 1) return null;
        BigDecimal sum = ZERO;
        for (int i = end - period + 1; i <= end; i++) sum = sum.add(bars.get(i).close());
        return sum.divide(BigDecimal.valueOf(period), 12, RoundingMode.HALF_UP);
    }

    private static BigDecimal rsi(int end, List<Bar> bars, int period) {
        if (end < period) return null;
        BigDecimal gains = ZERO;
        BigDecimal losses = ZERO;
        for (int i = end - period + 1; i <= end; i++) {
            BigDecimal change = bars.get(i).close().subtract(bars.get(i - 1).close());
            if (change.signum() > 0) gains = gains.add(change);
            else losses = losses.add(change.abs());
        }
        if (gains.signum() == 0 && losses.signum() == 0) return new BigDecimal("50");
        if (losses.signum() == 0) return ONE_HUNDRED;
        BigDecimal relativeStrength = gains.divide(losses, 12, RoundingMode.HALF_UP);
        return ONE_HUNDRED.subtract(ONE_HUNDRED.divide(BigDecimal.ONE.add(relativeStrength), 12, RoundingMode.HALF_UP));
    }

    private static BigDecimal buyAndHoldReturn(BigDecimal capital, BigDecimal entry, BigDecimal lastClose) {
        long quantity = quantityFor(capital, entry);
        BigDecimal invested = entry.multiply(BigDecimal.valueOf(quantity));
        BigDecimal ending = capital.subtract(invested).add(lastClose.multiply(BigDecimal.valueOf(quantity)));
        return percent(ending.subtract(capital), capital);
    }

    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) return ZERO.setScale(4);
        return numerator.multiply(ONE_HUNDRED).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static long quantityFor(BigDecimal capital, BigDecimal price) {
        try {
            return capital.divide(price, 0, RoundingMode.DOWN).longValueExact();
        } catch (ArithmeticException exception) {
            throw badRequest("Starting capital is too large for whole-share simulation");
        }
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        return values.stream().reduce(ZERO, BigDecimal::add).divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private static boolean inPeriod(LocalDate date, BacktestRequest request) {
        return !date.isBefore(request.fromDate()) && !date.isAfter(request.toDate());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private enum Signal { NONE, BUY, SELL }
    private record Bar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}
    private record Holding(LocalDate entryDate, BigDecimal entryPrice, long quantity, BigDecimal invested) {}
    private record Parameters(int fastPeriod, int slowPeriod, int rsiPeriod, BigDecimal oversold, BigDecimal overbought) {}
}
