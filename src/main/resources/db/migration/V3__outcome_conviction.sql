-- M3 (Phase 4): record conviction on orders and outcomes so the feedback-loop view
-- can group realized win-rates by conviction without the dormant setups/analyses tables.

ALTER TABLE orders ADD COLUMN conviction TEXT;             -- HIGH | MEDIUM | LOW | MANUAL | NULL
ALTER TABLE position_outcomes ADD COLUMN conviction TEXT;  -- copied from the order at close time

-- Rewrite setup_performance to read straight from position_outcomes (the old definition
-- joined setups/orders on orders.setup_id, which is never populated → always empty).
DROP VIEW IF EXISTS setup_performance;
CREATE VIEW setup_performance AS
SELECT
    direction,
    conviction,
    COUNT(*)                                              AS total,
    SUM(CASE WHEN outcome = 'WIN' THEN 1 ELSE 0 END)      AS wins,
    ROUND(100.0 * SUM(CASE WHEN outcome = 'WIN' THEN 1 ELSE 0 END) / COUNT(*), 1) AS win_pct,
    ROUND(AVG(pnl), 2)                                    AS avg_pnl
FROM position_outcomes
WHERE conviction IS NOT NULL
GROUP BY direction, conviction;
