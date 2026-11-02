-- Forward migration V3: Enforce global tenant user email uniqueness per 05_BACKEND_SCHEMA.md
CREATE UNIQUE INDEX uq_tenant_users_global_lower_email ON tenant_users (lower(email));
