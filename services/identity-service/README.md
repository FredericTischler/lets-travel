# identity-service

**Context:** Identity and authentication (Spring Boot · PostgreSQL).

## Current scope

Basic CRUD skeleton for the `User` resource, plus password-based
authentication:

- `POST /users` — create a user (email + password + optional `role`). Public,
  so people can sign up, **but the role depends on the caller**: without a
  valid ADMIN token only `TRAVELER` and `TRAVEL_MANAGER` may be requested
  (asking for `ADMIN` is a 403 and creates nothing; an absent/invalid token is
  treated as anonymous, not as a 401), an authenticated ADMIN may create any
  role, and an omitted `role` means `TRAVELER` — never a silent ADMIN. Rejects
  with 409 if the email is already active. The plaintext password is hashed
  with BCrypt before persistence; it never reaches the repository.
- `GET /users/{id}` — get an active user by id (404 if absent or
  soft-deleted). Requires a valid `Authorization: Bearer <token>` header.
- `GET /users` — list all active users. Requires a valid
  `Authorization: Bearer <token>` header.
- `DELETE /users/{id}` — soft-delete an active user (404 if absent or already
  soft-deleted). Requires a valid `Authorization: Bearer <token>` header.
- `POST /login` — verify email + password for an active user. Public —
  no token can exist before a successful login. Returns 200 with the user's
  id, email, and a signed JWT on success; 401 with a generic message on any
  failure (unknown email and wrong password are indistinguishable to the
  caller, including in response timing). Brute-force protection
  (`LoginThrottle`): after 5 failures for a normalised email or 50 for a client
  IP within 900 s, further attempts get 429 with `Retry-After`, before any
  lookup; a success resets the email counter. Tunables (not secrets, so they
  have defaults): `LOGIN_THROTTLE_EMAIL_MAX_FAILURES`, `_IP_MAX_FAILURES`,
  `_WINDOW_SECONDS`, `_MAX_TRACKED_KEYS`; `LOGIN_THROTTLE_TRUST_FORWARDED_FOR`
  (default `false`, set to `true` in the Compose fragment: behind Traefik the
  client IP is the last `X-Forwarded-For` hop). **Limits**: counters are in
  memory and per replica (N replicas = up to N times the budget, reset on
  restart), a third party can lock a known email for one window, and a flood of
  random emails can evict entries (ADR §10, addendum G4).
- `GET /me` — resolve the active user identified by the
  `Authorization: Bearer <token>` header. Returns 401 (same generic message)
  if the header is absent/malformed, the token is expired/invalid, or its
  subject no longer maps to an active user.

There is no Spring Security filter chain in this codebase. Token validation
is manual: every controller reuses the exact same mechanism (read the header,
validate via `AuthService`/`JwtService`) and returns the exact same generic
401 body on failure. Since docs/lets-travel-architecture-decisions.md §1,
`POST /users` accepts an explicit `role` (`ADMIN`/`TRAVEL_MANAGER`/`TRAVELER`,
defaults to `TRAVELER` when omitted; `ADMIN` needs an admin caller) and every
JWT carries that role as a claim.
`GET /users`, `GET /users/{id}`, `PATCH /users/{id}` and `DELETE /users/{id}`
require the caller to be an `ADMIN` (`AuthService#requireAdmin`); the report
endpoints below use a looser `AuthService#requireAnyRole` gate (any of the 3
known roles, still authenticated). `POST /users` and `POST /login` remain
public.

## First admin (bootstrap)

Since `POST /users` cannot mint an ADMIN for an anonymous caller, the very
first admin is created **at startup** by `BootstrapAdminInitializer`, from two
optional environment variables (Vault `secret/identity/bootstrap-admin` →
Ansible `app-secrets` → `/opt/travel-plan/.env` → `docker-compose.identity.yml`):

- `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` both unset: nothing happens.
- Exactly one set, an invalid email, or a password shorter than 12 characters:
  the service **refuses to start** (fail-fast).
- Both set: the admin is created **only if no active ADMIN exists** — idempotent
  across restarts and replicas; password hashed with BCrypt; neither password
  nor email is ever logged. If every admin is later deleted, the next startup
  recreates the bootstrap one (a deliberate break-glass path).

Rationale and rejected alternatives: `docs/lets-travel-architecture-decisions.md`
§1 addendum. There is still **no password-change or reset endpoint** (listed in
`docs/security-audit.md`), so rotating the bootstrap password means changing the
Vault value and soft-deleting the admin row so the next startup recreates it.

## Reports (signalements)

docs/lets-travel-architecture-decisions.md §5: a traveler reports a Travel
Manager or another traveler; an admin reviews and resolves reports. Backed by
the `reports` table (`V5__add_reports.sql`), same soft-delete/Flyway-owned
pattern as `users`.

- `POST /reports` — file a report against another user. Any authenticated
  role. `reporterId` is always the caller's own id, resolved from the Bearer
  token — never taken from the request body (same principle payment-service
  applies to payment ownership). Rejects with 400 if the caller tries to
  report themselves, 404 if `reportedUserId` does not correspond to an
  existing active user (docs/lets-travel-architecture-decisions.md §5bis: the
  existence check is done because this service owns the `users` table, unlike
  payment-service's unchecked `Payment.userId`).
- `GET /reports` — list all active reports. `ADMIN` only.
- `PATCH /reports/{id}/status` — transition a report from `OPEN` to
  `REVIEWED`, `DISMISSED` or `ACTIONED`. `ADMIN` only. Terminal once resolved
  (400 for an invalid target value, 409 if already terminal) — mirrors
  payment-service's `Payment.status` transition rules.
- `GET /reports/count/{userId}` — count of active reports filed against a
  user. Any authenticated role (it only returns a count, never report
  contents). Returns 0, not 404, for an id with no reports (including an
  unknown id) — same "empty result is not an error" rule as
  payment-service's `deleteAllByUserId`.

## CORS

Browser origins for the admin dashboard are allowed via a plain Spring MVC
`WebMvcConfigurer` (`CorsConfig`), not a Spring Security `CorsConfigurationSource`
— there is no security filter chain to hang one off. Allowed by default:
`http://localhost:4200` (Angular dev server) and `https://admin.localhost`
(dashboard routed through Traefik). GET/POST/PATCH/DELETE and the
`Authorization`/`Content-Type` headers are allowed. Overridable per
environment via `CORS_ALLOWED_ORIGINS` (comma-separated).

## Soft-delete

Rows are never physically removed. "Delete" sets `deleted_at` to the current
timestamp; the row stays. Every read (`findById`, `findAll`) systematically
filters on `deleted_at IS NULL`, so a soft-deleted user is indistinguishable
from a non-existent one to API callers.

A soft-deleted user's email is not blocked for re-registration — the unique
constraint on `email` only applies among active (non-deleted) rows.

## Authentication and JWT

- Passwords are hashed with BCrypt (`BCryptPasswordEncoder`).
- On login, tokens are HS256-signed JWTs with a 15-minute expiration.
- The token subject is the user id; custom claims are the email and the `role`.
- ~~No refresh token, no revocation~~ — **corrigé** : `POST /refresh` exchanges
  a separate, opaque, DB-backed refresh token (7-day validity, single-use —
  rotated on every redeem, `refresh_tokens` table, V7__add_refresh_tokens.sql)
  for a fresh access/refresh pair, and `POST /logout` revokes one on demand.
  Only the SHA-256 hash of the refresh token is ever persisted, same principle
  as `password_hash`. No token-family/reuse-detection beyond simple rotation
  (a stolen-and-replayed-before-the-legitimate-client token is rejected, but
  doesn't revoke the rest of that session's lineage) — an accepted, explicitly
  scoped gap, not an oversight.

## Assumed debt

- `JWT_SIGNING_KEY` is read directly from a plain environment variable — it
  is **not** wired to Vault the way the `DB_*` credentials are (those are
  injected by Docker Compose from Vault). This service never talks to Vault
  directly (consistent with the "Ansible reads from Vault and renders" model
  used elsewhere in this repo); the JWT signing key is an explicit, assumed
  gap in that model, not a silent shortcut. See the Javadoc on `JwtService`
  for the full rationale.

## Configuration

All connection and secret values are externalized via environment variables
in `application.yml` (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD`, `JWT_SIGNING_KEY`, `PAYMENT_SERVICE_URL`, `SERVER_PORT`). The service
fails fast at startup if any of them (except `SERVER_PORT`) is absent — no silent
default for a secret. (Spring placeholders are bare `${VAR}`: the Compose-style
`${VAR:?msg}` is *not* fail-fast in Spring — it resolves to the literal `?msg`;
`ConfigFailFastTest` guards against reintroducing it.) `BOOTSTRAP_ADMIN_EMAIL` /
`BOOTSTRAP_ADMIN_PASSWORD` are optional (see "First admin").
`CORS_ALLOWED_ORIGINS` is also externalized but, unlike the values above, it
is not a secret, so it ships with a sensible default (see CORS section).

Schema is owned by Flyway (`src/main/resources/db/migration/`); Hibernate
`ddl-auto` is set to `validate` only.