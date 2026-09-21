-- V6__drop_role_default.sql
-- Security hardening (docs/lets-travel-architecture-decisions.md §1 addendum
-- "Bootstrap et création d'ADMIN"): V3/V4 left `DEFAULT 'ADMIN'` on users.role.
-- The application always writes the role explicitly (User constructor), so the
-- default was dead code -- but a dead default that hands out the most
-- privileged role to any INSERT that forgets the column is a trap. Dropping it
-- makes an insert without a role fail (NOT NULL) instead of creating an admin.
-- The CHECK constraint from V4 is untouched.

ALTER TABLE users ALTER COLUMN role DROP DEFAULT;
