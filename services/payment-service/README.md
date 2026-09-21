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

- `POST /payments/stripe`, `POST /payments/paypal`, `POST /payments/paypal/{orderId}/capture`,
  `POST /webhooks/stripe` — provider-backed payments (Stripe PaymentIntent +
  signed webhook, PayPal order + explicit capture).
- `GET /payments/summary[?userId=]` — the caller's payment summary (count and
  total per provider over `COMPLETED` payments, totals per currency, most-used
  provider). Any role, own payments only; `userId` of someone else is `ADMIN`
  only (403 otherwise). It is the payment half of the traveler's "preferred
  payment methods" stat.
- `POST /payments/reconcile-subscriptions` — `ADMIN` only, re-sends to
  travel-service every terminal subscription-linked payment it has not
  acknowledged (see "Paying for a subscription"). Returns
  `{attempted, notified}`.

## Paying for a subscription

(docs/lets-travel-architecture-decisions.md §4 and its addendum.) The three
create endpoints (`POST /payments`, `/payments/stripe`, `/payments/paypal`)
accept an optional pair `travelId` (the Destination id) + `subscriptionRef` (id
of the `SUBSCRIBED` relation in travel-service) — both or neither, else 400.
Stored on `payments` (migration `V4`, no FK: both point into travel-service's
Neo4j). Ownership is the existing rule: `userId` must be the caller (403
otherwise; `ADMIN` may act for anyone). travel-service is the normal caller: it
forwards the traveler's own token.

When such a payment reaches `COMPLETED` or `FAILED` (admin PATCH, Stripe
webhook, PayPal capture), and once that change is committed,
`SubscriptionPaymentNotifier` calls travel-service
`POST /internal/subscriptions/{subscriptionRef}/payment-result` with a
`service:payment` service token (`JwtService.generateServiceToken()`, same
shared HS256 secret), 3 s/5 s timeouts, `X-Request-Id` propagated.
travel-service moves the subscription to `ACTIVE` / `CANCELLED`.

- **Best-effort, no distributed transaction.** A failed call (travel-service down,
  timeout, non-2xx) is logged and swallowed: the payment status change is
  never rolled back or failed because of it, and a Stripe webhook still
  answers 200.
- **Reconciliation seam.** `payments.travel_notified_at` is set when travel-service
  answered 2xx; a terminal payment with a `subscription_ref` and a `NULL`
  `travel_notified_at` is a confirmation still to send.
  `POST /payments/reconcile-subscriptions` re-sends them (the travel-service
  endpoint is idempotent). Nothing calls it automatically yet.
- Payments without `subscriptionRef` are untouched by all of this.

## Income aggregate for the dashboards

`GET /payments/income` (ADMIN, or the `service:travel` token travel-service
mints for exactly this call; a manager/traveler token or the `service:identity`
token gets 403) returns `{"rows": [{travelId, month, currency, total, count}]}`:
`COMPLETED`, non-deleted, travel-linked payments grouped by travel, calendar
month of **completion** (UTC, `YYYY-MM`) and currency, never summed across
currencies. payment-service does not know managers, so it neither filters nor
windows: travel-service groups the rows by manager and window. Migration `V5`
adds `payments.completed_at`, stamped by `Payment.setStatus` on the transition
to `COMPLETED` (so admin PATCH, Stripe webhook and PayPal capture all set it);
rows already `COMPLETED` before `V5` are backfilled with `created_at`, an
approximation. The `service:travel` token is refused by every other route
(401), like `service:identity`.

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
`DB_PASSWORD`, `JWT_SIGNING_KEY`, the Stripe/PayPal credentials,
`TRAVEL_SERVICE_URL`, `SERVER_PORT`). The service fails fast at startup if any
of them is absent — including `TRAVEL_SERVICE_URL`, the base URL of
travel-service used for the subscription payment callback. (The test suite
supplies a test-only default in `src/test/resources/application.properties`;
production has none.)

Schema is owned by Flyway (`src/main/resources/db/migration/`); Hibernate
`ddl-auto` is set to `validate` only.

## Not yet implemented

- (The two lines this section used to carry — "no Stripe/PayPal" and "no
  `/internal/*` endpoints" — were stale before this phase: Stripe and PayPal
  are wired, and `DELETE /payments/by-user/{userId}` already accepts identity's
  service token.)
- No automatic reconciliation: `POST /payments/reconcile-subscriptions` exists
  but nothing schedules it; a subscription-linked payment whose confirmation
  call failed stays un-notified until an admin triggers it.
- The travel-service callback runs synchronously in the request thread that
  changed the status (up to ~8 s if travel-service hangs); no `@Async`, no queue.
- No PayPal webhook (capture is client-driven), no de-duplication of Stripe
  event ids; unchanged by this phase.
- No refund flow. A payment that completed for a subscription travel-service
  could not activate (it was cancelled while the payment was in flight)
  stays `COMPLETED` here and is logged as an error in travel-service, to be
  refunded by hand.
- `GET /payments/income` returns every (travel, month, currency) row in one
  response, unpaginated and uncached; pre-`V5` completions are bucketed on
  `created_at`.
- `GET /payments/summary` counts only `COMPLETED` payments and does not
  convert currencies (totals are per currency).
- Not exercised by the test suite: the real Stripe/PayPal round trips (the 3
  skipped tests need sandbox credentials); the callback is tested against a
  stubbed travel-service, not a running one.