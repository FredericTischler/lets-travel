-- V5__add_completed_at.sql
-- Dashboards' income aggregation (docs/lets-travel-architecture-decisions.md,
-- "Dashboards : agrégation cross-service" addendum).
--
-- Design decisions:
--   - completed_at: the instant a payment became COMPLETED. Income "per month"
--     must be bucketed by when the money was actually taken, not when the
--     payment was created: a MANUAL payment is created on the 30th and confirmed
--     by an admin on the 2nd of the next month. created_at (the only timestamp
--     so far) cannot express that. Set by Payment.setStatus on the transition to
--     COMPLETED (one place, so PATCH / Stripe webhook / PayPal capture all stamp
--     it); NULL for every non-COMPLETED row.
--   - Backfill: rows that are already COMPLETED get created_at — the best
--     available approximation, since the real completion instant was never
--     recorded. Documented as a known imprecision for pre-V5 data only.
--   - Partial index matching the single query shape (income of COMPLETED,
--     travel-linked, active payments grouped by travel and month).

ALTER TABLE payments
    ADD COLUMN completed_at TIMESTAMPTZ;

UPDATE payments SET completed_at = created_at WHERE status = 'COMPLETED';

CREATE INDEX idx_payments_income
    ON payments (travel_id, completed_at)
    WHERE status = 'COMPLETED' AND travel_id IS NOT NULL AND deleted_at IS NULL;
