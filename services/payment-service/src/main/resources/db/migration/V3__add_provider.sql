-- V3__add_provider.sql
-- Adds provider tracking: every payment now records where it came from
-- (docs/sujet.md §2 — Stripe/PayPal support on top of the pre-existing manual
-- payment path).
--
-- Design decisions:
--   - provider: NOT NULL, DEFAULT 'MANUAL' so every existing row backfills to
--     the pre-existing behaviour without a manual data migration step — the
--     manual payment path is unaffected by this change (see Payment's
--     3-argument constructor, still forces MANUAL).
--   - No Postgres enum type / CHECK constraint: same rationale already
--     applied to `status` in V1 — plain VARCHAR, the allowed value set is
--     owned by the Java enum (com.travelplan.payment.entity.PaymentProvider)
--     and Hibernate's @Enumerated(EnumType.STRING), not by the schema.
--   - `external_reference` (already present since V1, previously unused) is
--     reused as-is to store the provider's own identifier (Stripe
--     PaymentIntent id / PayPal Order id) — exactly the purpose it was
--     reserved for in V1's comments. No new column needed for that.

ALTER TABLE payments
    ADD COLUMN provider VARCHAR NOT NULL DEFAULT 'MANUAL';
