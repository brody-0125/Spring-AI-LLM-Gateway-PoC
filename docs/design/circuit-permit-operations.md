# Circuit permit operations

This change is an implementation checkpoint, not a production-readiness claim.

## State and ownership

- Candidate inspection performs Redis reads only. The selected attempt acquires a permit before provider dispatch.
- A permit carries a server-generated attempt owner and a circuit generation. Terminal updates consume a matching receipt once. Old-generation completions cannot reset a newer open circuit.
- Circuit state retains the existing deployment key. Receipt keys use that complete key as a Redis hash tag so both script keys share a cluster slot. Prefixes containing braces are rejected.
- Closed-state and receipt retention use the configured state TTL. Open/probe state does not expire automatically. Receipt expiry cannot reopen an unresolved probe.
- A successful provider response closes its circuit independently of output policy rejection. A confirmed non-health provider rejection releases its receipt; a half-open circuit then waits another cooldown without incrementing health failures.
- Inspection/acquisition errors deny new dispatch and emit bounded backend-failure metrics. Terminal-update failures retain conservative state and emit diagnostics.

These are health-circuit permits, **not** remote capacity reservations. Redis data loss, unresolved remote work, durable recovery, and capacity fencing still require the journal/recovery work in P03/P05/P11. An unknown probe must not be cleared merely because a lease expired.

## Upgrade restriction

Do not mix the previous ownerless circuit writer with this implementation: an old replica can still delete state without validating ownership. Before an authorized rollout, stop new admission, drain old replicas and verify no old writer remains. Keep existing open state. A legacy claimed probe or unresolved new probe requires confirmed execution termination and an audited recovery decision, not a blanket Redis flush or a TTL reset.

Automated probe reconciliation and a zero-downtime mixed-version migration are not implemented at this checkpoint. P11/P13 must supply and verify those paths before the corresponding operational gates can pass. No Redis mutation or deployment has been performed as part of this local change.

## Verification

Local tests cover read-only planning, selected-attempt wiring for JSON/SSE, denied dispatch with zero provider calls, result mapping, and backend failure handling.

The gated Redis integration suite covers duplicate completion, stale generation, read-only inspection of three half-open candidates, concurrent acquisition from independent clients, and unresolved probe retention after receipt expiry. These cases need an actual Docker/Testcontainers run; scripted Redis responses do not validate Lua atomicity.

The PostgreSQL integration fixture now migrates a fresh per-test schema using the app's shipped Flyway migrations. It no longer drops shared tables or maintains a second handwritten schema.
