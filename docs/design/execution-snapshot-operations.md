# Execution routing and pricing snapshots

## Capture and lifetime

An execution captures one routing plan before its first attempt. The PostgreSQL adapter reads routing revision, deployments, and effective prices in one read-only, repeatable-read transaction. This transaction uses its own propagation boundary so a caller's weaker transaction cannot silently weaken snapshot isolation. It ends before provider I/O.

The plan copies its candidate and pricing collections into unmodifiable views. Retry uses the same deployment; fallback selects the next candidate from the captured order. Neither path reads a new policy or price version. A later execution captures the newly published state. Prices are not reconstructed from a timestamp during settlement, and the calculator does not perform database I/O.

JSON success, output-policy rejection, streaming success, partial failure, timeout, and cancellation-with-usage use the supplied price value. Missing captured pricing remains unknown; a known zero price and an unavailable price are not interchangeable. This work does not provide a hard-budget admission gate; reservation and journal integration remain P03 work.

## Emergency disable

The existing management override `enabled=false` is the emergency switch; no new client header, response field, or endpoint is introduced. Every selected attempt reads that switch separately in a fresh transaction before acquiring a circuit permit. Missing deployment or lookup failure rejects dispatch with the existing public unavailable error. It does not fabricate a provider failure or charge a call that never started. Re-enabling does not add a candidate to an already captured plan.

This is a pre-dispatch admission checkpoint, not cancellation of a remote call or an atomic transaction spanning PostgreSQL, Redis, and the vendor. A change after the checkpoint can race an already admitted dispatch. Do not interpret it as a verified fleet-wide revocation bound. Durable dispatch fencing, authority leases, and recovery are later P03/P07/P11 deliverables; already-started calls and accounting must still be drained safely.

Circuit acquisition continues to evaluate current Redis health. Policy priority, weight, model definition, and prices stay frozen; rate, budget, key revocation, and circuit safety must never be inferred from the frozen policy alone.

## Verification

Application tests cover JSON/SSE retry and fallback during a policy/price replacement, adoption by the next execution, emergency disable and lookup failure before retry, snapshot acquisition failure before provider dispatch, defensive collection copies, and old-price accounting after a partial streaming failure.

The PostgreSQL fixture pauses after the actual routing-version SELECT, commits a policy/price replacement using another connection, and resumes the actual snapshot read. It checks that the old view remains coherent, a subsequent capture sees the new revision, and the live enabled check sees the disable. The test does not rewrite SQL or substitute database results.

Docker is unavailable in the current verification environment. The real PostgreSQL contention fixture is compiled but not executed; A05 is not certified by the local unit/MVC regression suite alone.

## Remaining operational limits

- This checkpoint still reads the managed registry and performs per-deployment pricing queries when capturing a plan. P07 must introduce bounded indexed snapshots, refresh/polling, client lifecycle management, and round-trip limits. It is not a fleet-scale cache implementation.
- Provider credentials and clients remain startup configured. Runtime policy publication with prepared client replacement and drain is not implemented here.
- The availability read and snapshot capture still depend on PostgreSQL reachability. Control-plane/execution-store separation, query/deadline limits, and HA verification remain required.
- Accounting persistence still has pre-existing best-effort paths. Snapshot consistency must not be presented as durable settlement or crash-safe cost completeness.
- No actual deployment, paid provider call, secret change, commit, or push was performed.
