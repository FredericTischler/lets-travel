-- V2__add_password.sql
-- Adds password storage to the existing `users` table. V1__init.sql is not
-- modified.
--
-- password_hash stores a BCrypt hash only — the plaintext password is never
-- persisted. NOT NULL: every user row from this point on must carry a
-- password hash (enforced at the application layer on POST /users).

ALTER TABLE users
    ADD COLUMN password_hash TEXT NOT NULL;