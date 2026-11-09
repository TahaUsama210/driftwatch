# 4. Contract-first API shared by every component

- Status: accepted
- Date: 2026-11-03

## Context

Three components in three languages (Go, Java, TypeScript) talk over one HTTP API. Without
a shared definition, field names, null handling and error shapes drift apart, and nobody
notices until the integrated system breaks.

## Decision

`contracts/openapi.yaml` is written first and treated as the source of truth:

- The API validates its real responses against it in integration tests.
- The dashboard generates its TypeScript types from it (`npm run gen:api`). CI fails if
  the committed types differ from what the generator produces.
- CI lints the contract itself.
- Errors are RFC 9457 `application/problem+json` everywhere.

## Consequences

- A contract change is one reviewable diff, and both the API and the dashboard fail to
  build until they agree with it.
- The components could be built in parallel against the contract, with no waiting on
  each other.
