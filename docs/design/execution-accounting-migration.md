# Execution identity and attempt accounting

## Identity boundary

Each newly constructed request context receives a server-generated execution ID. HTTP accepts only the existing correlation header; it does not accept an execution ID. Retries and fallbacks retain the execution ID and receive distinct attempt IDs.

Routing uses the execution ID as its rendezvous key. Supplying the same correlation ID no longer pins independent requests to a selected route. Within one execution, the key remains stable. Request observations are keyed by execution ID, attempt observations by attempt ID. Structured logs retain correlation and execution separately; neither is a metric label.

Public JSON/SSE and error responses retain their existing formats. No new client header, field, or endpoint is introduced by this change.

## Attempt meaning

The operation records INITIAL when execution begins, RETRY when choosing another attempt on the same deployment, and FALLBACK when switching deployment. The kind is passed to the attempt operator, accounting adapter, and observer. It is not inferred from the sequence number.

The request aggregate sums all attempt usage and costs, with independent initial/retry/fallback counts. The expected four-attempt example is 1 initial + 2 retries + 1 fallback. Request accounting filters by execution and authenticated tenant/caller, and rejects an upsert that changes an existing execution's identity.

This does not implement durable dispatch, reconciliation, or a public usage authorization API. Those remain P03/P06 requirements.

## V6 expansion and historical data

V1–V5 are unchanged. V6 adds nullable execution/correlation/kind fields and new count fields, plus constraints and indexes.

- New rows have explicit execution identity. The existing physical `request_id` primary key column also contains that server execution ID during this compatibility stage. Its name must not be interpreted as the public correlation field.
- New consumers and aggregates use `execution_id`; correlation lookups use `correlation_id` with authenticated scope. A correlation lookup can return multiple executions.
- Historical rows keep their original keys, costs, and counts. Their new fields remain null because lost execution boundaries and retry kinds cannot be reconstructed reliably.
- Do not backfill execution IDs from legacy correlation IDs or reinterpret legacy `fallback_count` as an exact count of deployment switches.

Apply V6 before new writers. Existing legacy writers can still produce legacy rows, but their old collision behavior is not corrected by a schema expansion. Drain all old writers before claiming execution isolation. This checkpoint does not certify a mixed-version rollout; P13 must verify its migration and rollback path.

Do not drop the added columns as a routine rollback: doing so loses provenance. Keep the expanded schema, stop admission, drain writes, preserve the ledger, and use a schema-compatible writer. A journal migration must retain the explicit distinction between legacy and execution-aware records.

## Completion failures

Attempt and request accounting failures are no longer discarded by the
application. The gateway returns `OUTCOME_UNKNOWN` with `retryable=false` and
no `Retry-After`. For JSON this is HTTP 503; an already-started SSE response
uses its error event. Storage diagnostics remain internal. A failed attempt
write does not enter provider retry/fallback, even if the provider itself
failed with a retryable response.

The SSE controller forwards content incrementally but retains the single
success terminal until stream exhaustion and close have completed, including
request accounting. A write failure after visible content emits no success
terminal or `[DONE]`. This is a terminal-event barrier, not whole-response
buffering. A disconnect can still prevent error delivery.

Observation callbacks remain best-effort and are closed even when accounting
fails. The request observation reports a completion failure; an attempt
observation still describes the provider outcome and is not a settlement
receipt. If request accounting fails while another operation error is being
unwound, the accounting failure is public and the original failure is retained
as a suppressed diagnostic.

The checkpoints below add journal persistence, owned budget reservation/settlement
and bounded same-receipt completion retries to this failure boundary.
Native database fault verification and reconciliation remain pending. Do not enable production admission or claim
A09/A10/A13 completion from local tests. P03 must complete the transaction
protocol; P06 must provide authorized usage lookup. Until then, clients must
not automatically recreate an unknown generation request, and operators must
treat it as unresolved rather than infer a zero charge.

## V11 attempt journal checkpoint

The production Chat operators require `AttemptJournalPort`. The PostgreSQL
adapter commits PREPARED before acquiring the selected circuit permit, then
commits DISPATCH_INTENT before invoking the provider. Preparation or dispatch
failure returns ADMISSION_UNAVAILABLE without entering model retry/fallback.
V11 alone did not reserve a budget. The current V12 writer also reserves
owned funds in the same preparation transaction, as described below.

An unavailable permit abandons only PREPARED. If abandonment fails, PREPARED
remains for recovery. If dispatch acknowledgement is lost, the application
does not call the provider or guess whether PostgreSQL committed intent.
The locally acquired health permit can be released because this caller has
not invoked the provider; a committed intent remains conservatively UNKNOWN.
A stored circuit generation is not yet a durable capacity authorization.

Terminal recording locks the owned attempt and commits usage, component cost
lines, a versioned SHA-256 receipt and an outbox event in one transaction.
The existing component writer joins that transaction; it is not a second
production bean or an independent dual writer. Repeated identical completion
inputs are accepted without another event. Conflicting receipts require
reconciliation rather than overwriting usage. An outbox insertion failure
rolls back terminal and usage writes together, preserving dispatch intent.

These transactions use REQUIRES_NEW so a caller transaction rollback cannot
erase evidence after the provider boundary. Provider I/O is outside them.
The receipt contains normalized accounting metadata, not prompt/response
content. The outbox carries a schema version and immutable attempt reference;
its worker is still pending. RECORDED means the observed outcome was saved,
not that budget settlement or remote cancellation has been proven.

V11 is additive and does not fabricate intent for historical usage. Apply
migrations before the new writer, stop/drain old writers and retain the
expanded schema on rollback. Mixed-version safety and actual PostgreSQL
migration execution remain unverified. The existing request aggregate writer
is a separate compatibility projection, not an execution authority.

Remaining P03 work includes end-to-end deadline verification,
late usage adjustments and actual fault verification. P04/P05 must supply
grant issuance/import/transfers, recovery and outbox delivery/retention.
UNKNOWN is never free usage.

## V12 owned budget reservation checkpoint

An explicit PostgreSQL binding maps authenticated tenant/caller metadata to a
project and service identity. Credentials with the same binding share funds;
no end-user wallet or budget is inferred from a caller string. A period grant
is the local spending authority, not proof that central allocation has been
implemented. The migration creates no mappings, funds, limits or enabled
profiles. Only isolated tests provision fixture balances.

Preparation locks the owned project grant and then its service limit. Both
must cover the quote. Their holds, the reservation snapshot and PREPARED
commit or roll back together. There is no organization-row lock on this path.
The snapshot retains owner epoch, UTC half-open period, price version and
the four applicable unit prices, profile revision and billable token bounds.
Every retry/fallback carries the original request budget timestamp; settlement
uses the reservation period instead of the wall clock at completion.

The text Chat profile ceiling is a verified maximum billable input/output
bound for that exact deployment model and vendor, including provider-added
input. Reservation uses the highest applicable input/cache price plus the
full output ceiling; reasoning is already included in output. This is a
coarse, conservative ceiling, not token prediction. It may reserve much more
than a short request consumes. Optimizing it requires a separately verified
request-specific upper bound. Missing prices, additional billing dimensions,
disabled/missing profiles and unmapped identities fail before provider I/O.
An explicit output limit above the verified ceiling is rejected before use.
No production model ceiling has been verified or activated by this work.

Known successful completion settles once in the same transaction as the
journal, usage and outbox. A rate-card amount is accepted only with reported
canonical input/output/cache measurements and is recalculated against frozen
prices. Estimated or missing measurements retain the full hold even if the
calculated cost status is ESTIMATED. A final provider-reported monetary amount
does not require fabricated token measurements. Failed/cancelled or otherwise
uncertain outcomes remain REVIEW_REQUIRED; automatic reconciliation is absent.

Only a PREPARED abandonment releases unspent funds. UNKNOWN holds are not
released by time passage. An actual charge above the reserved amount, or
reported token units above the profile ceiling, freezes further grant
admission and preserves the full debit. Available balance may be negative;
clamping it to zero would hide the overrun. Outbox failure rolls back financial
settlement too. Repeated terminal receipts never reapply a debit or release.
Late adjustments to an already recorded receipt require the pending explicit
adjustment protocol; rewriting the original receipt is rejected.

Insufficient available grant/service funds return HTTP 429 BUDGET_EXCEEDED,
retryable=false, without an invented Retry-After. Missing authority/configuration
or preparation storage failure remains ADMISSION_UNAVAILABLE; neither is
presented as insufficient funds. Budget/project IDs and prices are not exposed
in the public inference contract. The existing error/SSE envelope is retained.

Do not enable the production path merely by inserting arbitrary balances or
turning a profile on. Central grant issuance/import, ownership transfer and
financial audit procedures are still P04 work. Price/model ceilings need
approved capability evidence, and the PG/Redis crash and concurrency tests
must run successfully. V12 is additive; retain its tables and holds during
rollback, drain incompatible writers and use a schema-compatible binary.

## V13 completion deadline and write retry checkpoint

The request deadline and provider-attempt deadline are distinct. Production
attempt policy reserves `GATEWAY_COMPLETION_WINDOW` before the
request deadline. The journal records the original request deadline through
the additive V13 migration; historical rows retain null because the original
deadline cannot be reconstructed from an attempt deadline. Do not rewrite
applied migrations or fabricate historical completion evidence.

Preparation and dispatch use the attempt deadline. V14 splits the completion
reserve as described below; abandonment uses the original request deadline.
Each completion phase has at most
three SQL attempts with full-jitter backoff (25/50 ms ceilings at the default
attempt count). Every retry receives the same phase deadline bounded by the
original request deadline; it only rechecks or stores the same receipt.
It cannot invoke a provider or replay the generation. A matching committed
receipt returns without a second debit or outbox event.

Retry classification examines explicit SQLState before exception subtype or
deeper causes. Connection-class failures and the selected transient states
40001/40P01/55P03/53300/57P01/57P02/57P03 can retry; constraint, authorization,
cancellation and other unlisted states cannot. A state-less transient connection
exception can retry. A state-less generic wrapper delegates to its cause, with
cycle protection. This is a conservative application policy over the
[PostgreSQL SQLState catalog](https://www.postgresql.org/docs/18/errcodes-appendix.html),
not a claim that all driver exceptions are recoverable. Thread interruption
stops retries and preserves the interrupt flag.

Journal transactions use native Spring transaction timeouts and PostgreSQL
LOCAL statement/lock timeouts. Admission subtracts configured pool acquisition
plus validation wait and a two-second cleanup margin; insufficient time fails
before connection acquisition. Inside the transaction, statement timeout is
capped at two seconds and lock timeout at 500 ms. A final margin check prevents
starting a knowingly late commit. LOCAL settings are not global pool settings.
See [Spring JDBC transaction timeout support](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceTransactionManager.html)
and [PostgreSQL JDBC connection parameters](https://jdbc.postgresql.org/documentation/use/).

Production defaults are pool acquisition 1000 ms, validation 250 ms, JDBC
connect/socket timeouts 2 seconds and cancellation-signal timeout 1 second.
Each half of the completion window must exceed acquisition plus validation plus 3 seconds;
incompatible settings fail startup. The effective JDBC network read timeout
must be positive and at most 2000 ms before journal writes. Existing explicit
pool settings may therefore need coordinated configuration changes. These are
initial safety bounds, not an approved latency SLO or a proved hard real-time
deadline across driver, network, commit and rollback. No detached SQL worker,
connection-abort watchdog or background retry is added.

Preparation failure after an earlier attempt is not evidence that the whole
request was unsent. The public message refers to preparing the next attempt;
later-attempt storage failures set retryable=false. JSON and SSE follow the
same rule. Existing business denials retain their own error contract.

The V14 execution journal replaces the legacy aggregate writer on the request
path. Actual native timeout and entire HTTP lifecycle behavior are still not
proved by local tests alone.
Timeout, interruption or exhausted completion writes leave unresolved intent
and holds for reconciliation; they do not authorize automatic release or a
new provider call. Actual pool exhaustion, transport faults, process kill and
HA recovery still require their designated integration gates.

## V14 execution completion checkpoint (P03.3c)

The production `RequestAccountingPort` now records a durable execution terminal
through `PostgresExecutionJournalAdapter`; it no longer runs the synchronous
attempt-usage aggregation query. `PostgresRequestAccountingAdapter` remains
for legacy migration/projection fixtures and is not the production bean.
No best-effort drop or no-op replaces the durable write.

`PostgresExecutionState` creates and locks the OPEN execution root in the same
transaction as reserve + PREPARED. Its identity includes authenticated
tenant/caller, correlation, logical model, operation, streaming flag, original
start and deadline. The reservation's project/service identity is frozen on
the root; later attempts cannot silently switch it. Reserve failure rolls back
the new root too. A failed request with no prepared attempt can record a denial
root without a financial reservation; its project/service fields remain null.
Those fields are not inferred from an untrusted request or treated as a budget.

After the request operation ends, completion locks the owned root and records
outcome/error metadata plus an `execution.recorded` outbox event atomically.
It does not aggregate usage or settle funds again. Duplicate identical terminal
inputs are accepted; conflicting outcomes/identity are rejected. Success
requires the last attempt to have durable completed status and no pending
PREPARED/DISPATCH_INTENT attempt. Failure/cancellation does not erase pending
intent or release uncertain holds. A RECORDED execution cannot start another
attempt, although recovery may still record a previously dispatched attempt.
Execution outcome is not a claim that every attempt's cost is final.

Normal successful requests now require three transactions per attempt plus
one execution-completion transaction. The total completion reserve defaults to
10 seconds, split into two 5-second phase windows. Provider work stops before
the total reserve; attempt completion is capped before the final execution
phase, and execution completion is capped by the original request deadline.
Retries never mint another request deadline. A configured 5-second total is
no longer valid with the default pool settings. These conservative values
require workload/native-fault verification and are not an approved SLO.

Completion storage failure still withholds JSON success or the SSE success
terminal and never reinvokes the provider. A known first-attempt
ADMISSION_UNAVAILABLE with retryable=true is preserved if denial recording
also fails: the provider was not called. The storage failure is retained as a
suppressed diagnostic, and observation reports the same admission outcome.
Later-attempt failure does not gain that retry permission.

The shared outbox now references exactly one attempt or execution. Existing
attempt events retain schema version 1; the new execution event uses a typed
reference without request content. V14's root foreign key is NOT VALID for
historical attempt rows but enforced for new rows. Drain incompatible writers,
apply the additive migration before the new binary, retain journal/holds on
rollback and test mixed-version recovery before deployment. Do not bulk
backfill invented historical roots or validate the old rows without evidence.

**Operational boundary:** P05 worker/projection delivery is not implemented in
this P03.3c change. New requests no longer update the legacy request aggregate
table synchronously; pending outbox events and the execution/attempt journals
are the retained evidence. Do not advertise fresh usage projections or delete
pending events. Worker replay must handle both event subjects, deduplicate and
refresh after late attempt records without another financial settlement.
Public response-ID/policy-version linkage and authorized usage lookup also
remain follow-up work. This checkpoint is not a production activation approval.

## Evidence scope

Local tests cover operation transitions, concurrent observation lifetimes, duplicate observer notifications, and concurrent MVC JSON/SSE requests without internal-field exposure.

The PostgreSQL suite uses the app's actual migrations and includes concurrent same-correlation isolation, four-attempt aggregation and idempotency, immutable request identity, and V5-to-V6 legacy-row preservation. It requires Docker/Testcontainers. Until it runs successfully, migration and database invariants remain unverified; compilation and local tests are not substitutes.

`PostgresAttemptJournalIntegrationTest` adds six actual PostgreSQL scenarios:
terminal/outbox idempotency, outbox-failure rollback, ownership/CAS guards,
invalid permit constraints, concurrent completion writers, and caller rollback
isolation. All six are environment-gated and were skipped in E14. Sixteen
new application JSON/SSE scenarios ran locally for ordering, pre-provider
failure, ambiguous dispatch acknowledgement, cancellation and terminal-failure
propagation. Those fixtures do not prove database durability or crash recovery.

E15 expands this PostgreSQL class to 14 gated scenarios, adding 100 concurrent
30 USD reservations against 100 USD, service-limit rollback, missing authority,
UNKNOWN/estimated measurement holds, immutable period/price, and excess-charge
freeze. These tests still have not run. Local quote/settlement-policy tests,
JSON/SSE propagation and public 429 mapping ran; they are not substitutes for
PostgreSQL transaction or multi-replica evidence.

E16 expands the PostgreSQL class to 18 gated scenarios: pooled LOCAL-setting
restoration, locked-budget timeout, lost terminal commit acknowledgement and
serialization rollback before the same-receipt retry. All remain unexecuted.
The Hikari-backed fixtures compile; this does not validate their SQL or native
timeout behavior. Local completion retry, attempt deadline and JSON/SSE
fallback-preparation assertions are recorded separately in execution-status.md.

P03.3c adds local bean-selection, deadline/no-connection, phase-budget and
first-admission error/observation tests. Six PostgreSQL execution scenarios
expand the journal suite to 24: terminal/event idempotency, outbox rollback,
zero-attempt denial, pending/foreign identity rejection, reserve rollback and
budget identity freeze. They compile but remain skipped without the required
container environment. See execution-status.md for exact executed evidence.
