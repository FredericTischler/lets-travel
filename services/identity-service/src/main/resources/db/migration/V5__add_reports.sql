-- V5__add_reports.sql
-- "Let's Travel" phase (docs/lets-travel-architecture-decisions.md §5):
-- signalements. A traveler reports a travel manager or another traveler —
-- always a report against a USER (reportedUserId), never against a travel.
-- ACID, transactional status transitions an admin can audit reliably — see
-- §5 for why this lives in Postgres (identity-service) and not Neo4j.
--
-- Same soft-delete pattern as `users` (V1__init.sql): deleted_at, never a
-- physical delete. No unique constraint on (reporter_id, reported_user_id):
-- a traveler can file multiple distinct reports against the same user.
--
-- status mirrors the immutable-terminal-status pattern payment-service uses
-- for Payment.status (V1__init.sql there): OPEN is the only non-terminal
-- value, set at creation; REVIEWED/DISMISSED/ACTIONED are terminal, reached
-- only from OPEN (enforced at the application layer by ReportService, not by
-- this CHECK constraint — same split of responsibility as
-- payment-service's Payment.status, whose allowed-values CHECK does not
-- exist either: see Payment.java javadoc). DEFAULT 'OPEN' NOT NULL: every
-- report starts open, the client never supplies a status at creation.

CREATE TABLE reports (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    reporter_id       UUID        NOT NULL,
    reported_user_id  UUID        NOT NULL,
    reason            TEXT        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'OPEN',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,

    CONSTRAINT pk_reports PRIMARY KEY (id),
    CONSTRAINT reports_status_check CHECK (status IN ('OPEN', 'REVIEWED', 'DISMISSED', 'ACTIONED'))
);

-- Backs GET /reports/count/{userId} (COUNT of active reports against a user)
-- and any future admin queue filtering by target.
CREATE INDEX idx_reports_reported_user_id_active
    ON reports (reported_user_id)
 WHERE deleted_at IS NULL;
