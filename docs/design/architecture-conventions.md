# Architecture checks

## Production declarations

Each named Kotlin class, interface, object, enum, value class, or annotation
owns a matching top-level `.kt` file. A companion belongs to its enclosing
type; an anonymous callback is not a separate named declaration. Named types
inside companions or functions still require their own files. Top-level
utility functions do not need artificial wrapper classes.

`ProductionFileConventionTest` compares the filesystem inventory of production
Kotlin files in the current Gradle module layout with the Konsist production
scope. It then checks every file, including nested and local declarations.
Parser fixtures exercise valid companions, anonymous objects, value classes
and utility functions, plus invalid multiple, nested, local and misnamed
declarations. Test-resource fixtures are not production source.

The inventory covers the repository's direct child modules and their
`src/main/kotlin` roots. Introducing custom source sets, nested module layouts,
or production Java requires updating the inventory and checks in the same
change; these cases are not implicitly covered.

The outcome/event split retains sealed hierarchies, property defaults, HTTP
JSON/SSE shapes and persisted discriminator values:

| Previous declaration | Current file/type |
| --- | --- |
| `RequestOutcomeStatus` in `RequestOutcome.kt` | `RequestOutcomeStatus.kt` |
| `AttemptOutcome.Success` | `AttemptSuccess` |
| `AttemptOutcome.Failure` | `AttemptFailure` |
| `AttemptOutcome.CancelledWithUsage` | `AttemptCancelledWithUsage` |
| `AttemptOutcome.Cancelled` | `AttemptCancelled` |
| `GatewayEvent.Delta` | `GatewayDeltaEvent` |
| `GatewayEvent.Complete` | `GatewayCompleteEvent` |

The nested types' JVM names change. Rebuild all consuming modules together;
this is not a binary-compatible update for separately compiled JVM clients.
The old nested names are not persisted by the current accounting adapters.
No public compatibility aliases or new HTTP fields are introduced.

## Domain responsibilities

The former `domain.model` package is replaced by responsibility packages.
No type aliases keep the old package alive. All existing consumers are rebuilt
with the new JVM names; public DTOs and persisted values are unchanged.

| Package under `domain` | Existing responsibility | Allowed domain dependencies |
| --- | --- | --- |
| `accounting` | Usage units, price snapshots, cost calculation | None |
| `identity` | Authenticated principal | None |
| `policy` | Guardrail/rate decisions, registry changes | None |
| `error` | Gateway/provider failures and transmission disposition | None |
| `stream` | Closeable local stream lifetime | None |
| `routing` | Deployments, routing views, circuit permits | accounting |
| `execution` | Request/attempt context and terminal outcomes | routing, accounting, error |
| `inference.chat` | Chat inputs, provider results, response events | accounting |
| `observation` | Metadata, owned observation handles and scoped streams | execution, stream |

Core primitives remain available under the existing module rule. Accounting
does not depend on execution, Chat or telemetry; observation consumes outcomes
without controlling settlement. `DomainArchitectureTest` checks this direction
and rejects unreviewed domain packages, including a return to `domain.model`.
A test-resource fixture demonstrates that an accounting-to-telemetry reference
is rejected by Konsist. Future inference APIs must add their actual types and
reviewed dependencies with their implementation, not empty packages or stubs.

## Inner dependency boundaries

The root Gradle build registers `verifyInnerDependencies` for each inner module:

| Module | Allowed project dependencies |
| --- | --- |
| core | None |
| domain | core |
| application | core, domain |

The task inspects resolved **compile and runtime** project/Maven dependency
graphs, including transitive modules, and rejects unresolved dependencies.
It runs before compilation and tests and as part of `check`. This catches
unused `runtimeOnly` framework dependencies that an import-only rule misses.
Existing Konsist tests separately enforce package direction and port naming.

Allowed external modules are the enumerated Kotlin runtime modules, JetBrains
annotations, and approved Jackson/logging groups in `build.gradle.kts`.
Adding a cross-cutting library does not automatically approve all its
transitive dependencies. Review additions and update the policy deliberately;
do not disable the check to make a build pass. Test classpaths are excluded
from this production dependency rule. Local file dependencies and custom
configurations require an explicit boundary review; this graph check is not
a general-purpose artifact scanner.

## Verification

With the repository's JDK 21 configured, run:

```powershell
./gradlew.bat test koverVerify :llm-gateway-app:bootJar --rerun-tasks --warning-mode all --no-daemon --console=plain
```

Run these isolated negative probes separately. The init scripts are loaded
only by explicit `-I`; they do not add dependencies to normal builds:

```powershell
./gradlew.bat :llm-gateway-domain:verifyInnerDependencies -I llm-gateway-app/src/test/resources/architecture/runtime-framework-negative.init.gradle --warning-mode all --no-daemon --console=plain
./gradlew.bat :llm-gateway-domain:verifyInnerDependencies -I llm-gateway-app/src/test/resources/architecture/reverse-project-negative.init.gradle --warning-mode all --no-daemon --console=plain
```

Both probes must exit nonzero **because of the intended violation**: a
forbidden Spring module on `runtimeClasspath`, and the forbidden contract
project on `compileClasspath`, respectively. A dependency download or script
initialization failure is not successful negative evidence.

Record command, exit status, test counts/skips and code fingerprint in
[execution-status.md](execution-status.md). These architecture checks do not
prove PostgreSQL migration, Redis correctness, live provider compatibility,
HA or production readiness. Configuration-cache behavior is not verified by
these commands.
