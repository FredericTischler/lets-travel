-- V4__link_payment_to_subscription.sql
-- Links a payment to a travel subscription
-- (docs/lets-travel-architecture-decisions.md §4 and its Phase 4 addendum).
--
-- Design decisions:
--   - travel_id: the Destination id (travel-service, Neo4j) the payment is
--     for. Nullable: a payment need not be about a subscription at all (every
--     pre-existing row, and every payment created without these two fields,
--     stays exactly as before).
--   - subscription_ref: the applicative id of the SUBSCRIBED relation in
--     travel-service (Neo4j) that this payment settles. Nullable, same reason.
--   - NO foreign key on either column: both point into another service's
--     store (Neo4j), so there is nothing to reference — same "applicative
--     reference, integrity assumed at the application layer" principle as
--     user_id (V2).
--   - travel_notified_at: set once travel-service has acknowledged (2xx) the
--     terminal status of this payment (COMPLETED/FAILED). A terminal payment
--     that has a subscription_ref but a NULL travel_notified_at is exactly a
--     "confirmation call that failed" — this column is the reconciliation
--     seam (POST /payments/reconcile-subscriptions re-sends them). Nullable:
--     rows unrelated to a subscription never use it.
--   - Indexes: partial, matching the two real query shapes only —
--     lookup by subscription_ref, and the reconciliation scan of terminal,
--     un-notified, subscription-linked payments.

ALTER TABLE payments
    ADD COLUMN travel_id UUID,
    ADD COLUMN subscription_ref UUID,
    ADD COLUMN travel_notified_at TIMESTAMPTZ;

CREATE INDEX idx_payments_subscription_ref_active
    ON payments (subscription_ref)
    WHERE subscription_ref IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX idx_payments_pending_travel_notification
    ON payments (created_at)
    WHERE subscription_ref IS NOT NULL AND travel_notified_at IS NULL
      AND status <> 'PENDING' AND deleted_at IS NULL;
