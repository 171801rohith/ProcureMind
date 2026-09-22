-- contract-service is the only writer of `contracts` and should own its DDL going forward,
-- but the table was originally created by ai-service's V1__init_schema.sql, which cannot be
-- edited retroactively without breaking Flyway's checksum validation on every database that
-- already applied it (including this project's own dev volume).
--
-- This is version 1 of contract-service's OWN flyway_schema_history_contract history table
-- (see application.yaml), separate from ai-service's flyway_schema_history. Two independently
-- deployed services cannot safely share one Flyway history table even when they share a
-- physical schema: Flyway's validate() cross-checks the whole applied-migration history
-- against what is resolvable from the local classpath, so each service would permanently fail
-- validation on the versions the *other* service applied. A dedicated history table removes
-- that coupling entirely: this migration always runs against a fresh, service-local history,
-- so it needs no version-numbering tricks or startup-ordering dependency on ai-service.
--
-- IF NOT EXISTS makes it a safe no-op on every database today, where ai-service's V1 already
-- created this table; on a database where contract-service's migrations run before
-- ai-service's ever have, it is what actually creates the table. ai-service's V1 keeps
-- creating this table too, purely for checksum compatibility with already-migrated databases;
-- it must not be edited. This file is the authoritative, forward-looking definition of the
-- `contracts` table — new columns/indexes on it belong in a future contract-service migration.
CREATE TABLE IF NOT EXISTS contracts
(
    id                UUID NOT NULL,
    filename          VARCHAR(255),
    minio_object_name VARCHAR(255),
    vendor_name       VARCHAR(255),
    status            VARCHAR(255),
    uploaded_at       TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_contracts PRIMARY KEY (id)
);
