-- V8 (/ slice 1): one PASSWORD account per email, expressed as a partial unique index.
--
-- Why, and what it does not do yet. V3 already enforces GLOBAL case-insensitive uniqueness over every
-- tenant_users row (uq_tenant_users_global_lower_email ON tenant_users (lower(email))), so while that index
-- exists this one is redundant: no duplicate email of any kind can be inserted. V8 is the forward step that makes
-- the invariant the application actually depends on - "at most one password account per email" - explicit and
-- durable at the database level, matching V3's case-insensitive key so the two agree.
--
-- The remaining relaxation is deliberately NOT part of this migration. requires that a Google identity always
-- gets its own tenant, which means a Google row may legitimately carry an email a password account also uses -
-- and V3 forbids exactly that. Dropping or replacing V3's index is a compatibility change to an applied
-- constraint, so it needs its own reviewed decision and its own forward migration; until then Google identity
-- creation for an already-registered email cannot succeed, which is why it stays unapproved.
--
-- Additive and forward-only. V1-V7 are untouched.
CREATE UNIQUE INDEX uq_tenant_users_password_email
    ON tenant_users (lower(email))
    WHERE password_hash IS NOT NULL;
