# 1. Go agent with a pluggable filesystem

- Status: accepted
- Date: 2026-11-03

## Context

The agent runs on every host in the fleet. It must be trivial to deploy, use few
resources, and run checks that read system files (`sshd_config`, `/etc/shadow`,
`/etc/login.defs`, procfs sysctls). The checks are the core logic of the product, so they
need thorough tests that don't depend on the machine running the tests.

## Decision

- Write the agent in Go, built as one statically linked binary (`CGO_ENABLED=0`) with
  minimal dependencies.
- Every check reads through a small filesystem interface rooted at `--root`, which can
  read files and return owner UID and mode. Production uses the real filesystem; tests
  use an in-memory fake.
- `--root` and `--proc` also let the agent scan a mounted image or a host root from inside
  a container.

## Consequences

- Each check has table-driven tests for its pass, fail, not-applicable and error paths,
  and they run on any OS, including the Windows development machine.
- No interpreter or runtime is needed on hosts, so installing the agent is just copying
  one binary.
- Reading `/etc/shadow` requires root, so the agent runs as root. This is inherent to a
  host compliance agent. The agent only reads and never changes the host in phase 1.
