-- NSE 2026 EQUITIES holidays. Explicit session times, when later configured,
-- take precedence over the holiday marker (for example a special session).
ALTER TABLE market_session
    DROP CONSTRAINT ck_market_session_holiday_no_override;

INSERT INTO market_session (id, trading_date, session_state, opens_at, closes_at,
                            square_off_at, holiday, description, active)
SELECT seed.id, seed.trading_date, 'HOLIDAY', NULL, NULL, NULL, TRUE, seed.description, TRUE
FROM (VALUES
    ('23000000-0000-4000-8000-000000000001', DATE '2026-01-15', 'Municipal Corporation Election - Maharashtra'),
    ('23000000-0000-4000-8000-000000000002', DATE '2026-01-26', 'Republic Day'),
    ('23000000-0000-4000-8000-000000000003', DATE '2026-03-03', 'Holi'),
    ('23000000-0000-4000-8000-000000000004', DATE '2026-03-26', 'Shri Ram Navami'),
    ('23000000-0000-4000-8000-000000000005', DATE '2026-03-31', 'Shri Mahavir Jayanti'),
    ('23000000-0000-4000-8000-000000000006', DATE '2026-04-03', 'Good Friday'),
    ('23000000-0000-4000-8000-000000000007', DATE '2026-04-14', 'Dr. Baba Saheb Ambedkar Jayanti'),
    ('23000000-0000-4000-8000-000000000008', DATE '2026-05-01', 'Maharashtra Day'),
    ('23000000-0000-4000-8000-000000000009', DATE '2026-05-28', 'Bakri Id'),
    ('23000000-0000-4000-8000-000000000010', DATE '2026-06-26', 'Muharram'),
    ('23000000-0000-4000-8000-000000000011', DATE '2026-09-14', 'Ganesh Chaturthi'),
    ('23000000-0000-4000-8000-000000000012', DATE '2026-10-02', 'Mahatma Gandhi Jayanti'),
    ('23000000-0000-4000-8000-000000000013', DATE '2026-10-20', 'Dussehra'),
    ('23000000-0000-4000-8000-000000000014', DATE '2026-11-10', 'Diwali-Balipratipada'),
    ('23000000-0000-4000-8000-000000000015', DATE '2026-11-24', 'Prakash Gurpurb Sri Guru Nanak Dev'),
    ('23000000-0000-4000-8000-000000000016', DATE '2026-12-25', 'Christmas')
) AS seed(id, trading_date, description)
WHERE NOT EXISTS (
    SELECT 1 FROM market_session existing WHERE existing.trading_date = seed.trading_date
);
