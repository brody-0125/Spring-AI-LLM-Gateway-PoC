# 전사 Gateway 확장 인수 조건

기준: 2026-09-09, 현재 커밋 `ffbd6c7`. [제품·재구성 설계](enterprise-gateway-evolution.md)의 S0–S7에 대응한다.
모든 항목은 **향후 구현의 통과 조건**이다. 이번 설계 검토에서 실행·통과한 테스트 목록이 아니다.

2026-09-09 용어 정정: 제품은 미디에이터(중개) 시스템이다. 이미지 생성·전달로 잘못 확장한 A29–A31/A61은 철회했다. 참조 ID를 재사용하지 않으며, 아래 65개 ID 중 활성 인수 조건은 61개다. 철회 항목은 출시 gate가 아니다.

| ID | Given / When | Then: 관찰 가능한 통과 조건 | 검증 도구·범위 |
| --- | --- | --- | --- |
| A01 | 서로 다른 project 및 동시 요청이 같은 X-Request-Id 사용 | correlation은 유지되지만 execution/원장/trace 수명이 분리됨; 다른 project 데이터 조회 불가 | Kotest + MVC + PG Testcontainers |
| A02 | 후보 3개 모두 half-open, 하나만 선택 | 미선택 2개 probe는 미점유; 선택 permit은 동시 1개, owner/fence 검증 | Redis Testcontainers, 두 독립 client |
| A03 | 서로 다른 startup 설정의 replica가 rolling deploy | active registry의 enable/priority/weight/가격을 덮어쓰거나 disable하지 않음 | PG + 3 replica 통합 |
| A04 | 동일 deployment 2회 retry 후 다른 deployment로 fallback | initial=1, retry=2, fallback=1; request 비용은 네 attempt 합계 | Kotest + PG |
| A05 | 재계획 도중 정책 publish | 기존 execution은 같은 snapshot/pricing 사용; emergency disable만 즉시 적용 | application + snapshot 통합 |
| A06 | 신규 API DTO와 provider SDK 타입 추가 | core/domain/application에 Spring/Reactor/SDK import 없음; QueryIn/CommandIn·파일당 선언 규칙 통과 | Konsist + Gradle dependency 검사 |
| A07 | 공개 OpenAPI의 모든 request/response/example 및 생성 오류 | OpenAPI parser/linter 통과, fixture schema validation, unknown field/option 거부; Java BFF/Python FastAPI의 자동 retry 억제·deadline 계약 확인 | Kotest + OpenAPI validator + SDK contract |
| A08 | cancel, timeout, early iterator termination, provider throw | local stream close 1회; 원격 종료 확인 시 permit release, 불명 시 SUSPECT 유지; 중복 종결 없음 | Kotest + HTTP stream 통합 |
| A09 | PG reserve 불가 | provider 호출 0회, 503 ADMISSION_UNAVAILABLE, budget 초과로 오분류 안 함 | PG 중단/Toxiproxy + WireMock counter |
| A10 | 잔액 100, 예약 단위 30의 100개 동시 요청 | scope 전체에서 최대 3개 승인; 4번째 외부 call 없음 | PG Testcontainers, 다중 connection barrier |
| A11 | 배분 grant/project/service 중 한 한도 초과 또는 transaction 중단 | 소유 PG의 reservation+attempt 원자 rollback; 부모 배분 초과는 A47로 검사; 이행 모드 다중 scope 잠금도 검증 | PG 동시성·failure injection |
| A12 | PG reserve 후 Redis 거절·프로세스 강제 종료 | provider 미호출; PREPARED 회수 및 Redis permit 복구; 이중 release 없음 | 2 replica + PG/Redis + kill |
| A13 | provider 성공과 terminal commit 사이 PG 장애·process kill | 미종결 intent와 hold가 남음; outcome UNKNOWN 또는 REVIEW_REQUIRED; LLM 자동 재실행 0회 | HTTP stub + PG 장애 + kill |
| A14 | terminal+outbox commit 직후 worker kill | event 재전달; ledger/budget는 한 번만 반영 | PG outbox integration |
| A15 | sink 처리 후 ack 전 kill, 동일 event 10회 전달 | consumer receipt unique로 projection 1회; poison event 재처리 가능 | Worker + PG + 가짜 sink |
| A16 | UTC 월말 stream·late settlement·가격 변경 | 예약 기간/가격 고정; adjustment 추적; UNKNOWN을 zero 처리하지 않음 | 고정 Clock + PG |
| A17 | output 실제 비용 초과·usage 누락·late provider cost | 과금 차액 보존, hold/검토 상태 유지, 다음 admission 차단; reported+estimated 중복 합산 없음 | CostCalculator + settlement fixture |
| A18 | 키 생성·조회·export·오류·로그 | 원문은 생성 시 한 번만, 이후 digest/원문 노출 없음 | MVC + DB inspection + log capture |
| A19 | 두 keyring version으로 rotation 후 구 key revoke | project budget 공유; TTL/invalidation 유실에도 정책 시간 내 폐기 반영 | 3 replica + Redis 장애 |
| A20 | service key로 admin 및 타 project usage 호출, usage:read 유무 변경 | admin·타 project 거부; 허용된 read-only usage만 가능, ID 변경으로 scope 우회 불가 | MVC security integration |
| A21 | 동시에 policy publish, capability 위반·route cycle·space 충돌 | stale version 409; 잘못된 revision은 active가 되지 않음; 변경+audit 원자성 | Admin API + PG |
| A22 | notification 유실·replica 재시작·client 생성 실패 | poll로 수렴; 준비 전 swap 금지; LKG/drain 유지; rollback은 새 version | 3 replica integration |
| A23 | 프로젝트 예산 소진 상태에서 비용·response 조회/삭제 | 허가된 비과금 조회·삭제는 가능; 신규 생성만 차단 | Admin/Public MVC |
| A24 | embedding batch 분할, 응답 순서 뒤섞임 | 원본 index와 dimension 보존; 부분 성공 공개 없음; subattempt 전부 원장화 | provider fixture + PG |
| A25 | 같은 dimension이지만 다른 embeddingSpaceId 후보 | routing 및 publish에서 거부; 허용 space끼리만 fallback | Kotest routing |
| A26 | rerank top_n, 중복 index, NaN, 동점 score | 범위·유한값·index 검증; 동점 안정 순서; provider malformed 결과를 client 오류로 오분류 안 함 | Kotest + WireMock |
| A27 | Bedrock Rerank 지정 model/region | 실제 응답·과금단위·권한 오류 mapping 확인 후 capability 활성화 | gated vendor smoke |
| A28 | token/cache/reasoning/rerank/tool usage | unit별 가격 계산 및 포함관계 중복 방지, null 가격과 무료 구별 | table-driven Kotest |
| A29 | 철회: 이미지 결과 크기·형식 전용 검사 | 용어 오해에서 파생; 필수 범위 아님 | 실행·출시 gate 제외 |
| A30 | 철회: 이미지 생성 replay 계약 | 용어 오해에서 파생; 필수 범위 아님 | 실행·출시 gate 제외 |
| A31 | 철회: 이미지 URL 저장·전달 계약 | 용어 오해에서 파생; 필수 범위 아님 | 실행·출시 gate 제외 |
| A32 | Responses tool calls·opaque reasoning·refusal·incomplete | item/call ID 및 의미 보존; 지원 안 되는 profile은 호출 전 400 | 공식 schema 기반 JSON fixture |
| A33 | Responses stream 정상/오류/분할 chunk/중단 | event 순서·terminal 정확, 잘못된 DONE 추가 없음; 공개 후 fallback 0회 | SSE incremental client + WireMock |
| A34 | stored Responses chain, 프로젝트 격리, expiry, delete | 권한·affinity·retention 보장; opaque item 교차 provider replay 금지; stateless profile은 저장 0건 | PG/Redis + MVC + native fixture |
| A35 | least-busy 3 replica, process kill, 늦은 completion | 정상 authority의 허가 상한 준수; lease 만료만으로 remote 종료 처리 안 함; SUSPECT 차감·stale release 차단 | Redis Lua + 다중 replica + 원격 생존 stub |
| A36 | input-heavy/output-heavy 요청, 무료·미확인 가격 | 요청 전체 예상 비용 순; hard hold는 상한 사용; UNKNOWN이 최저가로 선택되지 않음 | parameterized routing/cost tests |
| A37 | 동일 provider 계정을 공유하는 두 deployment quota | 공유 pool 한도 준수; quota 여유 있는 다른 pool 선택; project budget 우회 불가 | Redis+PG race integration |
| A38 | quota inspect 후 다른 replica가 먼저 acquire | 점유 경합 재선택, 미전송 예약 보상, bounded 횟수; provider error/circuit 실패로 집계 안 함 | barrier race test |
| A39 | adaptive 표본 없음·stale·극단값·latency 악화 | 정적 fallback·score clamp·hysteresis 적용; shadow 외부 호출/예약 0회 | deterministic fake telemetry + replay |
| A40 | Redis restart/flush/failover와 active attempt | affected pool admission 잠금·PG generation 검증·active/suspect 복구; free 초기화 금지; failover 감지 전 초과 위험과 durable capacity profile 차이를 기록 | 실제 Redis 복제 topology + fault injection |
| A41 | BFF traceparent와 virtual thread, 동시 SSE | SERVER→attempt parent 관계 확인; context leak 없음; metric 중복 0 | OTel in-memory exporter + HTTP |
| A42 | OTLP Collector 수신·재시작·backend 장애 | trace/log 연계, disk queue replay, bounded memory, inference 지속, accounting 독립 | Collector container + sink |
| A43 | sampling·1만 project/key·비밀 payload | ledger 100% 대상 유지, 고유 ID metric label 0개, 원문/키 로그 0개 | metric registry 및 exporter payload 검사 |
| A44 | JSON/SSE/Embeddings/Rerank/Responses 혼합 부하 | latency·error·memory·pool·reject·원장 누락 보고, SSE TTFT 실시간 측정 | k6 + incremental streaming probe, 3 replica |
| A45 | SIGTERM drain, worker rollout, PG 장애 복구 | 새 admission 중단; 기한 내 기존 요청 종결; pending intent/outbox 복구; 중복 bill 없음 | staging chaos scenario |
| A46 | S0–S7 출시 판정 | 단위·MVC·필수 containers·schema·security·k6·profile smoke 결과와 runbook 링크가 release evidence에 존재 | CI release gate |
| A47 | 조직 예산 100, 독립 owner 두 곳이 grant 60을 동시에 요청 | 한 번만 배분; 동일 grant 재전달/import 10회에도 추가 지출 권한 0; 정상 request reserve가 조직 행을 잠그지 않음 | 중앙/소유 PG Testcontainers + barrier + lock trace |
| A48 | 중앙 발급 commit 후 응답 유실·import 전후 kill | 같은 grant/transfer ID로 수렴; 임의 재발급/TTL 회수 없음; 금전 보존식 검증 | PG 2개 + process kill + state-machine Kotest |
| A49 | unused 반환의 freeze/parent credit/local ack 각 지점에서 kill | 중복 credit 없음; 불명 transfer는 사용 불가, held/UNKNOWN 반환 0; receipt 기준 합계 보존 | PG 2개 + failure injection |
| A50 | 이미 배분된 액면 미만으로 한도 감소·기간 종료·늦은 청구 | 동결·회수/차단 확인 전 적용 완료로 응답 안 함; 이전 기간 hold 유지; 초과 조정 숨김 없음 | Admin + PG + Clock |
| A51 | 관리 PG 중단, local execution PG는 정상, auth lease 만료 | 기존 grant/유효 권한까지만 진행; 만료 후 신규 호출 0; 이미 실행한 요청 정산 가능 | 독립 PG + 다중 replica |
| A52 | 한 cell PG 장애와 다른 cell 정상, quota 계정 공유/분리 각각 | 독립 자원만 장애 격리; 공유 authority의 영향은 명시; cell마다 계정 quota 중복 부여 안 함 | 2 cell staging + provider counter |
| A53 | 한 project가 stream/batch 과점·autoscale | class/project 상한 및 예약 용량 배분 합계 준수; 정상 project의 승인 SLO 검증; 기존 stream 강제 선점 없음 | open-arrival k6 + incremental client |
| A54 | 큐 포화·chunked/압축 대형 입력·JSON 과다 중첩 | byte/parse/queue 한도 적용; 거절 요청의 provider 호출·금전 hold 0; 전체 대기 항목도 bounded | raw HTTP fixtures + heap 관찰 |
| A55 | provider 장애로 다수 요청 retry/fallback, SDK도 retry 시도 | request 상한과 pool 추가 시도 예산 모두 준수; retry가 allowance 재발행 안 함; destination 기존 traffic 보호 | fake Clock + 다중 replica + BFF SDK |
| A56 | deployment/project 수 증가 및 전 replica cold restart | 정상 요청의 전체 registry SELECT 0; 후보/Redis round trip bounded; single-flight/jitter, 미준비 replica readiness 닫힘 | query counter + load + startup integration |
| A57 | project 이동 도중 분할·source 지연·destination 재시작 | source 동결/이관 확인 전 destination 신규 허가 0; grant·key tombstone 중복 없음; pending/affinity 소유 보존 | 2 cell + PG + 네트워크 fault |
| A58 | outbox sink 장기 중단·poison event·disk watermark | 다른 project 전달 진행; terminal 저장 여유 보존, 새 admission 선제 제한; 복구 처리율·oldest age 수렴 | worker integration + 제한 disk staging |
| A59 | partition 교체·receipt 만료 경계·이전 event replay | dedup 범위 유지; pending hold/transfer 삭제 0; 정책 기간 내 재전달 정산 중복 0 | PG migration + event replay |
| A60 | 동일 correlation 요청 및 projection 지연·성공 ID 없는 UNKNOWN | usage에서 프로젝트 범위 목록/확정 상태 조회; not-found를 미실행·자동 재생성 근거로 사용 안 함; admin 권한 불필요 | usage OpenAPI + Java/Python consumer fixture |
| A61 | 철회: 이미지 artifact 보관·복구 | 용어 오해에서 파생; 객체 저장소 도입을 요구하지 않음 | 실행·출시 gate 제외 |
| A62 | 느린 SSE 소비자·adapter 취소 불확실·긴 stream drain | outbound byte/write stall 상한; local handle 정리와 SUSPECT/hold 수명 분리; unrelated class 유지 | incremental client + provider stub + JFR |
| A63 | synchronous PG failover·구 primary 분할 및 async DR 복원 | 승인된 grant/intent 복원 검증; old writer fenced; 손실 가능 owner는 reconciliation 전 신규 허가 0 | 실제 PG HA staging; 단일 container restart로 대체 불가 |
| A64 | N/N+1 app/worker rollout, backup restore, DB 역할별 접근 | event reader 호환·복구 완료; data plane 정책 변경/원장 삭제 거부; incompatible replica 미노출 | migration/restore + DB ACL integration |
| A65 | 외부 필수 guardrail 장애·timeout·출력 stream 검사 | 해당 profile fail-closed, 별도 자원 상한·deadline; 모델 circuit 오염 0; 검사 전/후 공개 경계 준수 | guardrail HTTP stub + MVC/SSE |

테스트 전략: Kotlin 정책·상태 전이는 Kotest, 경계는 MVC/HTTP fixture, 분산 원자성·crash recovery는 Testcontainers의 실제 PostgreSQL/Redis를 사용한다. 3 replica 테스트는 각각 별도 JVM으로 실행하고 in-memory adapter로 대체하지 않는다. container suite가 실행되지 않은 job은 성공한 release gate로 취급하지 않는다.

k6의 일반 HTTP 응답 전체 문자열에 `[DONE]`이 있는지만 확인하는 테스트로 실제 streaming을 증명하지 않는다. incremental client로 first byte/first content time, 이벤트 순서, 취소·partial error, backpressure를 별도 검증한다. 생성 요청 timeout은 과금 중복을 유발할 수 있으므로 mock fault injection을 우선하며 실제 vendor chaos는 하지 않는다.

Kover의 기존 line coverage gate는 유지하되 임의로 기준치를 높이지 않는다. reserve/settle, permission, retry disposition, stream terminal에 대한 경계 사례 누락을 먼저 막는다. 모듈 이동 전후 테스트 대상 누락도 Konsist·Gradle task 결과로 확인한다.

## 목표-설계-검증 추적

| 목표 | 설계 위치 | gate |
| --- | --- | --- |
| 전사 공용 미디에이터 역할 | §1, §3, §9 | A01–A08, A18–A23 |
| Embeddings | §4.1–4.2, §4.5 | A24–A25, A44 |
| Rerank | §4.1–4.2, §4.5 | A26–A28 |
| Responses | §4.4–4.5 | A32–A34 |
| adaptive routing | §5.2, §8 | A39, A41–A44 |
| quota-aware routing | §5.1, §6 | A37–A40 |
| least-busy / lowest-cost | §5.2 | A35–A36 |
| 사용량·예산 제어 | §6 | A09–A17, A37–A40 |
| 비용 응답·로그·내구성 | §7 | A01, A04, A13–A17, A23, A28 |
| OTel 생태계 | §8 | A41–A43 |
| Virtual Key / Budget / Admin | §9 | A18–A23 |
| Hexagonal 구조·리팩터링 | §2–3, §10 | A01–A08, A45–A46 |
| 전사 예산 잠금 완화·권한 보존 | §6.1 | A47–A50 |
| control/data plane 및 project 소유 격리 | §9.3, §12.1 | A51–A52, A57, A63 |
| 과부하 공정성·retry 증폭 방지 | §5.3, §12.2 | A53–A55, A62, A65 |
| 요청 경로 I/O·캐시 성장 | §12.3 | A56 |
| 내구성·보관·권한·버전 호환 | §12.4 | A58–A59, A63–A64 |
| 조회 권한·Responses 상태 격리 | §4.4, §7.3 | A34, A60 |

## 운영 runbook에 포함할 절차

예산 소진과 admission backend 장애의 구분, 키 긴급 폐기와 반영 확인, deployment drain/rollback, Redis generation 복구, outbox 재전달, UNKNOWN attempt 증빙 조정, 가격 version 정정, Responses 보관 삭제, Collector queue full, client disconnect, deadline 초과를 각각 trigger→확인 지표→조치→검증→감사 형식으로 작성한다. 최종 운영 승인은 활성 gate 실행 증거와 runbook 검토 이후다.

추가 절차는 grant 발급/반납 중단 복구, budget 감소 적용 확인, project owner 이동, 공유 quota 장애 범위 확인, overload shedding·class 용량 조정, PG failover/DR의 잔액 격리, outbox disk 압박과 schema rollback이다. A01–A65 중 철회된 A29–A31/A61을 제외한 61개가 전체 목표의 활성 추적 범위이며 단계별 실행 결과·환경·미지원 profile을 release evidence에 명시한다. 실제 multi-cell 공개 전에는 A52/A57, HA 승인 전에는 A63의 해당 topology 시험이 필수다. 단일 cell 운영 승인과 전체 목표 완료를 구분한다.
