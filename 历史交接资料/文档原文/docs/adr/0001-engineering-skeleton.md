# 0001 — Engineering skeleton toolchain

- Status: Accepted
- Date: 2026-09-01

## Context

The v1.26 baseline requires one repository, five application entries, native Android, a TypeScript modular monolith, OpenAPI contracts, strict layering, and test-first delivery.

## Decision

Use the toolchain already confirmed by `ARCHITECTURE.md`, `DESIGN.md`, and `CONTRIBUTING.md`: Android API 26–36 with Kotlin and Compose, Node.js 24 with pnpm, NestJS with Fastify, and React for the restricted admin application. Pin exact dependency versions in repository configuration and the lockfile.

The skeleton contains no product tables or user-facing product behavior. Database access, migrations, and queue implementations require later ADRs before their first formal use.

## Consequences

The backend and shared packages can be verified immediately. Android verification additionally requires JDK 17 and Android SDK 36 on the host or CI runner.
