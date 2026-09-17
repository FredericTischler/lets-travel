# identity-service

**Context:** Identity and authentication (Spring Boot · PostgreSQL).

## Current scope

Basic CRUD skeleton for the `User` resource, plus password-based
authentication:

- `POST /users` — create a user (email + password). Public — it is the only
  way to create the very first account. Rejects with 409 if the email is
  already active. The plaintext password is hashed with BCrypt before
  persistence; it never reaches the repository.
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
  caller, including in response timing).
- `GET /me` — resolve the active user identified by the
  `Authorization: Bearer <token>` header. Returns 401 (same generic message)
  if the header is absent/malformed, the token is expired/invalid, or its
  subject no longer maps to an active user.

There is no Spring Security filter chain in this codebase. Token validation
is manual: `GET /me`, `GET /users`, `GET /users/{id}` and `DELETE /users/{id}`
all reuse the exact same mechanism (read the header in the controller,
validate via `AuthService`/`JwtService`) and return the exact same generic
401 body on failure. "Protected" means "any authenticated user" — there is
no role/permission distinction (no `role` claim in the JWT), so this is not
ownership-aware: any valid token unlocks these routes regardless of whose
account it belongs to. `POST /users` and `POST /login` remain public.

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
- On login, tokens are HS256-signed JWTs with a 15-minute expiration. There is
  no refresh token, no revocation/blacklist, and no roles/permissions carried
  in the token — out of scope for this increment.
- The token subject is the user id; the only custom claim is the email.

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
`DB_PASSWORD`, `JWT_SIGNING_KEY`, `SERVER_PORT`). The service fails fast at
startup if any of them is absent — no silent default for a secret.
`CORS_ALLOWED_ORIGINS` is also externalized but, unlike the values above, it
is not a secret, so it ships with a sensible default (see CORS section).

Schema is owned by Flyway (`src/main/resources/db/migration/`); Hibernate
`ddl-auto` is set to `validate` only.