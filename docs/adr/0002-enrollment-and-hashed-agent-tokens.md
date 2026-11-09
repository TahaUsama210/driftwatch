# 2. Enrollment token plus hashed per-host agent tokens

- Status: accepted
- Date: 2026-11-03

## Context

Agents must prove who they are when submitting scans. A single shared secret for every
submission would mean one leaked host compromises the whole fleet's data, and revoking it
would break every agent at once.

## Decision

- A shared **enrollment token** is used only to enroll. The API returns a per-host
  **agent token** (`dwa_` + 32 random bytes, base64url).
- The API stores only the SHA-256 hash of each agent token and compares secrets in
  constant time. A fast hash is enough because the tokens are high-entropy random values,
  not passwords.
- Re-enrolling a host (same `machineId`) rotates its token. When a scan is rejected with
  401, the agent re-enrolls once.

## Consequences

- A database leak doesn't reveal usable agent tokens.
- One host's token can be rotated without affecting the rest of the fleet.
- Anyone holding the enrollment token can enroll new hosts. Phase 2 can add
  time-limited or single-use enrollment tokens.
