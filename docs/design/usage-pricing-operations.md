# Usage and pricing semantics

## Configuration and provenance

- Omitted/empty per-1k prices are null (unknown). Explicit zero is a known free rate. The same conversion is used by configured pricing and explicit registry seed.
- Negative prices, values outside NUMERIC(24,18) per-token range, and values requiring per-token rounding are rejected. The seed transaction rolls back instead of silently recording a free price. The smallest supported configured per-1k increment is 0.000000000000001 USD.
- A nonzero quantity without its price yields a warning. If no positive-quantity charge can be calculated, cost is UNKNOWN with null amount; zero-quantity details do not make it known. If some charges are calculable and others are missing, cost is PARTIAL with the known subtotal. An explicitly free rate for a known quantity remains a known zero. Unavailable usage or a missing captured snapshot remains UNKNOWN.
- Rate-card multiplication has source RATE_CARD and status ESTIMATED when complete. REPORTED is reserved for amounts actually reported by a provider. Provider-reported amount ingestion and reconciliation are not implemented yet; the two sources must not be added together.
- Source/status are internal accounting metadata. No inference cost header, extra JSON field, or public endpoint was added.

## Provider normalization

The canonical Chat input count includes regular input plus cache-read and cache-write input. The output count includes reasoning output. The calculator subtracts cache categories from regular input and does not charge reasoning a second time.

- OpenAI-compatible native usage preserves cache-read and reasoning details. The adapter uses the pinned SDK's typed CompletionUsage, without passing native objects to inner modules.
- Converse input is uncached input; the adapter adds its separate cache counters to obtain canonical total input. Example: 10 regular + 100 cache-read + 20 cache-write + 5 output becomes input 130, output 5, total 135; regular input pricing still uses 10.
- EmptyUsage means absent; an explicitly supplied zero-token usage is present. JSON and SSE use the same normalization. Negative counts and overflow are rejected rather than clamped into a valid provider response.

Converse counter semantics were checked against [AWS token counting](https://docs.aws.amazon.com/bedrock/latest/userguide/quotas-token-burndown.html). Local Spring AI 2.0.1 bytecode confirms that OpenAIChatModel retains typed native CompletionUsage, that missing OpenAI usage uses EmptyUsage, and that BedrockProxyChatModel passes separate input/cache counts to DefaultUsage. In-process fixtures verify this gateway mapping; they are not live vendor tests.

## Migration and release restrictions

V7 makes legacy deployment configuration prices nullable and preserves finer per-1k precision. It translates legacy deployment zero sentinels to null but does **not** change immutable versioned rate cards: a zero already present in a rate card stays zero. V8 adds nullable attempt cost_source; historical rows stay null because their old status does not prove provenance. V9 expands attempt/request amount columns to NUMERIC(30,18), retaining their existing 12-integer-digit range. Existing recorded values/statuses are not retroactively corrected.

These migrations have compiled Testcontainers fixtures but have not run against PostgreSQL in the current environment. Do not deploy them on that evidence. Before an approved migration, verify backup/restore and table rewrite/lock duration on a production-sized clone; drain incompatible old readers/writers. Old code expecting non-null deployment prices is not mixed-version compatible. Do not undo V7 by coercing new unknowns to zero or automatically roll back to an old writer. Follow the version/DB-role release gate in P13.

The token calculator now retains all 18 fractional digits of the rate card and rejects precision loss instead of rounding. One token at 0.000000000000000001 USD therefore remains nonzero, and aggregation preserves two such attempts as 0.000000000000000002 USD. The old 12-decimal calculation could lose these values (F014); migration cannot reconstruct already rounded-away charges. Reconciliation requires evidence, not automatic backfill. SQL persistence and aggregate fixtures still await a real PostgreSQL run.

## Typed components and cost lines

`Usage` now owns an immutable component list. Each `UsageComponent` has a `UsageKey(type, variant)`, an integer quantity or null, a unit determined by type, and a measurement source (PROVIDER_REPORTED, ESTIMATED, UNKNOWN). Null requires UNKNOWN source; explicit zero does not. Duplicate keys, invalid variants, negative quantities, and overflowing per-type totals are rejected.

Token totals, cache categories, and reasoning remain distinct types. Rerank quantities are QUERY or DOCUMENT_BLOCK; tool usage is INVOCATION. The calculator does not turn documents into tokens or invent a document-block size: the API-specific adapter must report the billing unit prescribed by its supported profile. Variant names identify bounded billing profiles, such as cache-write duration or hosted-tool class, never arbitrary caller labels. Unqualified cache categories and qualified variants must partition their quantities, not repeat the same aggregate.

`PricingSnapshot` owns immutable `ComponentPrice` values. Lookup uses the exact type/variant key, without a fallback from an unknown variant to an unqualified price. Reasoning cannot have a separate price or cost line because it is already included in output. For INPUT_TOKENS, the usage component is the gross canonical input; its cost line quantity is the regular input remaining after cache categories. If cache usage is unknown, that regular quantity is also unknown rather than billed as if all input were uncached.

`CostCalculator` computes every chargeable component through the same exact 18-decimal path. `Cost.lines` holds immutable quantities, unit prices, amounts, status, and source. Unknown line amounts are null, while the overall stored subtotal sums only known lines. Token-specific amount fields are compatibility projections; total cost also includes non-token lines. Source of a measured quantity is separate from source of the calculated monetary amount.

Cumulative stream merge operates by key: unknown does not erase known, reported quantities replace estimates (even when the report is lower), and repeated same-source cumulative values use max rather than addition. This is not the protocol for post-terminal corrections. The stream accumulator starts empty, without invented token categories. Missing ProviderResponse usage also starts unknown. Inference JSON/SSE only emits the existing token usage object when both gross input/output totals are complete.

## V10 storage and outstanding integration

The four existing unqualified token rates remain in deployment_pricing. V10 adds component_pricing for other types and qualified cache/tool variants under the same deployment/version foreign key. Constraints prevent duplicate ownership of the four old rate keys and prevent pricing reasoning twice. The catalog assembles one immutable snapshot from both stores inside the existing execution snapshot transaction. The seed command still seeds only its supported token configuration; additional managed profiles belong to the later publish workflow, not a new public endpoint.

Attempt insert, usage components, and cost lines run in the same existing transaction. Child writes use bounded JDBC batches. Only a newly inserted attempt writes children; repeated terminal recording does not change or duplicate them. `component_schema_version=1` distinguishes newly captured data from historical or old-writer records (null); empty historical child tables must not be interpreted as zero consumption. No historical usage source, cost line, or lost precision is backfilled. Parent retention cascades to its children and must remain gated by the P05 journal/outbox retention rules.

The PostgreSQL fixture covers mixed units, null quantity/amount, source, version, request subtotal, duplicate delivery, and rollback after a child constraint fails. It has not run in the current environment. Migration and old-writer handling require the same approved PG verification and release controls described above.

Observation event/context contracts, the complete file-declaration gate, actual provider-reported monetary amount ingestion/reconciliation, and journal/budget integration remain outstanding. No rerank/tool endpoint or vendor capability is advertised by these domain types. Monetary columns retain the existing 12-integer-digit limit; P03 admission must validate reservation/aggregate bounds before dispatch, and terminal correction must not use cumulative max as an accounting reconciliation rule.
