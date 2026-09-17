-- V1__init.sql
-- Foundational schema for payment-service.
-- Owned entirely by Flyway; never modified by Hibernate ddl-auto.
--
-- Design decisions applied from the start (not as later patches):
--   - id: UUID primary key (gen_random_uuid() requires pgcrypto — available in
--     Postgres 13+ via built-in gen_random_uuid()).
--   - created_at / deleted_at: TIMESTAMPTZ, soft-delete pattern (same
--     foundation as identity-service's V1).
--   - amount: NUMERIC, deliberately WITHOUT a fixed precision/scale. Money is
--     never stored as float/double (binary floating point cannot represent
--     decimal fractions exactly, e.g. 0.1 + 0.2 != 0.3 — unacceptable for
--     currency amounts). A fixed scale such as NUMERIC(12,2) was considered
--     but rejected here: ISO 4217 currencies do not all use 2 decimal places
--     (e.g. JPY uses 0, BHD uses 3), and this increment does not yet encode
--     any currency-specific rounding rule. Leaving NUMERIC unconstrained keeps
--     the column exact and generic; a per-currency scale constraint can be
--     added later (application-level or a future migration) once that rule
--     is actually decided.
--   - currency: ISO 4217 alphabetic code, fixed 3-character. No CHECK
--     constraint listing valid codes at this stage — validation against the
--     ISO 4217 list is application-level logic, out of scope for this
--     increment.
--   - status: plain NOT NULL string defaulting to 'PENDING'. No Postgres enum
--     type, no CHECK constraint enumerating allowed values — the state
--     machine governing transitions is explicitly out of scope for this
--     increment and will be introduced later.
--   - external_reference: nullable, free-form text placeholder for a future
--     Stripe/PayPal identifier. No network call to any provider happens in
--     this increment; this is only a column reserved for that future use.
--   - No partial unique index in this migration: identity-service's V1 has
--     one on (email, deleted_at) because email uniqueness among active users
--     is a real invariant from day one. payments has no equivalent natural
--     uniqueness constraint at this stage, so none is forced here.

CREATE TABLE payments (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    amount              NUMERIC     NOT NULL,
    currency            VARCHAR(3)  NOT NULL,
    status              VARCHAR     NOT NULL DEFAULT 'PENDING',
    external_reference  VARCHAR,

    CONSTRAINT pk_payments PRIMARY KEY (id)
);