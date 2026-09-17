-- V4__restrict_role_values.sql
-- "Let's Travel" phase (docs/lets-travel-architecture-decisions.md §1):
-- restricts `role` (V3__add_role.sql) to the 3 roles the system now grants.
--
-- DEFAULT 'ADMIN' is kept on purpose: POST /users still accepts an omitted
-- `role` for backward compatibility with existing callers/tests (see
-- CreateUserRequest) and falls back to ADMIN in that case. Every row written
-- from now on — whether via the default or an explicit value — must satisfy
-- the CHECK below.

ALTER TABLE users
    ADD CONSTRAINT users_role_check CHECK (role IN ('ADMIN', 'TRAVEL_MANAGER', 'TRAVELER'));
