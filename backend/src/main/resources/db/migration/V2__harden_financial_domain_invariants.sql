-- Signed ledger convention: credits are positive; debits and fees are negative.
-- Reversals and adjustments retain the sign needed to offset/correct the original movement.

ALTER TABLE trading_order
    ADD CONSTRAINT uq_trading_order_account_id UNIQUE (account_id, id);

ALTER TABLE trading_order
    ADD CONSTRAINT uq_trading_order_execution_key UNIQUE (id, instrument_id, side, trading_mode);

ALTER TABLE execution
    ADD COLUMN account_id UUID;

UPDATE execution e
SET account_id = (SELECT o.account_id FROM trading_order o WHERE o.id = e.order_id);

ALTER TABLE execution
    ALTER COLUMN account_id SET NOT NULL;

ALTER TABLE execution
    ADD CONSTRAINT fk_execution_account
        FOREIGN KEY (account_id) REFERENCES trading_account (id);

ALTER TABLE execution
    ADD CONSTRAINT uq_execution_account_id UNIQUE (account_id, id);

ALTER TABLE execution
    ADD CONSTRAINT fk_execution_order_account
        FOREIGN KEY (account_id, order_id)
        REFERENCES trading_order (account_id, id);

ALTER TABLE execution
    ADD CONSTRAINT fk_execution_order_consistency
        FOREIGN KEY (order_id, instrument_id, side, trading_mode)
        REFERENCES trading_order (id, instrument_id, side, trading_mode);

ALTER TABLE ledger_entry
    ADD CONSTRAINT fk_ledger_order_account
        FOREIGN KEY (account_id, order_id)
        REFERENCES trading_order (account_id, id);

ALTER TABLE ledger_entry
    ADD CONSTRAINT fk_ledger_execution_account
        FOREIGN KEY (account_id, execution_id)
        REFERENCES execution (account_id, id);

ALTER TABLE idempotency_record
    ADD CONSTRAINT fk_idempotency_order_account
        FOREIGN KEY (account_id, original_order_id)
        REFERENCES trading_order (account_id, id);

ALTER TABLE ledger_entry
    DROP CONSTRAINT ck_ledger_reference;

ALTER TABLE ledger_entry
    ADD CONSTRAINT ck_ledger_amount_sign CHECK (
        (entry_type IN ('INITIAL_DEPOSIT', 'TRADE_CREDIT') AND amount > 0)
        OR (entry_type IN ('TRADE_DEBIT', 'FEE') AND amount < 0)
        OR entry_type IN ('REVERSAL', 'ADJUSTMENT')
    );

ALTER TABLE ledger_entry
    ADD CONSTRAINT ck_ledger_reference CHECK (
        (entry_type = 'INITIAL_DEPOSIT' AND order_id IS NULL AND execution_id IS NULL)
        OR (entry_type IN ('TRADE_DEBIT', 'TRADE_CREDIT', 'FEE')
            AND order_id IS NULL AND execution_id IS NOT NULL)
        OR (entry_type = 'REVERSAL'
            AND ((order_id IS NOT NULL AND execution_id IS NULL)
                 OR (order_id IS NULL AND execution_id IS NOT NULL)))
        OR (entry_type = 'ADJUSTMENT'
            AND NOT (order_id IS NOT NULL AND execution_id IS NOT NULL))
    );

-- The unique constraint already indexes these columns in the same key order.
-- PostgreSQL can scan its B-tree in reverse for newest-first candle history.
DROP INDEX ix_market_candle_history;
