-- V1__init.sql
-- Foundational schema for identity-service.
-- Owned entirely by Flyway; never modified by Hibernate ddl-auto.
--
-- Design decisions applied from the start (not as later patches):
--   - id: UUID primary key (gen_random_uuid() requires pgcrypto — available in
--     Postgres 13+ via built-in gen_random_uuid()).
--   - created_at / deleted_at: TIMESTAMPTZ, soft-delete pattern.
--   - email: present in V1 because the partial unique index (email, deleted_at)
--     is an architectural foundation that must be laid now, not in a later patch.
--     The column is NOT NULL (active users must have an email); this constraint
--     is enforced at the application layer for future inserts.
--   - Unique partial index on email WHERE deleted_at IS NULL: a soft-deleted user
--     does not block re-registration with the same email address.

CREATE TABLE users (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    email       TEXT        NOT NULL,

    CONSTRAINT pk_users PRIMARY KEY (id)
);

-- Partial unique index: ensures email uniqueness only among active (non-deleted)
-- users. Soft-deleted rows (deleted_at IS NOT NULL) are excluded from the index.
CREATE UNIQUE INDEX uq_users_email_active
    ON users (email)
 WHERE deleted_at IS NULL;