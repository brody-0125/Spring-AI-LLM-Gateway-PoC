# Spring AI LLM Gateway

A Spring MVC LLM gateway built on JDK 21 virtual threads and Spring AI. Clients select a logical model group, while the gateway selects an available deployment across OpenAI, AWS Bedrock Converse, and OpenRouter.

## Features

- Provider-neutral `POST /v1/chat/completions` with JSON and Server-Sent Events (SSE)
- Weighted round-robin routing with streaming-capability filtering
- Bounded execution with same-deployment retry and alternative-deployment selection
- Provider timeouts and deployment circuit breakers
- Bearer-token client, tenant, and administrator identification
- Token-bucket rate limiting and configurable input guardrails
- Request tracing, structured logging, and Micrometer latency, token, failure, alternative-selection, and cost metrics
- Prometheus metrics at `/actuator/prometheus` and optional OTLP trace export on the internal management port
- PostgreSQL-backed deployment registry and routing overrides
- Redis-backed distributed rate-limit and circuit-breaker state
- OpenAPI 3.0.3 contract at `GET /v3/api-docs.yaml`

## Run locally

Production configuration requires PostgreSQL and Redis. The application fails to start when either dependency is unavailable.

```powershell
$env:GATEWAY_CLIENTS = "client-key=bff:tenant-a"
$env:POSTGRES_JDBC_URL = "jdbc:postgresql://localhost:5432/llm_gateway"
$env:POSTGRES_USERNAME = "llm_gateway"
$env:POSTGRES_PASSWORD = "<postgres-password>"
$env:REDIS_HOST = "localhost"
$env:REDIS_PORT = "6379"
$env:MANAGEMENT_SERVER_PORT = "8081"
$env:OPENAI_API_KEY = "<openai-api-key>"
$env:OPENAI_MODEL = "gpt-4o-mini"

.\gradlew.bat :llm-gateway-app:bootRun --no-daemon
```

Replace all placeholder values. Inject credentials through a secret manager or Kubernetes Secret.

The management server defaults to port `8081`; keep it on an internal network and expose only the endpoints required by the operations platform.

`GATEWAY_CLIENTS` uses the format `api-key=caller:tenant[:admin]`, with comma-separated entries. Client authentication is enabled by default.

Supported provider settings:

- OpenAI: `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_MODEL_GROUP`, `OPENAI_BASE_URL`, and `OPENAI_*_COST_PER_1K_USD`
- OpenRouter: `OPENROUTER_API_KEY`, `OPENROUTER_MODEL`, `OPENROUTER_MODEL_GROUP`, `OPENROUTER_BASE_URL`, and `OPENROUTER_*_COST_PER_1K_USD`
- AWS Bedrock: `GATEWAY_BEDROCK_ENABLED`, `BEDROCK_MODEL`, `BEDROCK_MODEL_GROUP`, `AWS_REGION`, and `BEDROCK_*_COST_PER_1K_USD`

OpenAI and OpenRouter support provider `TIMEOUT` settings. Bedrock also supports connection, read, and connection-acquisition timeouts. Provider client retries are disabled so the gateway can enforce the total execution limit. Gateway execution settings are controlled by `GATEWAY_MAX_TOTAL_ATTEMPTS`, `GATEWAY_MAX_RETRIES_PER_DEPLOYMENT`, `GATEWAY_MAX_FALLBACKS`, `GATEWAY_RETRY_BACKOFF_INITIAL`, `GATEWAY_RETRY_BACKOFF_MULTIPLIER`, and `GATEWAY_RETRY_BACKOFF_MAX`.

## Deployment configuration

Register multiple deployments under a provider and model group when required:

```yaml
gateway:
  providers:
    openai:
      deployments:
        - id: openai-primary
          model: gpt-4o-mini
          model-group: default
          weight: 3
        - id: openai-secondary
          model: gpt-4.1-mini
          model-group: default
          weight: 1
          input-cost-per-1k-usd: 0.0004
          output-cost-per-1k-usd: 0.0016
          cache-read-input-cost-per-1k-usd: 0.0002
          cache-write-input-cost-per-1k-usd: 0.0005
```

The single-deployment fields create one default deployment per provider.

## Runtime routing control

An administrator can enable or disable deployments and change their weights without restarting the application:

```powershell
Invoke-RestMethod http://localhost:8080/internal/v1/routing `
  -Method Put `
  -Headers @{ Authorization = "Bearer <admin-key>" } `
  -ContentType "application/json" `
  -Body '{"overrides":[{"id":"openai-primary","enabled":false},{"id":"openai-secondary","weight":2}]}'
```

PostgreSQL stores deployment metadata, runtime overrides, and snapshot versions. Redis uses an atomic Lua token bucket for rate limiting and a Lua-based open/half-open circuit breaker. Each alternative deployment selection re-evaluates the current registry and circuit state. Provider credentials and model clients are loaded at startup; runtime credential replacement is not exposed.

## HTTP contract

- `POST /v1/chat/completions`: JSON response or SSE when `stream=true`
- `GET /v3/api-docs.yaml`: OpenAPI v3 contract

The source contract is [`llm-gateway-contract/src/main/resources/openapi.yaml`](llm-gateway-contract/src/main/resources/openapi.yaml). It exposes only the common provider-neutral request fields. Message content is currently limited to strings; tool calling, multimodal content, and provider-specific fields are intentionally excluded. Streaming errors are sent as SSE events named `error`; successful streams end with `data: [DONE]`.

The public contract does not expose provider cost details. Usage is returned when provider metadata is available and omitted otherwise. Usage and cost accounting are stored internally per provider attempt, with idempotent PostgreSQL writes and versioned pricing snapshots.

Supported request options include `temperature`, `max_tokens`, `max_completion_tokens`, `top_p`, `stop`, and `stream`. `max_tokens` and `max_completion_tokens` are mutually exclusive. Usage is always collected internally for cost tracking.

Supported headers:

- Required: `Authorization: Bearer <client-key>`
- Optional: `X-Request-Id`, `traceparent`
- Response correlation: `X-Request-Id`
- Rate-limit responses: `Retry-After`

## Modules

```text
llm-gateway-contract   HTTP DTOs and OpenAPI contract
llm-gateway-core       Framework-free primitives
llm-gateway-domain     Framework-free gateway model
llm-gateway-application Inbound/outbound ports, operations, operators, and policies
llm-gateway-adapters   Spring AI provider and infrastructure adapters
llm-gateway-app        MVC inbound adapters and composition root
```

The core and domain modules have no Spring or provider SDK dependencies. The gateway uses Spring MVC and virtual threads; WebFlux and Reactor are not part of the gateway execution model.

## Verification

```powershell
.\gradlew.bat clean test koverHtmlReport koverVerify --no-daemon
.\gradlew.bat :llm-gateway-app:bootJar --no-daemon
```

The test suite includes Kotest unit tests, MVC integration tests, and WireMock/Testcontainers provider tests for success, fallback, and streaming paths. Testcontainers and external-vendor tests are opt-in through `RUN_TESTCONTAINERS=true` and `RUN_EXTERNAL_VENDOR_TESTS=true`.

## License

MIT License
