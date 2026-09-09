# Chat command and stream ownership

## Commands

Both paid Chat operations use command input ports: `CompleteChatCommandIn` for JSON and `StreamChatCommandIn` for streaming. The matching application services delegate to typed Chat operations. Query input ports remain for non-generating reads. The HTTP endpoint and response schemas are unchanged; no compatibility alias for the old generating query port remains.

## Stream ownership

`CloseableStream<T>` is a single-consumer, explicitly closeable pull stream. Internal sequence builders are an implementation detail, not the lifetime contract. Consumers must use a close scope:

```kotlin
streamChatCommandIn.stream(request, context).use { stream ->
    stream.forEach { event -> consume(event) }
}
```

Early operators such as `take(1)` do not automatically close their source. Keep them inside `use`. A handle permits only one iterator and does not permit concurrent pulls.

The MVC worker owns the request handle; the service, operation, attempt, and provider handles own their children. EOF, error, explicit close, HTTP completion/timeout/error, and consumer disconnect close the local ownership chain. The worker reference is published before it starts, and a handle created after HTTP cancellation is immediately closed.

`ManagedStream` records close ownership atomically, closes owned resources in reverse order, and rejects/closes resources registered after cancellation. Cleanup does not depend on resuming a suspended sequence's `finally` block. A blocked local reader receives an interrupt; clients that ignore interruption can still run until their own operation returns. A late resource is closed when it is registered.

The provider adapter owns the SDK's closeable Java stream with a prefetch of one. Closing it cancels the local subscription. SDK reactive types do not cross the adapter boundary. The application remains MVC and virtual-thread based.

## Observation ownership and thread handoff

Request observers now receive `RequestObservationContext`: execution metadata, logical model and streaming mode, not messages or tool payloads. `RequestObserverPort.start` returns an `ObservationHandle<RequestOutcome>`; `AttemptObserverPort.start` returns an `AttemptObservationHandle`. Typed terminal outcomes are unchanged. The caller owns one handle per execution/attempt; starting a handle is not an identifier-based lookup or an idempotent create operation.

The Micrometer adapter owns the actual observation and its terminal guard. No global request/attempt observation map or first-token map remains. Repeated or racing terminal notifications record once; late first-token notifications cannot reopen a finished handle. Failure in a first-token callback does not convert a successful provider stream into a model failure. Exceptional paths after the provider call still stop the observation. A short lock orders first-token/terminal state only; logging and observation handlers run outside that lock to avoid monitor-held I/O on JDK 21.

`ObservationContextPort.capture` returns a captured context, not an already-open scope. `MicrometerObservationContextAdapter` bridges the installed observation registry. The MVC controller captures the inbound context before starting the SSE virtual thread, and `VirtualThreadDeadlineOperator` captures it before each provider worker. A scope opens and closes on the same worker and restores that worker's previous context, including when a scope callback throws. Capturing an empty context clears an unrelated worker observation during the operation and restores it afterwards.

`RequestLifecycleOperator` owns the request handle. `ObservedStream` opens a request scope around each iterator creation, pull and close; a suspended sequence never leaves a thread-local scope active between consumer calls. Attempt operators open the attempt scope around provider work, so nested deadline workers inherit the attempt rather than an unrelated request. Cross-thread stream cancellation can stop an observation without closing a scope belonging to another thread.

Observation failures are best effort and do not replace business results. This rule is for telemetry, not permission to drop financial journal writes. Existing accounting persistence remains a separate, unfinished P03 obligation.

The local regression suite exercises real request/attempt operators and Micrometer handlers, parent identity across provider virtual threads, per-pull restoration, cross-thread early close, scope callback failures, deadline cancellation and terminal idempotency. These tests do **not** prove inbound W3C propagation, exported OTel span relationships, SDK reactive-thread propagation, MDC restoration by a tracing bridge, Collector durability or sampling behavior. P10 must install/configure and verify those actual integrations. The legacy trace-ID string extraction in the controller remains to be replaced there; it is not propagation evidence.

### Usage and cost telemetry

Only known token quantities are recorded. A missing output total does not create an output-token zero sample. Explicit zero remains a valid measurement. `llm.gateway.usage.units` preserves the finite usage type/unit/source dimensions; it does not label arbitrary variants, request IDs or keys. Cache and reasoning are detail categories, not additional tokens to sum over gross input/output totals. Partial per-type measurements remain visible in component metrics without pretending the gross type total is complete.

`llm.gateway.usage.missing` counts attempts with empty usage or at least one explicitly unknown component. An omitted optional detail is not inferred to be zero. Attempt logs use an unavailable marker for unknown totals and amounts.

Cost summaries exclude UNKNOWN amounts and distinguish known partial subtotals from complete estimates/reports through `status` and `source` tags. The cumulative cost counter excludes PARTIAL/UNKNOWN outcomes. Update dashboard queries that previously selected these cost meters without completeness/source semantics; do not sum partial subtotals into complete-cost series. Metrics use floating-point samples for operations and are not a replacement for the exact-decimal, unsampled ledger.

## Execution outcomes and remaining limits

Early closure records local cancellation, not successful inference. Attempt terminal notifications are guarded against duplicate close/error paths. Request lifecycle cleanup runs on explicit close as well as full consumption. Partial usage observed before cancellation uses the execution's captured pricing.

Local subscription cancellation is not evidence that the vendor stopped executing or billing. Cancellation does not manufacture circuit success or release an unconfirmed remote probe as successful. Durable journal, SUSPECT capacity, reservation settlement, and reconciliation remain P03/P05/P11 work. Existing accounting writes are still best effort; this checkpoint does not make them crash safe.

Independent concurrent close calls can observe a handle already closing; they do not wait for arbitrary third-party cleanup code to finish. The implementation does not forcibly terminate non-cooperative vendor code. Bounded provider pools, write-stall limits, graceful drain, and operational cancellation bounds still require P12/P14 verification.

## Evidence scope

- Application tests cover EOF, early close, source/cleanup errors, close-before-consumption, repeated close, a blocked reader, and late resource registration after ignored interruption.
- Chat tests cover early termination and deadline cleanup with one local terminal record; previous JSON/SSE retry, snapshot, and error contract regressions remain included.
- Adapter tests exercise actual SDK stream subscription cancellation and blocked-reader release using an in-process model fixture. No vendor network call occurs.
- MVC integration uses an incremental HTTP client, closes the body after the first frame, and observes server-side cleanup before the finite test source could finish normally.

These tests are not a substitute for remote cancellation confirmation, real PG/Redis fault injection, slow-consumer load, or multi-replica drain tests. Those acceptance gates remain open.
