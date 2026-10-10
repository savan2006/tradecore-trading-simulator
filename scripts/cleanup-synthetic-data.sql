-- Review the preview counts before deciding whether to commit the deletion.
-- This script targets only users whose email ends with @example.invalid.
-- It deliberately never deletes shared instruments, quotes, candles, or market sessions.

BEGIN;

CREATE TEMP TABLE cleanup_user_ids ON COMMIT DROP AS
SELECT id
FROM app_user
WHERE lower(email) LIKE '%@example.invalid';

CREATE TEMP TABLE cleanup_account_ids ON COMMIT DROP AS
SELECT id
FROM trading_account
WHERE user_id IN (SELECT id FROM cleanup_user_ids);

SELECT 'app_user' AS table_name, count(*) AS rows_to_remove FROM app_user WHERE id IN (SELECT id FROM cleanup_user_ids)
UNION ALL SELECT 'trading_account', count(*) FROM trading_account WHERE id IN (SELECT id FROM cleanup_account_ids)
UNION ALL SELECT 'trading_order', count(*) FROM trading_order WHERE account_id IN (SELECT id FROM cleanup_account_ids)
UNION ALL SELECT 'order_event', count(*) FROM order_event e JOIN trading_order o ON o.id = e.order_id WHERE o.account_id IN (SELECT id FROM cleanup_account_ids)
UNION ALL SELECT 'execution', count(*) FROM execution WHERE account_id IN (SELECT id FROM cleanup_account_ids)
UNION ALL SELECT 'ledger_entry', count(*) FROM ledger_entry WHERE account_id IN (SELECT id FROM cleanup_account_ids)
UNION ALL SELECT 'position', count(*) FROM position WHERE account_id IN (SELECT id FROM cleanup_account_ids)
UNION ALL SELECT 'risk_limit', count(*) FROM risk_limit WHERE account_id IN (SELECT id FROM cleanup_account_ids)
UNION ALL SELECT 'trade_journal', count(*) FROM trade_journal WHERE user_id IN (SELECT id FROM cleanup_user_ids)
UNION ALL SELECT 'notification', count(*) FROM notification WHERE user_id IN (SELECT id FROM cleanup_user_ids)
UNION ALL SELECT 'price_alert', count(*) FROM price_alert WHERE user_id IN (SELECT id FROM cleanup_user_ids)
UNION ALL SELECT 'watchlist', count(*) FROM watchlist WHERE user_id IN (SELECT id FROM cleanup_user_ids)
UNION ALL SELECT 'watchlist_item', count(*) FROM watchlist_item wi JOIN watchlist w ON w.id = wi.watchlist_id WHERE w.user_id IN (SELECT id FROM cleanup_user_ids)
UNION ALL SELECT 'audit_log', count(*) FROM audit_log WHERE actor_user_id IN (SELECT id FROM cleanup_user_ids)
UNION ALL SELECT 'idempotency_record', count(*) FROM idempotency_record WHERE account_id IN (SELECT id FROM cleanup_account_ids);

DELETE FROM order_event
WHERE order_id IN (SELECT id FROM trading_order WHERE account_id IN (SELECT id FROM cleanup_account_ids));

DELETE FROM trade_journal WHERE user_id IN (SELECT id FROM cleanup_user_ids);
DELETE FROM ledger_entry WHERE account_id IN (SELECT id FROM cleanup_account_ids);
DELETE FROM idempotency_record WHERE account_id IN (SELECT id FROM cleanup_account_ids);
DELETE FROM execution WHERE account_id IN (SELECT id FROM cleanup_account_ids);
DELETE FROM trading_order WHERE account_id IN (SELECT id FROM cleanup_account_ids);
DELETE FROM position WHERE account_id IN (SELECT id FROM cleanup_account_ids);
DELETE FROM risk_limit WHERE account_id IN (SELECT id FROM cleanup_account_ids);
DELETE FROM notification WHERE user_id IN (SELECT id FROM cleanup_user_ids);
DELETE FROM price_alert WHERE user_id IN (SELECT id FROM cleanup_user_ids);
DELETE FROM watchlist_item
WHERE watchlist_id IN (SELECT id FROM watchlist WHERE user_id IN (SELECT id FROM cleanup_user_ids));
DELETE FROM watchlist WHERE user_id IN (SELECT id FROM cleanup_user_ids);
DELETE FROM audit_log WHERE actor_user_id IN (SELECT id FROM cleanup_user_ids);
DELETE FROM trading_account WHERE id IN (SELECT id FROM cleanup_account_ids);
DELETE FROM app_user WHERE id IN (SELECT id FROM cleanup_user_ids);

ROLLBACK;
-- COMMIT;
