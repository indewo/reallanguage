# 0002 — PostgreSQL access and migrations

- Status: Accepted
- Date: 2026-09-01
- Product baseline: v1.26 (2026-08-31)

## Context

RealLanguage needs the first formal cloud tables for Identity and Settings. `ARCHITECTURE.md` requires PostgreSQL, explicit Repository ports, visible transaction boundaries, optimistic locking, module-owned schemas, auditable migrations, and expand/migrate/contract compatibility. `DESIGN.md` selects a type-safe SQL builder rather than shared ORM entities.

This ADR is an implementation proposal only. It does not add product behavior, change data ownership, or authorize cloud storage of learning material.

## Decision

Use:

- Kysely as the type-safe SQL builder and transaction boundary;
- node-postgres (`pg`) as the production PostgreSQL driver and connection pool;
- Kysely's migration infrastructure with globally ordered, module-prefixed migration files under `database/migrations`;
- one generated TypeScript database interface per owning cloud module, with no shared ORM entities;
- a dedicated migration command; application startup must never silently apply production migrations.

Repository adapters receive either the root database handle or the transaction handle explicitly. Multi-write use cases open one Kysely transaction and pass that handle to every participating repository. Code must not run transaction statements through unrelated pool clients; PostgreSQL transactions are connection-scoped.

## Migration rules

1. File names use UTC ordering and module ownership, for example `202609010001_identity_create_registration_challenges.ts`.
2. Each migration has a forward path, compatibility note, verification query, and rollback classification.
3. Destructive changes use separate expand, migrate, and contract releases.
4. A deployed migration is immutable. Corrections use a later migration.
5. Module schemas and tables are created only by their owning module's migration.
6. Application deploy and migration execution are separate release steps.
7. CI tests migration from an empty database and from the previous released schema using real PostgreSQL.
8. Fast local migration checks may additionally use PGlite, but PGlite cannot replace the real PostgreSQL release gate.

## Repository and transaction rules

- Application use cases own transaction boundaries.
- Domain code imports no Kysely, `pg`, SQL, or migration types.
- Repository public methods include the authenticated `userId` for user-owned cloud data.
- Updates include aggregate ID, owner ID, and expected `rowVersion`.
- SQL identifiers are static; values use driver parameters.
- Pool acquisition, query, and transaction timeouts are explicit configuration.
- Connections are released in all success and failure paths.
- Database errors are mapped to stable application errors; raw SQL and driver errors never reach Android.

## Testing

- Domain and Application tests use fake ports and no database.
- Repository integration tests use isolated schemas or isolated databases.
- Migration tests verify constraints, indexes, ownership, forward upgrade, and rollback classification.
- Transaction tests cover rollback, optimistic-lock conflict, duplicate idempotency keys, and concurrent uniqueness.
- Tests never share mutable accounts, balances, sessions, or challenge records.

## Alternatives considered

### Drizzle ORM

Strong TypeScript support and migration tooling, but its ORM-shaped API makes it easier to spread database models beyond infrastructure. RealLanguage already requires explicit repositories and SQL-oriented transaction control, so Kysely is the narrower fit.

### Prisma

Productive schema and client generation, but its generated global data model and migration workflow add friction to module schema ownership, handwritten constraints, and explicit SQL review.

### Raw node-postgres only

Provides direct SQL and reliable pooling, but would require maintaining query result typing and schema drift checks manually.

## Consequences

- SQL and transactions remain visible and reviewable.
- Domain and Application layers remain independent of the data tool.
- Database interfaces and migration discipline add some boilerplate.
- Real PostgreSQL remains required in CI before database work can pass the release gate.
- Exact package versions are pinned only when this ADR is accepted and implementation begins.

## Acceptance

Accepted by the product owner on 2026-09-01. This authorizes the tool category and migration workflow. It does not decide the cloud provider, region, production PostgreSQL version, backup product, or retention schedule.

## Primary references

- [Kysely dialect and PostgreSQL support](https://kysely-org.github.io/kysely-apidoc/interfaces/Dialect.html)
- [Kysely migration locking and transactional DDL](https://kysely-org.github.io/kysely-apidoc/interfaces/DialectAdapter.html)
- [node-postgres pool and transaction connection rules](https://node-postgres.com/apis/pool)
