# Explicit registry initialization

## Normal startup

Replica startup constructs read adapters and provider clients, but does not write configured deployments or prices into the managed registry. Missing local configuration does not disable managed deployments. Existing routing enable/priority/weight/model/pricing values survive restart.

Database migrations still run according to the existing Flyway configuration. Removing configuration seeding does not mean startup is a read-only database session; separate migration credentials and deployment jobs remain P13 work.

## Authorized initialization

After reviewing provider configuration and the target database, run one of:

```powershell
.\gradlew.bat :llm-gateway-app:bootRun --args="seed-registry" --no-daemon
# Or, after building the executable jar:
java -jar llm-gateway-app/build/libs/llm-gateway.jar seed-registry
```

The command forces non-web mode, activates the seed profile, invokes RegistrySeedCommandIn, reports only created/existing counts, and closes its context. Configuration supplies the same deployment definitions as normal startup. OpenAI-compatible definitions still require their configured credential to be present for selection, but no provider client is constructed and no model call is made.

The current command reuses application bootstrap and therefore expects its configured PostgreSQL/Redis dependencies. It must run as an authorized management operation; it is not exposed through a public endpoint. Never configure it as a recurring replica startup command.

## Transaction and retry behavior

- Validate non-empty configuration and unique deployment IDs before writing.
- Lock the routing version row, using the same lock order as managed routing updates.
- Insert missing deployment rows only. Existing rows are not updated or disabled.
- For newly inserted rows, record initial versioned pricing in the same transaction and increment routing revision once.
- Repeating the command for existing IDs returns existing counts without changing prices or revisions. Changed configuration for an existing ID is not a policy publish.
- Pricing without a corresponding deployment is a reconciliation conflict: the entire seed transaction rolls back. Do not delete historical prices automatically to bypass this check.

After a lost command response, verify managed state and rerun the same configuration. Atomic commit and insert-only semantics are intended to prevent partial initialization and repeated updates; actual PostgreSQL contention/rollback validation is still required.

## Verification and restrictions

Local tests verify input validation, metadata-only seed construction, Spring non-web wiring, and unchanged registry values across adapter construction. PostgreSQL tests cover concurrent seed commands, preservation of managed overrides/pricing, and rollback on an orphan-pricing conflict.

Docker was unavailable during this checkpoint. Those PostgreSQL cases and three-process rolling-replica tests are not certified. Stop/drain legacy configuration writers before claiming the new startup behavior across a fleet. Policy publish, snapshot/client refresh, separate admin execution, and migration-role isolation remain P07/P13 deliverables.
