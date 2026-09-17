-- V3__add_role.sql
-- Adds a single-role authorization field to the existing `users` table.
-- V1__init.sql and V2__add_password.sql are not modified.
--
-- This is an Admin Dashboard: every account created through this system IS an
-- administrator (no role hierarchy at this stage — see docs/sujet.md §4 on
-- least privilege: the JWT must carry an explicit, enforced role claim
-- instead of "any authenticated account" implicitly granting full access).
--
-- DEFAULT 'ADMIN' NOT NULL: every existing row (created before this migration)
-- is backfilled to ADMIN by the DEFAULT clause applied at ALTER TABLE time,
-- and every future insert must supply a role (enforced at the application
-- layer on POST /users, which always sets ADMIN today).

ALTER TABLE users
    ADD COLUMN role TEXT NOT NULL DEFAULT 'ADMIN';
