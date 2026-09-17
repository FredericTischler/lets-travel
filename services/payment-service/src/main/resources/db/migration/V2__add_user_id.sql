-- V2__add_user_id.sql
-- Adds ownership: every payment now belongs to a User (identity-service).
--
-- Design decisions:
--   - user_id: plain UUID, NOT NULL, no default. No FOREIGN KEY constraint to
--     any users table, because there is no such table reachable from here:
--     payment-service has its own Postgres database (payment_db) and has
--     never had, and will never have, network/schema access to identity_db
--     (two separate databases by design, see identity-service's own V1).
--     A cross-database FK is not something Postgres supports anyway; the
--     alternative (a logical/application-level reference with no DB-enforced
--     integrity) is what is implemented here. Consequently there is no
--     CHECK/validation that a given user_id actually exists in identity_db at
--     insert time either — that trust boundary is assumed at the application
--     layer (see PaymentService.create()), not enforced by this schema.
--   - NOT NULL, no default value: unlike id/created_at/status in V1, user_id
--     is not something this schema can sensibly default (there is no
--     "anonymous" payment) — every payment creation path must supply it
--     explicitly from now on.
--   - Index: this increment ships together with a new
--     `DELETE /payments/by-user/{userId}` endpoint whose entire job is to
--     scan all ACTIVE payments for a given user_id (see
--     PaymentRepository.findAllActiveByUserId). That is a real, already-used
--     query pattern in this increment, not a speculative future one, so a
--     partial index matching it exactly (WHERE deleted_at IS NULL) is added
--     now rather than deferred. A full (non-partial) index was rejected: this
--     service never queries by user_id across soft-deleted rows, so indexing
--     those rows would only add write overhead with no read benefit.

ALTER TABLE payments
    ADD COLUMN user_id UUID NOT NULL;

CREATE INDEX idx_payments_user_id_active ON payments (user_id) WHERE deleted_at IS NULL;