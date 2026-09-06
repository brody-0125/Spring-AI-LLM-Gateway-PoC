# 테스트 실행 가이드

## 기본 검증

```powershell
.\gradlew.bat clean test :llm-gateway-app:bootJar --no-daemon
```

기본 실행에는 외부 vendor 및 분산 저장소 호출이 포함되지 않는다. `test` profile은 test-only 상태 adapter를 사용한다. 실제 OpenAI, OpenRouter, Bedrock smoke 테스트는 `RUN_EXTERNAL_VENDOR_TESTS=true`와 해당 credential 환경 변수가 모두 있어야 실행된다.

## Kover/Konsist 품질 검증

```powershell
.\gradlew.bat :koverHtmlReport :koverVerify --no-daemon
```

Kover는 전체 모듈의 line coverage를 집계하고 현재 baseline인 68% 미만이면 실패한다. HTML 리포트는 `build/reports/kover/html/index.html`에 생성된다. Konsist는 일반 `test` 실행에 포함되어 헥사고날 모듈 의존성 방향을 검증한다.

## Testcontainers 전체 통합 테스트

Docker daemon이 실행 중인 환경에서 다음을 실행한다.

```powershell
$env:RUN_TESTCONTAINERS = "true"
.\gradlew.bat test --tests "*DistributedAdaptersIntegrationTest" --tests "*SpringAiProvider*IntegrationTest" --no-daemon
```

테스트는 Redis와 PostgreSQL container를 기동해 replica 간 공유 상태를 검증하고, 별도 WireMock container로 OpenAI 호환 provider를 기동한다. PostgreSQL routing override persistence, Redis rate-limit/circuit-breaker atomic state, provider 정상 응답, fallback, SSE 전달을 검증한다. 플래그가 없으면 이 테스트들은 skip된다.

## 테스트 구성

- `llm-gateway-application`: Kotest 단위 테스트. filtering, weighted round-robin, `5xx`/`429` fallback, 인증 오류 no-fallback, stream retry boundary, maximum attempt count, failure classification/policy matrix를 검증한다.
- `llm-gateway-app/ChatCompletionRequestMapperTest`: public request의 provider 공통 옵션, stream 플래그, token alias 충돌, 잘못된 role/model/temperature를 검증한다.
- `llm-gateway-app/GatewayControllerIntegrationTest`: Spring MVC JSON/SSE contract, request ID 생성, null/unknown field 거부, token alias 충돌, error sanitization, logical model/finish reason을 검증한다.
- `llm-gateway-app/GatewaySecurityIntegrationTest`: client/admin 경계와 내부 routing control plane 접근 제어를 검증한다.
- `llm-gateway-app/SpringAiProvider*IntegrationTest`: Testcontainers 기반 provider adapter 전체 통합 테스트다.
- `llm-gateway-app/ExternalVendorIntegrationTest`: `RUN_EXTERNAL_VENDOR_TESTS=true`일 때만 실행되는 실제 vendor smoke 테스트다.
- `llm-gateway-adapters/GatewayAdaptersTest`: guardrail, API key, observability metric/cost 단위 테스트다.
- `llm-gateway-adapters/RedisAdaptersTest`: Redis rate-limit/circuit-breaker 입력 제약과 상태 경계를 검증한다.
- `llm-gateway-adapters/PostgresDeploymentRegistryAdapterTest`: routing override version/중복 ID 제약을 검증한다.
- `llm-gateway-adapters/PromptMapperTest`, `ChatResponseMapperTest`, `ProviderFailureMapperTest`: Spring AI provider request/response/failure 매핑을 순수 단위 테스트한다.
- `llm-gateway-adapters/DistributedAdaptersIntegrationTest`: Redis/PostgreSQL Testcontainers 기반 분산 상태 adapter와 concurrent update/token allocation을 검증한다.

## 인수 조건별 매트릭스

| 영역 | 단위 테스트 | MVC 통합 테스트 | Testcontainers/외부 통합 |
| --- | --- | --- | --- |
| 요청 계약·입력 검증 | mapper 옵션/role/model/token 충돌 | JSON 400, unknown field, null content | provider request 전달 |
| 라우팅·fallback 정책 | 분류기/정책/시도 경계 | fallback error contract | provider fallback, routing registry |
| streaming | response/chunk mapper | SSE model/finish reason | provider SSE 전달 |
| 관측성·비용 | Micrometer metric/cost recording | request ID/header contract | replica 간 상태 검증 |
| 보안·제어면 | auth/guardrail 경계 | client/admin 접근 제어 | PostgreSQL override persistence |
| 분산 rate-limit/circuit-breaker | 입력/상태 경계 | gateway rate-limit contract | Redis 원자성·동시성 |

테스트 매트릭스에서 외부 vendor smoke와 Docker 기반 테스트는 실행 조건이 충족되지 않으면 skip된다. CI의 배포 전 검증에서는 Docker daemon과 `RUN_TESTCONTAINERS=true`를 필수로 설정하고, credential이 있는 환경에서만 외부 vendor suite를 별도 job으로 실행한다.
