package com.tradecore.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only aggregate checks for financial rows persisted in PostgreSQL. */
@Service
public class ReconciliationService {
    private static final int SAMPLE_LIMIT = 20;
    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Check> reconcile() {
        return List.of(
                check("account_cash_matches_ledger", """
                        SELECT a.id AS id
                        FROM trading_account a
                        LEFT JOIN ledger_entry l ON l.account_id = a.id
                        GROUP BY a.id, a.available_balance, a.reserved_balance
                        HAVING a.available_balance + a.reserved_balance <> COALESCE(SUM(l.amount), 0)
                        """),
                check("account_reserved_matches_pending_buy_orders", """
                        SELECT a.id AS id
                        FROM trading_account a
                        LEFT JOIN trading_order o
                          ON o.account_id = a.id AND o.status = 'PENDING' AND o.side = 'BUY'
                        GROUP BY a.id, a.reserved_balance
                        HAVING a.reserved_balance <> COALESCE(SUM(o.reserved_amount), 0)
                        """),
                check("position_reserved_matches_pending_sell_orders", """
                        SELECT p.id AS id
                        FROM position p
                        LEFT JOIN trading_order o
                          ON o.account_id = p.account_id AND o.instrument_id = p.instrument_id
                         AND o.trading_mode = p.trading_mode AND o.status = 'PENDING' AND o.side = 'SELL'
                        GROUP BY p.id, p.reserved_quantity
                        HAVING p.reserved_quantity <> COALESCE(SUM(o.remaining_quantity), 0)
                        """),
                check("position_quantity_matches_executions", """
                        SELECT p.id AS id
                        FROM position p
                        WHERE p.quantity <> COALESCE((
                            SELECT SUM(CASE WHEN e.side = 'BUY' THEN e.quantity
                                            WHEN e.side = 'SELL' THEN -e.quantity ELSE 0 END)
                            FROM execution e
                            WHERE e.account_id = p.account_id AND e.instrument_id = p.instrument_id
                              AND e.trading_mode = p.trading_mode
                        ), 0)
                        UNION ALL
                        SELECT e.id AS id
                        FROM execution e
                        WHERE NOT EXISTS (
                            SELECT 1 FROM position p
                            WHERE p.account_id = e.account_id AND p.instrument_id = e.instrument_id
                              AND p.trading_mode = e.trading_mode
                        )
                        """),
                check("filled_orders_match_one_complete_execution", """
                        SELECT o.id AS id
                        FROM trading_order o
                        LEFT JOIN execution e ON e.order_id = o.id
                        WHERE o.status = 'FILLED'
                        GROUP BY o.id, o.requested_quantity
                        HAVING COUNT(e.id) <> 1 OR COALESCE(SUM(e.quantity), 0) <> o.requested_quantity
                        """),
                check("executions_have_one_trade_ledger_entry", """
                        SELECT e.id AS id
                        FROM execution e
                        LEFT JOIN ledger_entry l
                          ON l.execution_id = e.id AND l.entry_type IN ('TRADE_DEBIT', 'TRADE_CREDIT')
                        GROUP BY e.id
                        HAVING COUNT(l.id) <> 1
                        """),
                check("pending_orders_have_reservations", """
                        SELECT o.id AS id
                        FROM trading_order o
                        LEFT JOIN position p
                          ON p.account_id = o.account_id AND p.instrument_id = o.instrument_id
                         AND p.trading_mode = o.trading_mode
                        WHERE o.status = 'PENDING'
                          AND (o.remaining_quantity <= 0 OR
                               (o.side = 'BUY' AND o.reserved_amount <= 0) OR
                               (o.side = 'SELL' AND (p.id IS NULL OR p.reserved_quantity < o.remaining_quantity)) OR
                               o.side NOT IN ('BUY', 'SELL'))
                        """),
                check("financial_balances_and_quantities_are_nonnegative", """
                        SELECT a.id AS id FROM trading_account a
                        WHERE a.available_balance < 0 OR a.reserved_balance < 0
                        UNION ALL
                        SELECT p.id AS id FROM position p
                        WHERE p.quantity < 0 OR p.reserved_quantity < 0 OR p.reserved_quantity > p.quantity
                        UNION ALL
                        SELECT o.id AS id FROM trading_order o
                        WHERE o.requested_quantity <= 0 OR o.executed_quantity < 0 OR o.remaining_quantity < 0
                           OR o.reserved_amount < 0
                        UNION ALL
                        SELECT e.id AS id FROM execution e WHERE e.quantity <= 0
                        """));
    }

    private Check check(String name, String violationsQuery) {
        String query = """
                SELECT CAST(id AS VARCHAR) AS id, COUNT(*) OVER() AS violation_count
                FROM (%s) violations
                ORDER BY id
                FETCH FIRST %d ROWS ONLY
                """.formatted(violationsQuery, SAMPLE_LIMIT);
        List<ViolationSample> samples = jdbc.query(query, (row, index) ->
                new ViolationSample(row.getLong("violation_count"), UUID.fromString(row.getString("id"))));
        long violationCount = samples.isEmpty() ? 0 : samples.getFirst().violationCount();
        return new Check(name, violationCount == 0 ? "PASS" : "FAIL", violationCount,
                samples.stream().map(ViolationSample::id).toList());
    }

    public record Check(String name, String status, long violationCount, List<UUID> sampleIds) { }
    private record ViolationSample(long violationCount, UUID id) { }
}
