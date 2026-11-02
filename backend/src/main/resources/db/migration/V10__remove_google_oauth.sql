-- V10: Remove Google OAuth / OIDC feature artifacts from tenant_users schema.
--
-- With Google OAuth removed from the platform:
-- 1. Drop check constraint chk_tenant_users_auth (which allowed null password when google_sub was present)
-- 2. Drop unique constraint on google_sub and drop column google_sub
-- 3. Enforce password_hash NOT NULL on all tenant users
-- 4. Re-establish global unique index on lower(email) across all tenant_users rows

ALTER TABLE tenant_users DROP CONSTRAINT IF EXISTS chk_tenant_users_auth;
ALTER TABLE tenant_users DROP CONSTRAINT IF EXISTS tenant_users_google_sub_key;
ALTER TABLE tenant_users DROP COLUMN IF EXISTS google_sub;
ALTER TABLE tenant_users ALTER COLUMN password_hash SET NOT NULL;

DROP INDEX IF EXISTS uq_tenant_users_password_email;
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_users_email ON tenant_users (lower(email));

