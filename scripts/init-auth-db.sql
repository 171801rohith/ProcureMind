-- Provisions the auth-service database inside the shared PostgreSQL container.
--
-- Mounted at /docker-entrypoint-initdb.d/ by docker-compose in Phase 2, this runs ONLY
-- when the postgres data volume is first created. For an existing volume, run once:
--   docker compose exec postgres createdb -U user procuremind_auth
--
-- auth-service owns this database entirely; it never touches procuremind_db.

SELECT 'CREATE DATABASE procuremind_auth'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'procuremind_auth')\gexec
