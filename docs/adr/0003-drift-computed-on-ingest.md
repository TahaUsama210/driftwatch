# 3. Compute drift at ingest time and store it as events

- Status: accepted
- Date: 2026-11-03

## Context

The question operators ask most is "what changed?": a control that passed yesterday and
fails today. That could be computed when the dashboard asks, by diffing scans on read, or
once when each scan arrives.

## Decision

When a scan is ingested, the API compares it with the host's previous scan (by
`finishedAt`) in the same transaction and stores each status change as a `drift_event`
with a direction: `REGRESSED`, `RESOLVED` or `CHANGED`. The host row is locked during
ingest, so two concurrent submissions from one host can't both diff against the same
baseline. For comparing any two scans on demand, the API still offers an ad-hoc diff
(`GET /hosts/{id}/drift`).

## Consequences

- The fleet-wide "recent changes" feed is a cheap indexed read instead of diffing every
  host's history on each dashboard refresh.
- Events are an append-only audit trail: when a regression happened and which scan first
  showed it.
- A scan that arrives out of order (an older `finishedAt` than the latest stored scan) is
  diffed against its predecessor. The events of the scan right after it are rebuilt so
  history stays consistent. It doesn't move the latest-scan pointer or overwrite host
  facts. The API README documents the exact behaviour.
