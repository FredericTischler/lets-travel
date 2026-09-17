# payment-service

**Context:** Payment (Spring Boot · PostgreSQL).

## Current scope

Basic CRUD skeleton for the `Payment` resource, with a manual status
lifecycle:

- `POST /payments` — create a new manual payment. Always starts as `PENDING`;
  the client cannot influence the initial status (no status field on the
  create request).
- `GET /payments/{id}` — get an active payment by id (404 if absent or
  soft-deleted).
- `GET /payments` — list all active payments.
- `PATCH /payments/{id}/status` — transition a payment's status to
  `COMPLETED` or `FAILED`.
- `DELETE /payments/{id}` — soft-delete an active payment (404 if absent or
  already soft-deleted).

## Status lifecycle

Three statuses: `PENDING`, `COMPLETED`, `FAILED`.

- A payment is always created as `PENDING`.
- The only valid transitions are `PENDING -> COMPLETED` and
  `PENDING -> FAILED`. `PENDING` itself is never a valid transition target.
- Once a payment reaches `COMPLETED` or `FAILED`, its status is **immutable**:
  no further transition is permitted, not even to the same terminal value or
  to the other terminal value. Attempting one returns 409.

Soft-delete is independent from status: a `COMPLETED` payment can still be
soft-deleted.

## Soft-delete

Rows are never physically removed. "Delete" sets `deleted_at` to the current
timestamp; the row stays. Every read (`findById`, `findAll`) systematically
filters on `deleted_at IS NULL`, so a soft-deleted payment is
indistinguishable from a non-existent one to API callers.

## Configuration

All connection values are externalized via environment variables in
`application.yml` (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD`, `SERVER_PORT`). The service fails fast at startup if any of
them is absent.

Schema is owned by Flyway (`src/main/resources/db/migration/`); Hibernate
`ddl-auto` is set to `validate` only.

## Not yet implemented

- No integration with an external payment provider (e.g. Stripe, PayPal).
  All payments today are created manually via `POST /payments`; there is no
  external reference reconciliation flow, even though the entity has an
  `external_reference` column reserved for that purpose.
- No `/internal/*` cross-service endpoints.