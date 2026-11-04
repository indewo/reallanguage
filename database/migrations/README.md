# Database migrations

Migrations are append-only, globally ordered, and owned by a backend business module. Tooling and execution rules are fixed by accepted ADR `docs/adr/0002-postgresql-access-and-migrations.md`.

The ordered migrations create the confirmed Identity registration, verification, and refresh-session tables. Production applications do not apply migrations during startup.
