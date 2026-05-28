-- M3 (Phase 4) hardening: prevent duplicate outcome rows if a crash occurs between
-- PerformanceDao.recordOutcome() and OrderDao.markClosed(). A UNIQUE index turns the
-- silent duplicate into a loud constraint violation that OutcomeTracker catches and treats
-- as "already recorded — just mark the order closed."

CREATE UNIQUE INDEX IF NOT EXISTS idx_position_outcomes_order_id
    ON position_outcomes(order_id);