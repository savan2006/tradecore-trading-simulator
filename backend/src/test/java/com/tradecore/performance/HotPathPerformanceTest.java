package com.tradecore.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradecore.learning.CompanyComparisonService;
import com.tradecore.market.MarketScreenerService;
import com.tradecore.portfolio.PortfolioQueryService;
import com.tradecore.strategylab.BacktestRequest;
import com.tradecore.strategylab.BacktestStrategy;
import com.tradecore.strategylab.StrategyBacktestService;
import com.tradecore.watchlist.WatchlistService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-perf-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.generate_statistics=true", "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379", "tradecore.security.user=perf-test",
        "tradecore.security.password=perf-test-password", "tradecore.execution.scheduling.enabled=false",
        "tradecore.market-data.scheduling.quote-enabled=false", "tradecore.market-data.scheduling.candle-enabled=false"
})
class HotPathPerformanceTest {
    private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = Instant.now();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID WATCHLIST_ID = UUID.randomUUID();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @Autowired private MarketScreenerService screener;
    @Autowired private PortfolioQueryService portfolio;
    @Autowired private WatchlistService watchlists;
    @Autowired private CompanyComparisonService comparison;
    @Autowired private StrategyBacktestService backtests;

    private final List<PerfInstrument> instruments = new ArrayList<>();

    @Test
    void sqlCountsStayFlatAsTheSeedGrowsAndBoundedAnalyticsStayResponsive() {
        seedAccount();
        seedInstruments(0, 10);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        long screenerTen = statements(statistics, () -> screener.screen("PERF", null, "DEFAULT", 80));
        long portfolioTen = statements(statistics, () -> portfolio.currentPortfolio("hot-path-perf@example.invalid"));
        long watchlistTen = statements(statistics, () -> watchlists.list("hot-path-perf@example.invalid"));

        seedInstruments(10, 80);
        long screenerEighty = statements(statistics, () -> screener.screen("PERF", null, "DEFAULT", 80));
        long portfolioEighty = statements(statistics, () -> portfolio.currentPortfolio("hot-path-perf@example.invalid"));
        long watchlistEighty = statements(statistics, () -> watchlists.list("hot-path-perf@example.invalid"));

        assertThat(screenerEighty).isEqualTo(screenerTen);
        assertThat(portfolioEighty).isEqualTo(portfolioTen);
        assertThat(watchlistEighty).isEqualTo(watchlistTen);

        LocalDate yesterday = LocalDate.now(NSE_ZONE).minusDays(1);
        LocalDate yearAgo = yesterday.minusDays(364);
        long screenerNanos = timed(() -> screener.screen("PERF", null, "DEFAULT", 80));
        long comparisonNanos = timed(() -> comparison.compare(
                "PERF000,PERF001,PERF002,PERF003", yearAgo, yesterday));
        long backtestNanos = timed(() -> backtests.run(new BacktestRequest("PERF000",
                yearAgo, yesterday, BacktestStrategy.SIMPLE_MOVING_AVERAGE_CROSSOVER,
                new BigDecimal("100000"), 5, 20, null, null, null)));

        assertThat(screenerNanos).isLessThan(java.time.Duration.ofSeconds(10).toNanos());
        assertThat(comparisonNanos).isLessThan(java.time.Duration.ofSeconds(10).toNanos());
        assertThat(backtestNanos).isLessThan(java.time.Duration.ofSeconds(10).toNanos());
    }

    private void seedAccount() {
        jdbc.update("insert into app_user (id,email,password_hash,display_name,role,status,created_at,updated_at) values (?,?,?,?,?,?,?,?)",
                USER_ID, "hot-path-perf@example.invalid", "not-used", "Performance Fixture", "USER", "ACTIVE", NOW, NOW);
        jdbc.update("insert into trading_account (id,user_id,status,currency,available_balance,reserved_balance,created_at,updated_at) values (?,?,?,?,?,?,?,?)",
                ACCOUNT_ID, USER_ID, "ACTIVE", "INR", new BigDecimal("1000000"), BigDecimal.ZERO, NOW, NOW);
        jdbc.update("insert into watchlist (id,user_id,name,created_at,updated_at) values (?,?,?,?,?)",
                WATCHLIST_ID, USER_ID, "Performance fixture", NOW, NOW);
    }

    private void seedInstruments(int start, int end) {
        for (int index = start; index < end; index++) {
            String symbol = "PERF%03d".formatted(index);
            UUID instrumentId = UUID.nameUUIDFromBytes(symbol.getBytes(StandardCharsets.UTF_8));
            jdbc.update("insert into instrument (id,symbol,company_name,exchange,instrument_type,currency,tradable,provider_instrument_key,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?)",
                    instrumentId, symbol, "Performance Company " + index, "NSE", "EQUITY", "INR", true,
                    "PERF:" + symbol, NOW, NOW);
            jdbc.update("insert into learning_profile (id,instrument_id,sector,business_type,business_description,major_business_factors,common_price_drivers,important_risks,educational_observations,updated_at) values (?,?,?,?,?,?,?,?,?,?)",
                    UUID.nameUUIDFromBytes((symbol + ":profile").getBytes(StandardCharsets.UTF_8)), instrumentId,
                    "Technology", "Software", "Fixture description", "Fixture factors", "Fixture drivers",
                    "Fixture risks", "Educational fixture", NOW);
            jdbc.update("insert into market_quote (id,instrument_id,last_price,previous_close,open_price,high_price,low_price,volume,market_at,provider_updated_at,received_at,market_status,data_status) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.nameUUIDFromBytes((symbol + ":quote").getBytes(StandardCharsets.UTF_8)), instrumentId,
                    new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("98"), new BigDecimal("101"),
                    new BigDecimal("97"), 1000L, NOW, NOW, NOW, "CLOSED", "LIVE");
            jdbc.update("insert into position (id,account_id,instrument_id,trading_mode,quantity,reserved_quantity,average_price,realized_pnl,updated_at) values (?,?,?,?,?,?,?,?,?)",
                    UUID.nameUUIDFromBytes((symbol + ":position").getBytes(StandardCharsets.UTF_8)), ACCOUNT_ID,
                    instrumentId, "DELIVERY", 1L, 0L, new BigDecimal("100"), BigDecimal.ZERO, NOW);
            jdbc.update("insert into watchlist_item (id,watchlist_id,instrument_id,sort_order,created_at) values (?,?,?,?,?)",
                    UUID.nameUUIDFromBytes((symbol + ":watchlist").getBytes(StandardCharsets.UTF_8)), WATCHLIST_ID,
                    instrumentId, index, NOW);
            instruments.add(new PerfInstrument(symbol, instrumentId));
        }

        List<Object[]> rows = new ArrayList<>(Math.max(0, end - start) * 250);
        LocalDate lastDay = LocalDate.now(NSE_ZONE).minusDays(1);
        for (int index = start; index < end; index++) {
            PerfInstrument instrument = instruments.get(index);
            for (int day = 0; day < 250; day++) {
                Instant bucket = lastDay.minusDays(249L - day).atStartOfDay(NSE_ZONE).toInstant();
                BigDecimal close = BigDecimal.valueOf(100L + day % 20);
                rows.add(new Object[] { UUID.randomUUID(), instrument.id(), "1D", Timestamp.from(bucket), close,
                        close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close, 1000L, Timestamp.from(NOW) });
            }
        }
        jdbc.batchUpdate("insert into market_candle (id,instrument_id,resolution,bucket_start,open_price,high_price,low_price,close_price,volume,created_at) values (?,?,?,?,?,?,?,?,?,?)",
                rows);
    }

    private static long statements(Statistics statistics, Runnable action) {
        statistics.clear();
        action.run();
        return statistics.getPrepareStatementCount();
    }

    private static long timed(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return System.nanoTime() - start;
    }

    private record PerfInstrument(String symbol, UUID id) { }
}
