# 미디에이터 Gateway 구현·완결 작업 프롬프트

작성일: 2026-09-09. 프롬프트 검토·보강: 2026-09-10. 기준: [설계](enterprise-gateway-evolution.md), [인수 조건](acceptance-matrix.md).
이 문서는 **향후 작업을 시작하기 위한 프롬프트**다. 목표 생성·구현·테스트·배포를 이미 수행했다는 의미가 아니다.

## 사용 방식

`<repository-root>`는 실행 환경의 저장소 루트 경로로 바꾼다. 개인 PC 경로는 문서에 저장하지 않는다. `execution-status.md`는 실행 시 생성·갱신하는 로컬 전용 파일이며 Git에 포함하지 않는다.

권장 방식은 §3의 **전체 목표 프롬프트 하나**를 실행하고, 그 목표 아래 P00–P14를 순차 진행하는 것이다. 여러 턴에 걸쳐 계속할 때는 §5의 재개 프롬프트를 사용한다. 전체 목표를 켠 상태에서 단위별 새 목표를 중복 생성하지 않는다.

한 섹션씩 직접 통제하려면 §4에서 해당 단위의 프롬프트 하나만 실행한다. 이 경우 해당 단위의 완료와 제품 전체 완료를 구별한다. 각 블록은 이 파일의 공통 규약을 읽도록 포함했으므로 앞선 대화의 기억에 의존하지 않는다. 실제 저장소 경로가 바뀌면 아래 경로를 먼저 수정한다.

현재 활성 조건은 **61개**다. A29–A31/A61은 철회 이력이며 구현·출시 조건으로 부활시키지 않는다. 목표 기능은 지속 작업의 상태를 관리하는 데 사용하며, 토큰 예산·정기 자동 실행·별도 작업 생성은 요청하지 않는다.

| 지금 하려는 일 | 사용할 프롬프트 | 목표 처리 |
| --- | --- | --- |
| 처음부터 전체 구현 실행 | §3 전체 목표 시작 | 같은 활성 목표가 없을 때만 생성 |
| 진행 중인 구현을 다음 턴에서 계속 | §5 다음 턴 재개·보강 | 현재 목표와 실행 기록 유지 |
| 한 섹션만 구현 | §4 해당 P-ID | 전체 목표가 이미 포함하면 그 안에서 수행 |
| 완료 후 드러난 필수 결함 수정 | §5 최종 검토 보강 | 기존 목표 상태·요청 범위를 확인 |
| 프롬프트 작성·검토만 요청 | 이 문서만 편집 | 구현·목표 생성·완료 처리 없음 |

이미 `execution-status.md`에 구현·검증 기록이 있다면 **§5로 재개**한다. 아래 P-ID 목록은 목표 작업 명세이지 전부 미구현이라는 뜻이 아니다. 진행 상태를 이 프롬프트에 복제하지 않고 실행 기록 한 곳에서 관리한다.

아래 블록의 문서명은 모두 저장소의 `docs/design/` 아래 파일을 의미한다. 실행 프롬프트의 범위·완료 기준과 실제 진행 기록을 구분한다. 설계의 과거 소스 진단이나 상태 파일에 적힌 다음 작업도 최신 코드와 대조한다. 이미 반영된 수정은 재구현하지 않고, 아직 검증되지 않은 변경을 이전 테스트 결과로 통과 처리하지 않는다.

## 1. 모든 작업에 적용할 공통 실행 규약

### 1.1 입력·권한·범위

- 저장소: `<repository-root>`.
- 시작할 때 최신 사용자 지시, 저장소 지침, 설계·인수 조건, 현재 HEAD와 변경 파일을 확인한다. 문서의 기준 SHA를 현재 HEAD라고 가정하지 않는다. 기존 변경을 보존하고 겹치면 실제 diff로 구분한다.
- 구현 목적은 다수 내부 서비스의 LLM 호출을 중개하는 미디에이터다. 이미지 생성·전달·객체 저장소·미디어 job, 임의 graph DSL·범용 command bus·추가 벤더는 범위가 아니다.
- Kotlin/Spring MVC/JDK21 virtual threads, 분리된 core/domain/application, `*CommandIn`/`*QueryIn`, 논리 작업별 Operation/Operator, 클래스당 파일 하나를 유지한다. 외부 framework/SDK/Reactor 타입을 안쪽 모듈에 누출하지 않는다. 기존 라이브러리·adapter·테스트를 우선 재사용하고 순수 계산마다 interface/Default 쌍을 만들지 않는다.
- OpenAI/AWS Bedrock/OpenRouter만 대상으로 한다. 특정 vendor/model/profile 지원을 추정하지 말고 실제 고정 dependency·공식 계약·fixture와 필요 시 승인된 smoke로 확인한다. 참고 제품·회사 이름을 제품 문서·커밋에 넣지 않는다.
- registry/budget/journal은 PG, 분산 rate/circuit/runtime capacity는 Redis라는 기존 결정을 따른다. 테스트가 어렵다는 이유로 메모리 구현·fail-open을 운영 대안으로 추가하지 않는다.
- 새 공개 header/field/endpoint는 설계에 있는 소비자 요구와 연결해야 한다. API 고유 성공 응답, 공통 오류, SSE terminal 의미를 유지한다. 내부 provider/topology/가격·권한 정보를 불필요하게 공개하지 않는다.
- 기본 허가는 로컬 코드·설정·테스트·문서 작성과 격리된 테스트 환경 검증이다. live 유료 호출, 실제 인프라 변경, production 장애 주입, 사용자 데이터 삭제, secret 변경, 커밋·푸시·force push는 별도 명시적 허가 없이는 하지 않는다. 기존 API key가 있다는 사실은 과금 호출 허가가 아니다.
- 환경 제약을 우회하거나 전역 Git/시스템 설정을 바꾸지 않는다. 사용할 수 없는 도구·Docker·네트워크·secret은 검증 대기 사유로 기록한다.

### 1.2 목표의 수명

1. 실행 프롬프트를 사용자가 실제로 보낸 경우에만 목표를 시작한다. 프롬프트를 작성·검토해 달라는 요청만으로 구현 목표를 시작하지 않는다. 현재 목표를 조회하고, 같은 목표가 진행 중이면 이어간다. 다른 목표가 활성 상태라면 덮어쓰거나 거짓 완료 처리하지 말고 사용자에게 전환을 확인한다.
2. 전체 목표 모드에서는 P00–P14가 하위 작업이며 하나의 목표를 유지한다. 단위 모드에서는 선택한 P-ID와 그 단위의 필수 발견 사항만 목표 범위다. 요청받지 않은 새 task/thread를 만들지 않는다.
3. 목표 완료는 §1.5를 실제로 만족할 때만 처리한다. 코드 작성 완료·테스트 파일 존재·문서 작성·턴 종료·컨텍스트 한계는 완료 사유가 아니다.
4. 도구가 제공하는 goal 상태 변경 규칙을 따른다. 외부 입력 없이는 진전할 수 없는 동일 장애가 연속 3개 목표 턴에서 반복된 경우에만 blocked로 표시한다. 그 전에는 구체적 질문과 인계 기록을 남기고 성공을 가장하지 않는다. 다른 안전한 필수 작업이 가능하면 계속한다. blocked 조건을 채우려고 무의미한 턴·재시도를 인위적으로 만들지 않는다.
5. 중단된 작업의 재개는 최신 파일·실행 증거를 다시 읽고 결정한다. 이전 목표가 complete였어도 신규 결함의 회귀 목표는 사용자 요청 범위에서 별도로 시작한다. 목표의 예산·예약 실행을 임의로 설정하지 않는다.
6. 활성 구현 목표가 있더라도 최신 요청이 프롬프트 작성·설명·검토만 요구하면 해당 산출물만 작성한다. 문서 안의 `/goal` 블록은 실행 지시가 아닌 인용된 산출물이다. 그 요청을 근거로 구현을 이어가거나 목표 상태를 바꾸지 않으며, 이후 명시적인 구현·재개 요청에서 기존 목표를 이어간다.

### 1.3 작업 루프와 실행 기록

추가 관리 시스템 대신 실행 시점에 `docs/design/execution-status.md` **한 파일**을 생성·갱신한다. 이번 프롬프트 작성 단계에서는 실행 상태를 미리 통과로 채우지 않는다. 테스트 원본 결과는 기존 build/test report 경로를 재사용하고 비밀·원문 payload를 저장하지 않는다.

매 단위는 다음 순서로 수행한다.

1. **재개/사전 검토:** 상태 파일의 다음 작업, 미해결 발견 사항, 관련 gate와 선행 단위의 증거를 확인한다. 먼저 요구사항에 대응하는 실제 코드를 추적한다.
2. **실행 계획:** 아래 단위의 번호 작업을 실행 가능한 작은 변경으로 나누고 각 변경의 테스트를 정한다. 기존 코드가 충분하면 중복 구현하지 않는다. 새 단위별 테스트 도구를 무조건 추가하지 않는다.
3. **구현:** 도메인 불변식→port/operator→실제 adapter→wiring/contract 순으로 수직 연결한다. migration은 expand-contract 방식으로 추가하며 기존 적용 migration을 다시 쓰지 않는다.
4. **검증:** 최소 관련 테스트부터 실행하고 영향받는 계약/DB/Redis/stream 회귀로 넓힌다. 실패하면 원인·재현 fixture를 남기고 수정 후 동일 실패와 인접 경로를 재검증한다.
5. **자체 검토:** 레이어 누수, 동시성·취소, 금전·권한, 계약 불일치, 실패 시 자원 정리, 운영 기본값, 도달 불가능/죽은 코드, 과도한 추상화를 확인한다.
6. **발견 사항 정리:** §1.4로 분류하고 필요한 수정·테스트·문서·gate 변경을 함께 반영한다.
7. **인계:** 현재 상태, 수행한 정확한 command와 exit/result, 남은 gate, 미해결 F-ID, 다음 턴 첫 행동을 저장한다. 필수 작업이 남으면 완료 대신 진행 중/검증 대기로 보고한다.

각 단위의 번호 작업은 `P01.1`처럼 식별하여 같은 상태 문서에 체크리스트로 기록한다. 실행 직전 해당 작업을 **대상 파일/심볼 → 변경할 불변식 → 실제 연결 경로 → 검증할 A-ID/테스트 → 완료 증거**로 구체화한다. 파일 구조가 바뀌면 실제 소스에 맞춰 갱신하며, 미리 추정한 클래스명에 구현을 억지로 맞추지 않는다. 한 작업이 여러 턴에 걸치면 완료한 부분과 다음 변경을 분리하고 번호 작업 전체를 완료 표시하지 않는다.

한 번에 선택할 작업은 검증 가능한 하나의 불변식 또는 실패 경로를 중심으로 한다. 큰 번호 작업은 `P02.4a`처럼 하위 ID로 나누되 별도 목표·관리 파일은 만들지 않는다. 하위 작업을 마치면 같은 목표 안에서 다음 작업으로 계속할 수 있다. 턴 종료를 맞추려고 작업을 임의로 완료 처리하거나 의존하지 않는 독립 작업까지 멈추지 않는다.

실행 기록의 현재 작업에는 다음 카드를 채운다. 실제 구현을 조사한 뒤 작성하며, 빈 카드 자체는 진전이나 완료 증거가 아니다.

```markdown
### 작업 카드: Pxx.n / 연결 F-ID
- 목적·불변식: 어떤 사용자 동작 또는 실패 결과를 보장하는가?
- 입력·선행 증거: 필요한 계약/상태/완료 작업과 아직 확인되지 않은 가정
- 대상: 실제 파일·심볼, 호출자와 저장소·외부 경계
- 변경·연결: domain → application → adapter → wiring/contract의 해당 경로
- 비변경 범위: 이번 수정에 불필요한 API·모듈·추상화
- 검증: A-ID, 정상/경계/실패 fixture, 실제 실행 명령과 필요한 환경
- 완료 산출물: 연결된 코드, 회귀 테스트, 필요한 migration/계약/runbook
- 결과·잔여: 코드 기준, 종료값, 성공/실패/skip, 남은 필수 F-ID
- 다음 첫 행동: 파일·심볼 → 수정 또는 재현 → 실행할 검증
```

테스트 증거에는 HEAD뿐 아니라 미커밋 변경 여부와 실행 시점의 관련 diff 또는 파일 해시를 연결한다. 실행 이후 관련 코드가 바뀌면 이전 PASS를 최신 증거로 재사용하지 않는다. 프로세스가 실행 중이면 명령과 session ID를 남기고, 종료를 확인한 뒤 실제 exit code로 갱신한다. 종료된 session을 다음 턴의 진행 중 작업으로 남기지 않는다.

출력이 잘렸거나 세션 결과를 잃은 경우 종료 상태와 원본 보고서를 먼저 확인한다. 확인할 수 없으면 결과 미확인으로 기록하고, 중복 실행의 안전성을 확인한 뒤 필요한 검증만 재실행한다. 실행 중인 테스트와 같은 작업 트리를 동시에 수정하여 결과의 코드 기준을 섞지 않는다.

`execution-status.md`의 최소 형식:

```markdown
# 실행 상태
- 목표 모드/범위: 전체 또는 P-ID
- 기준: HEAD, 작업 트리 변경 요약, 검증일·환경
- 현재 단위/다음 단위:
- 최근 턴 결과와 다음 턴 첫 행동:
- 범위 결정·미확정 운영 입력:

## 단위 상태
| P-ID | 상태 | 남은 필수 작업/선행 조건 | 증거 |
상태: NOT_STARTED / IN_PROGRESS / VERIFY_PENDING / DONE

## 인수 조건 증거
| A-ID | 주 담당 P-ID | 상태 | 정확한 명령·환경·종료값·결과 경로 | 검증한 코드 기준 |
상태: NOT_RUN / PASS / FAIL / ENV_BLOCKED / RETIRED
후속 변경이 영향을 주면 PASS를 재검증 대상으로 돌린다.

## 발견 사항
| F-ID | 발견 단위 | 분류/심각도 | 근거·재현 | 영향 P/A-ID | 다음 행동·담당 단위 | 상태/종결 근거 |

## 다음 턴 인계
- 우선 처리 F-ID 및 이유:
- 먼저 읽을 파일·심볼:
- 다음 구현 또는 재현 단계:
- 재실행할 테스트:
- 외부 입력/허가가 필요하면 정확한 질문:
```

### 1.4 사전 계획 밖 발견 사항과 다음 턴 처리

- 발견 즉시 F001부터 고유 ID를 부여한다. 동일 원인은 기존 항목에 병합하고, 해결 후 재발한 항목은 증거와 함께 다시 연다. 테스트 실패와 추측성 개선을 구분한다.
- **필수 결함:** 현재 계약·인수 조건 위반, 보안/금전/데이터 손실, 실행·복구 불능. 가능한 작은 수정은 같은 턴에 처리한다. 큰 수정은 재현/실패 테스트·영향 gate·다음 구현 지점을 남기고 **다음 턴 최우선**으로 처리한다. 단위 완료를 막는 결함을 다른 단위 backlog로 넘겨 DONE으로 만들지 않는다.
- **설계 누락/불일치:** 먼저 실제 요구·소스·테스트로 타당성을 판단한다. 기존 의도 안의 오류 수정은 설계·코드·인수 조건을 함께 갱신한다. 새로운 조건은 기존 ID를 재사용하지 말고 A66부터 추가하며 담당 P-ID·회귀 범위를 연결한다. 새 기능·공개 계약 변경·운영 위험 확대는 사용자 결정 전 구현하지 않는다.
- **후속 필수 통합:** 아직 미구현인 정상 선행/후행 의존은 담당 P-ID와 완료 gate를 명시한다. 부분 구현은 통과로 세지 않는다. 공통 A07의 전체 API 검증 등은 P14에서 닫되 해당 단위의 현존 API 회귀는 즉시 수행한다.
- **선택 개선:** 측정되지 않은 최적화·범위 밖 기능은 이유와 도입 조건만 남긴다. 필수 결함을 선택 개선으로 바꿔 숨기지 않는다. 관련 없는 리팩터링으로 목표를 무한 확대하지 않는다.
- **환경/결정 대기:** Docker, HA topology, 승인된 SLO, live smoke 권한 등 필요한 입력과 영향 gate를 구체적으로 기록한다. skip/mock 대체를 실제 통합 PASS로 바꾸지 않는다. 가능한 독립 작업을 진행하되 최종 운영 완료는 보류한다.
- 다음 턴은 **기록 재조회→필수 F-ID 재현→타당성 판단→수정→실패/인접 회귀→증거 갱신** 순으로 시작한다. 기록만 추가하고 새 기능으로 넘어가지 않는다.
- 동일한 환경 대기 항목은 환경·권한·입력의 변경 근거가 있을 때 재검증한다. 변화가 없다면 기존 증거와 재개 조건을 유지하고 독립적인 필수 작업을 선택한다. 해결되지 않은 안전성 결함이 있는 경로를 활성화하는 것은 독립 작업이 아니다.
- 다음 턴 처리 순서는 현재 경로의 보안·금전·데이터 손실 결함, 실패한 기존 회귀, 현재 단위의 필수 누락, 후속 단위 순이다. 같은 원인이 여러 단위에 걸치면 원인 소유 단위와 소비 단위의 재검증을 연결한다. 계획에 이미 있는 후속 통합은 매 턴 새 결함으로 중복 등록하지 않는다.
- 각 턴의 진전은 종료된 하위 작업·재현된 실패·갱신된 검증 증거·해소된 외부 입력으로 설명한다. 같은 문서를 반복해서 읽거나 상태 문구만 바꾼 것을 구현 진전으로 세지 않는다. 진전이 없으면 정확한 원인과 필요한 다음 입력을 남기고 도구의 blocked 규칙을 따른다.
- 새 사실로 기존 완료 gate가 깨지면 관련 단위를 재개하고 전체 목표 완료를 다시 판단한다. 미지의 결함이 전혀 없음을 증명하는 무한 검토 대신 현재 요구에 대한 증거와 미해결 필수 항목 0건을 종료 기준으로 삼는다.
- 자동 다음 턴이나 예약 실행이 보장된다고 말하지 않는다. 현재 환경이 목표를 이어가는 동안은 계속하고, 입력/환경 때문에 멈추면 §5 재개 프롬프트와 구체적인 첫 행동을 남긴다.

발견 사항은 `OPEN → FIXED_VERIFY_PENDING → CLOSED`로 구분한다. 코드 수정만 마쳤거나 필요한 실제 환경 회귀가 빠졌으면 중간 상태를 유지한다. 환경 때문에 수정·검증할 수 없으면 `ENV_BLOCKED`와 재개 조건을 기록한다. 제안이 타당하지 않으면 재현·계약 검토 근거와 함께 `NOT_APPLICABLE`로 남긴다. 모든 필수 항목에는 **담당 P-ID, 영향 A-ID, 첫 파일/심볼, 실패 재현 또는 검증 명령, 종결 assertion**을 지정하여 다음 턴이 원인을 다시 추측하지 않게 한다.

### 1.5 완료 판정

단위 DONE은 담당 기능의 실제 wiring, 해당 주 담당 gate의 PASS, 필요한 migration/계약/운영 문서, 범위 내 미해결 필수 F-ID 0건이 모두 필요하다. 다른 단위가 아직 만들지 않은 기능을 형식적 port/stub로 채워 완료 처리하지 않는다. 코드는 준비됐지만 실제 환경 테스트가 없으면 VERIFY_PENDING이다.

전체 목표 COMPLETE는 아래를 모두 충족해야 한다.

- P00–P14 및 활성 A-ID 전체의 검증 증거가 최신 변경에 유효함. A29–A31/A61만 RETIRED이고 신규 A66+가 있으면 포함.
- Kotest, MVC/HTTP, 필수 PostgreSQL/Redis Testcontainers, Konsist/Kover, OpenAPI/Java·Python 소비자 계약, 실제 증분 SSE, 혼합 부하, 필요한 HA/cell/profile smoke gate를 조건에 맞는 환경에서 실행.
- live 모델·region/profile, class별 SLO, revoke bound, RPO/RTO 등 운영 입력이 승인됐고 측정 결과가 충족됨. 임의 수치를 확정 SLA로 쓰지 않음.
- 모든 필수 발견 사항이 검증으로 종결됐고 runbook·migration/rollback·복구 증거가 있음. 테스트 skip, ENV_BLOCKED, 미완료 통합을 PASS로 계산하지 않음.
- 단일 cell 운영 가능과 전체 다중 cell·HA 검증 완료를 분리하여 보고함. 제한된 출시 범위는 사용자가 별도 승인할 수 있으나 전체 목표 완료라고 재명명하지 않음.
- 별도 허가가 없다면 커밋·푸시·실제 운영 배포는 완료 조건에 포함하지 않음.

## 2. 작업 단위·의존성·인수 조건 소유권

선행 단위의 코드·계약·관련 로컬 회귀가 준비됐지만 외부 환경 gate만 대기라면, 미확인 가정에 의존하지 않는 후속 작업은 진행할 수 있다. 이 경우 선행을 DONE/PASS로 바꾸지 않고 잔여 위험을 연결한다. 실행 안전성이나 금전·권한 불변식이 미완성인 경우에는 의존하는 경로의 활성화를 막는다.

주 담당은 최종 증거를 닫는 책임이다. 관련 gate는 후속 변경 때 다시 실행하며 단위 간 중복 검증을 생략하지 않는다. 설계의 S0–S7을 실행 크기로 세분화했으며 새로운 제품 범위를 추가하지 않는다.

| 단위 | 작업 | 설계 단계 | 선행 단위 | 주 담당 gate |
| --- | --- | --- | --- | --- |
| P00 | 기준선·실행 환경·미확정 입력 | 준비 | 없음 | 기준선 기록; PASS 선등록 금지 |
| P01 | 식별자·시도 구분·선택 permit·startup writer | S0 | P00 | A01–A05 |
| P02 | typed 경계·stream 수명·사용량 단위 | S1 | P01 | A06, A28 |
| P03 | 실행 journal·소유 예산·종결 정산 | S2 | P02 | A09–A13, A16–A17 |
| P04 | 영속 grant 발급·반납·한도 감소 | S2 | P03 | A47–A50 |
| P05 | outbox worker·복구·보관·저장 압박 | S2 | P03, P04 | A14–A15, A58–A59 |
| P06 | Virtual Key·권한·사용량 조회 | S3 | P04, P05 | A18–A20, A60 |
| P07 | policy publish·snapshot·제어면 장애 | S3 | P06 | A21–A22, A51, A56 |
| P08 | Embeddings·Rerank 수직 구현 | S4 | P02–P07 | A24–A27 |
| P09 | Responses JSON/SSE·상태·권한 | S5 | P02–P07 | A23, A32–A34 |
| P10 | 실제 OTel·관측/정산 분리 | S7 일부 | P05, P07, P08, P09 | A41–A43 |
| P11 | quota-aware·분산 부하·비용·adaptive·retry | S6 | P07, P10 | A35–A40, A55 |
| P12 | 공정성·과부하·guardrail·느린 소비자 | S7 일부 | P08–P11 | A08, A53–A54, A62, A65 |
| P13 | cell 소유·이관·HA·버전/DB 권한 | S7 일부 | P04–P12 | A52, A57, A63–A64 |
| P14 | 소비자 계약·혼합 부하·drain·최종 판정 | S7 통합 | P01–P13 | A07, A44–A46 |

기본 순서는 P00→P14다. P08/P09는 서로 독립적으로 진행할 수 있으나 공유 파일 변경과 migration 번호를 조정해야 한다. OTel의 내부 event/context 계약은 P02에서 정의하고 실제 export 검증은 P10에서 수행하여 P11 adaptive 입력이 뒤늦게 결정되지 않도록 한다. P03에서 최소 안전한 permit 연동을 구현하고 P11은 같은 경계를 확장한다. P05의 저장 압박 차단은 P12까지 안전성을 미루지 않는다.

## 3. 전체 목표 시작 프롬프트

아래 블록 하나를 복사하여 실행한다.

```text
/goal 현재 설계에 따라 전사 공용 LLM 미디에이터 Gateway의 구현·보강·검증을 끝까지 수행하라.

저장소는 <repository-root> 이다.
먼저 docs/design/execution-prompts.md 전체와 enterprise-gateway-evolution.md, acceptance-matrix.md를 읽어라.
execution-prompts.md §1의 공통 실행 규약과 §2의 의존성·gate 소유권을 적용한다.

하나의 전체 목표 아래 P00–P14를 진행하라. 단위별 새 목표를 만들지 말고, 각 P-ID의 번호 작업을 실제 구현·wiring·테스트·문서까지 마쳐라. 미디에이터를 미디어 시스템으로 해석하지 말고 철회된 A29–A31/A61을 구현하지 마라.

매 턴 시작 시 execution-status.md의 미해결 필수 발견 사항과 다음 행동을 먼저 처리하라. 사전 계획에 없던 결함/누락은 F-ID, 근거·재현, 영향 gate, 다음 수정·검증 단계로 기록하고 다음 턴에서 우선 종결하라. 필요한 인수 조건은 A66부터 추가하되, 범위 확대나 외부 권한은 사용자 확인 없이 진행하지 마라.

활성 61개 조건과 승인된 신규 조건의 실행 증거, 미해결 필수 항목 0건, 운영 runbook·복구 검증이 모두 갖춰지기 전에는 전체 목표를 complete로 처리하지 마라. 미실행 통합·live smoke·HA·부하 테스트는 검증 대기로 남겨라. 독립적으로 진행 가능한 작업은 계속하되 필요한 외부 입력은 구체적으로 요청하라.

실제 배포·유료 호출·비밀 변경·커밋·푸시는 이 요청의 허가 범위가 아니다. 최종 응답은 완료 범위, 검증 명령/결과, 남은 운영 제한, 증거 문서 링크를 제시하라.
```

## 4. 단위별 목표 프롬프트

전체 목표 모드에서는 아래 작업 명세를 하위 작업으로 적용한다. 개별 실행 모드에서만 각 블록을 별도 목표로 사용한다.

### P00. 기준선·검증 환경·운영 입력

```text
/goal P00: 기준선·검증 환경·운영 입력을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: 없음. 참조: 전체 설계 및 인수 조건.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. 최신 HEAD/작업 트리와 저장소 지침을 확인하고 기존 코드·OpenAPI·모듈·migration·테스트 task를 탐색한다. 과거 통과 결과를 현재 결과로 재사용하지 않는다.
2. JDK/Gradle, Docker/Testcontainers, Python 소비자 앱, k6, Redis/PG HA 시험 환경의 가용성을 비파괴적으로 확인한다. 저장소에 실제 존재하는 명령으로 가능한 최소 baseline build/test를 실행한다.
3. execution-status.md에 P00–P14, 활성 61개 A-ID와 철회 4개 ID를 등록한다. 확인된 현재 구현/누락/기존 테스트/필요 신규 테스트를 구분한다.
4. 승인된 model/region/profile, SSO, class별 부하·SLO, revocation bound, RPO/RTO, 보관 정책, live smoke 권한의 공백을 기록한다. 먼저 가능한 조사로 좁힌 뒤 정말 필요한 질문만 묶어 요청한다.
5. 초기 환경 제약과 기존 실패를 F-ID로 남기고 P01의 첫 파일·재현 명령을 지정한다. 다른 단위가 소유할 기능을 미리 대규모 구현하지 않는다.
완료 기준: 재현 가능한 기준선·실제 가용 명령·의존성·미확정 입력·다음 행동이 기록돼 있다. 환경 불가를 테스트 성공으로 쓰지 않으며 P00 완료는 운영 준비 완료가 아니다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P01. 식별자·시도 종류·선택 permit 정확성

```text
/goal P01: 식별자·시도 종류·선택 permit 정확성을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P00. 참조: 설계 §2 L2/L3/L4/L6, §5, §7.1.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. caller X-Request-Id는 correlation 전용으로 유지하고 server ExecutionId/AttemptId를 원장·observation·내부 상태의 고유 식별자로 연결한다. 같은 correlation의 동시·다른 project 요청을 분리한다.
2. circuit 후보 inspect와 선택 후보 acquire를 분리한다. 미선택 half-open 후보의 probe를 점유하지 않으며 stale owner의 release를 차단한다.
3. instance startup의 관리 registry 덮어쓰기/disable을 제거한다. 초기 seed가 필요하면 명시적·멱등 관리/이행 경로로 분리한다.
4. INITIAL/RETRY/FALLBACK을 실행 시점에 기록하고 집계를 교정한다. 요청 정책/가격 snapshot은 실행 중 고정하되 emergency disable은 별도 검사한다.
5. 기존 JSON/SSE 소비자 계약을 보존하며 PG/Redis 다중 client 및 rolling replica fixture로 회귀를 검증한다.
완료 기준: A01–A05를 실제 연결된 경로에서 통과하고, 이후 journal 이행 시 재실행할 회귀 목록을 남긴다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P02. 헥사고날 typed 경계·stream 수명·사용량 모델

```text
/goal P02: 헥사고날 typed 경계·stream 수명·사용량 모델을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P01. 참조: 설계 §3, §4.1, §7, §8.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. 현존 모듈을 재사용하여 과금 command와 조회 query를 분리하고 API별 typed port 및 Operation/Operator 책임을 정리한다. core/domain/application으로 HTTP DTO, SDK, Spring/Reactor 타입이 역유입되지 않게 한다.
2. Chat의 기존 동작을 이 경계로 실제 이전한다. 미래 API의 빈 구현·범용 Map/Any dispatch·불필요한 interface/Default 쌍을 늘리지 않는다.
3. 명시적 close/cancel 수명을 가진 stream handle과 단일 local cleanup을 구현한다. 원격 실행 불명은 금전 hold/SUSPECT와 구분하며 interrupt만으로 provider 종료를 확정하지 않는다.
4. 단위별 UsageComponent, 가격 snapshot, amount/source/status 및 관측 event/context 계약을 정의한다. cache/reasoning 포함관계, rerank 단위, UNKNOWN/무료 차이의 계산 테스트를 구현한다.
5. Konsist·dependency 검사·Kotest·기존 Chat HTTP/SSE 회귀로 검증한다. 향후 API DTO/OpenAPI 패턴은 정의하되 미구현 endpoint를 지원한다고 공개하지 않는다.
완료 기준: A06/A28 PASS와 Chat close/cancel의 로컬·HTTP 회귀 증거. A08의 전체 permit/원격 취소 수명 검증은 P11 구현 이후 P12가 닫고, A07 전체 API 검증은 P14 소유다. 미래 gate를 미리 통과 처리하지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P03. 실행 journal·예약·정산의 원자성

```text
/goal P03: 실행 journal·예약·정산의 원자성을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P02. 참조: 설계 §6–7, §12.3.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. 신규 migration으로 execution/attempt/reservation/usage/cost/outbox의 상태·unique/CAS 제약을 도입한다. 현재 writer와 이행 전략을 확인하고 이중 정산을 만들지 않는다.
2. 소유 PG의 reserve+PREPARED, 선택 Redis permit, PG DISPATCH_INTENT, provider, terminal+usage+cost+settle+outbox 순서를 실제 Chat 경로에 연결한다. 최소 안전한 permit/owner 연동도 구현하고 P11의 확장을 기다리는 no-op으로 두지 않는다.
3. 원장 저장 예외를 소거하는 경로를 제거한다. 미전송 예약 보상과 DISPATCH_INTENT 이후 UNKNOWN hold를 구분하고, terminal 저장 전 비스트리밍 성공/스트리밍 성공 terminal을 확정하지 않는다.
4. PG/Redis 거절·취소·시도 전환·중복 종결·월말·가격 변경·late usage·초과 청구를 처리한다. 조회 실패나 UNKNOWN을 무료/미실행으로 간주하지 않는다.
5. 실제 PG/Redis·provider stub·process kill로 A09–A13/A16–A17을 검증한다. 예산 100/예약 30의 동시 승인 상한과 provider call counter도 관찰한다.
완료 기준: 담당 gate PASS와 장애별 복구 지침. 조직 grant 배분은 P04에서 이어가며 이행용 공유 잠금 상태를 전사 확장 완료로 표현하지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P04. 전사 예산 grant 발급·반납·변경

```text
/goal P04: 전사 예산 grant 발급·반납·변경을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P03. 참조: 설계 §6.1, §12.1.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. 조직→project 소유 grant, 소유 epoch, 고유 발급/반납 transfer와 receipt를 구현한다. 부모 배분 한도와 로컬 available/held/settled/return_pending의 보존 규칙을 상태 전이로 검증한다.
2. 중앙 차감·발급 commit 이후 소유 PG의 멱등 import, 응답 유실 조회·재전달을 구현한다. 매 요청 중앙 조직 행 UPDATE를 제거하고 로컬 grant/서비스 한도와 journal 예약을 원자 연결한다.
3. 미사용분 동결→중앙 1회 credit→로컬 ack의 반납 복구를 구현한다. 진행 중 transfer 합계를 이중 계산하지 않고 timeout/TTL만으로 반납·재배분하지 않는다.
4. 이미 배분한 액면 미만으로 예산을 낮출 때 동결·회수·실행 차단 확인 상태를 드러낸다. UNKNOWN/기간 종료/사후 초과 청구와 키 rotation에도 한도를 우회하지 못하게 한다.
5. 두 독립 PG와 동시성·kill fixture로 A47–A50을 검증하고 P03의 예산·정산 회귀를 다시 실행한다.
완료 기준: 담당 gate PASS, 이전 예약 데이터의 안전한 이행 및 grant 복구 절차가 존재한다. 근거 없는 pod별 wallet이나 전역 active-active writer는 추가하지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P05. Outbox·복구 worker·보관·저장 압박

```text
/goal P05: Outbox·복구 worker·보관·저장 압박을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P03, P04. 참조: 설계 §7.2, §12.4.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. PG outbox bounded claim/lease/ack, commit 이후 외부 I/O, consumer receipt 기반 projection 중복 방지를 구현한다. sink 처리가 금전 정산을 다시 실행하지 않게 한다.
2. worker 실행 경계·pool을 분리하고 실패 backoff/jitter, poison event, 오래된 event 및 project별 starvation을 처리한다. 새 broker나 pop 후 삭제 큐를 도입하지 않는다.
3. 미종결 attempt·transfer의 복구 및 증빙 reconciliation을 연결한다. 조회 불가능한 provider 결과를 가정으로 확정하거나 LLM을 재호출하지 않는다.
4. outbox/WAL/disk 압박을 관찰하고 terminal 저장 여유를 보존하는 신규 admission 차단을 실제 요청 경로에 구현한다. P12까지 해당 안전장치를 미루지 않는다.
5. partition/retention/dedup 범위를 설계와 맞춘다. 실제 partition이 불필요하면 무작정 도입하지 않되 보관 경계·replay·pending 보호를 실제 PG에서 검증한다.
6. A14/A15/A58/A59의 commit/ack 전후 kill·장기 sink 장애·반복 replay를 실행하고 복구 runbook을 작성한다.
완료 기준: 담당 gate PASS. 소비자 API는 P06에서 만들 수 있으나 실제 projection writer와 재처리 증거 없이 완료하지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P06. Virtual Key·접근 제어·BFF 사용량 조회

```text
/goal P06: Virtual Key·접근 제어·BFF 사용량 조회을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P04, P05. 참조: 설계 §7.3, §9.1–9.2.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. 서비스 identity 기반 키 발급·hash-only 저장·1회 원문 반환·rotation/revoke/expiry를 구현한다. static key 이행은 명시적으로 수행하고 startup writer를 되살리지 않는다.
2. 관리 OIDC/JWT audience/role·project ACL과 service key inference/usage 권한을 분리한다. 인증·폐기 cache freshness/invalidation 유실과 keyring rotation을 처리한다.
3. 관리 key/budget/usage 표면과 BFF read-only usage query를 설계대로 연결한다. 원장 직접 UPDATE 대신 감사 가능한 command를 사용하고 필요한 admin 실행/contract 경계만 분리한다.
4. projection freshness, 같은 correlation의 다중 execution, 성공 response ID 없는 UNKNOWN 조회를 구현한다. 다른 project·내부 provider detail·digest가 소비자에게 노출되지 않게 한다.
5. 다중 replica·PG/Redis·MVC 및 Java/Python 계약 fixture로 A18–A20/A60을 검증한다. 허가된 사용량 조회는 budget 소진과 독립되게 한다.
완료 기준: 담당 gate PASS와 키 폐기/조회 runbook. Responses 조회·삭제까지 포함하는 A23은 P09가 주 담당한다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P07. 정책 publish·indexed snapshot·제어면 독립성

```text
/goal P07: 정책 publish·indexed snapshot·제어면 독립성을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P06. 참조: 설계 §9.3, §12.1, §12.3.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. draft/validate/publish/rollback revision과 낙관적 충돌, audit/outbox 원자성을 구현한다. 실제 adapter capability·embedding space·region·가격/예산 단위·bounded 경로를 검증한다.
2. 불변 indexed snapshot과 준비된 provider client를 원자 교체하고 이전 요청의 snapshot/client를 drain한다. 알림 유실을 polling으로 복구하며 refresh single-flight/jitter/backoff를 둔다.
3. 요청마다 전체 registry SELECT 및 후보별 무제한 순차 I/O가 발생하지 않게 후보·snapshot 크기·round-trip 경계를 만든다. 미준비 cold-start instance는 readiness를 열지 않는다.
4. 관리 PG와 실행 PG 장애를 구분한다. 관리 authority 중단 시 유효한 auth lease·snapshot·기배분 grant까지만 실행하고, 만료 후 신규 호출을 닫되 기존 정산은 진행한다.
5. 실제 분리 PG·3 replica·publish 경쟁·notification 유실·cold restart로 A21/A22/A51/A56을 검증한다.
완료 기준: 담당 gate PASS와 publish/rollback/authority 장애 runbook. 새로운 graph editor나 client routing header를 추가하지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P08. Embeddings·Rerank 수직 기능 구현

```text
/goal P08: Embeddings·Rerank 수직 기능 구현을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P02–P07. 참조: 설계 §4.1–4.2, §4.5.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. Embeddings/Rerank의 MVC DTO·OpenAPI·CommandIn·Operation·typed provider adapter를 실제 호출 경로까지 구현한다. 단순 Chat 변환으로 의미를 소실시키지 않는다.
2. Embeddings batch 분할의 child attempt 비용·quota, 결과 index/차원·encoding, 부분 실패와 전체 공개 원자성을 처리한다. 같은 차원이어도 다른 embeddingSpaceId로 fallback하지 않는다.
3. Rerank top_n/score/index/NaN/중복·동점 안정 순서와 실제 청구 단위를 처리한다. provider malformed 결과를 caller 오류로 오분류하지 않는다.
4. 세 허용 vendor의 실제 지원 profile만 연결하고 미지원 조합은 호출 전 거부한다. Bedrock Rerank의 모델/지역/권한 smoke는 사전 승인된 격리 조건에서만 수행한다.
5. A24–A27, 관련 A06/A07/A28과 공통 reserve/settle·오류·소비자 fixture를 검증한다.
완료 기준: 담당 gate PASS와 API 사용 예제. A27 실행 권한/환경이 없으면 VERIFY_PENDING으로 남기고 mock 성공을 live 검증으로 대체하지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P09. Responses JSON/SSE·저장 상태·권한

```text
/goal P09: Responses JSON/SSE·저장 상태·권한을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P02–P07. 참조: 설계 §4.4–4.5, §7.2, §9.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. Responses 고유 typed input/output item·call ID·opaque reasoning·refusal·incomplete/usage를 보존하는 native/호환 profile을 구현한다. Chat wrapper를 전체 호환으로 광고하지 않는다.
2. 실제 incremental SSE event 순서·terminal·취소·부분 오류를 구현하고 Chat의 DONE을 강제하지 않는다. 공개 시작 이후 자동 fallback/생성 재실행을 금지한다.
3. store=false 기본과 opt-in store/previous_response_id·조회/삭제/input-item 계약을 구현한다. project ACL·expiry·암호화/retention·deployment affinity를 보존하고 opaque state를 다른 provider로 replay하지 않는다.
4. Gateway는 BFF function tool을 실행하지 않는다. hosted tool profile은 승인된 capability/가격 상한만 허용한다. 예산 소진 시에도 허가된 상태 조회·삭제·사용량 조회를 막지 않는다.
5. A23/A32–A34 및 관련 소비자·원장·권한 회귀를 실제 MVC/SSE/PG fixture로 검증한다.
완료 기준: 담당 gate PASS, stateless/stored 지원 범위가 OpenAPI와 runtime에 일치한다. 상태 저장 장애를 묵시적 stateless 강등으로 숨기지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P10. OTel·비용/관측 분리·운영 지표

```text
/goal P10: OTel·비용/관측 분리·운영 지표을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P05, P07, P08, P09. 참조: 설계 §8, §12.5.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. 표준 propagator의 SERVER→execution/attempt parent 관계와 virtual thread context capture/restore/close를 실제 계측에 연결한다. trace ID 문자열 추출이나 request map만으로 계측을 대체하지 않는다.
2. 고정된 GenAI 규약 revision과 API별 usage·TTFT·duration·outcome mapping을 구현한다. Spring AI/HTTP 자동 계측과 수동 span/metric의 중복을 제거한다.
3. metadata-only 로그·redaction·low-cardinality metric, unsampled ledger, 감사·조정 기록의 독립성을 검증한다. 키/project/request 고유 ID를 metric label로 추가하지 않는다.
4. 실제 Collector queue/retry/disk 경로와 sink 장애 시 bounded memory·drop/경보를 구성한다. 관측 export 장애가 inference/정산을 막지 않도록 한다.
5. P11이 사용할 latency/failure/inflight 등 telemetry snapshot의 단위·bucket·freshness를 실제 event/worker에 연결한다. 요청 경로에서 분석 SQL/Prometheus를 조회하지 않는다.
6. A41–A43을 exporter fixture와 Collector container 장애로 검증하고 필요한 dashboard/alert·복구 문서를 제공한다.
완료 기준: 담당 gate PASS와 telemetry 입력 계약. sampling된 trace를 비용 원장으로 사용하지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P11. 분산 라우팅·quota·재시도 완결

```text
/goal P11: 분산 라우팅·quota·재시도 완결을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P07, P10. 참조: 설계 §5–6, §12.1, §12.3.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. 자격 검사→읽기 전용 circuit/quota→priority/ranking→선택 후보 reserve/acquire를 공통화한다. 정책별 ranking은 하나만 선택하고 quota 경합은 provider 실패로 집계하지 않는다.
2. weighted rendezvous를 안전한 기본으로 유지하며 least-busy의 active/SUSPECT 정규화, 요청별 예상 lowest-cost와 보수적 예약 상한을 구현한다.
3. telemetry 기반 adaptive EWMA·최소 표본·freshness·clamp·hysteresis·정적 복귀·shadow/canary를 구현한다. 품질 평가 LLM/hedging은 추가하지 않는다.
4. 공유 credential pool/region/model quota와 permit owner/fence/generation, Redis 유실·재구성·미확정 원격 실행의 용량 보유를 구현한다. TTL 만료를 원격 종료 증거로 쓰지 않는다.
5. Redis의 약한 failover 보장과 같은 소유 PG 경계의 durable capacity profile을 명확히 분리한다. 다른 cell에 같은 계정 capacity를 중복 발급하지 않는다.
6. request deadline/총 시도/동일 deployment retry/fallback 한도와 pool 추가 시도 예산을 함께 적용한다. SDK/BFF 중첩 retry·destination overload·전송 불명·stream 공개 후 행동을 통일한다.
7. A35–A40/A55를 실제 다중 replica·Redis 복제 topology·PG·fault injection으로 검증하고 routing decision 원인·운영 runbook을 남긴다.
완료 기준: 담당 gate PASS. 정상 허가 정합성과 장애 중 이미 전송한 원격 호출의 한계를 구별하며 exactly-once를 과장하지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P12. 공정성·과부하·guardrail·stream 자원 보호

```text
/goal P12: 공정성·과부하·guardrail·stream 자원 보호을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P08–P11. 참조: 설계 §1.1, §12.2.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. JSON 전체 변환 전 ingress/filter 인증·byte/content-type/read-time 제한을 적용한다. chunked·허용 압축 해제 크기·JSON depth/string/array를 포함한다.
2. interactive stream/retrieval batch class와 project/service의 inflight·connection·byte 상한을 구현한다. replica 증가에도 총 배분 상한과 class 보호가 유지되게 한다.
3. 기본 즉시 admission과 필요한 class의 bounded queue만 구현한다. 대기 중 provider permit/금전 hold를 잡지 않으며 ingress부터 settlement까지 deadline을 공유한다.
4. local overload와 project rate/quota·budget·backend unavailable의 공개 오류를 구분한다. 느린 SSE 소비자의 outbound buffer/write stall을 제한하고 local cleanup과 SUSPECT/hold 수명을 분리한다.
5. 기존 guardrail port에 필수 검사 fail-closed·별도 timeout/concurrency를 적용한다. stream 공개 전/후 검사 한계·검사기 장애의 circuit 분리를 검증한다. 외부 검사 서비스 자체는 새로 구매/도입하지 않고 adapter와 격리 stub으로 검증한다.
6. A08/A53/A54/A62/A65를 open-arrival 부하·raw HTTP·incremental client·JFR·검사 stub으로 검증하고 P05의 저장 압박 차단을 회귀한다.
완료 기준: 담당 gate PASS와 class별 승인된 목표 대비 결과. 미정 SLO를 임의 수치로 확정하여 통과시키지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P13. cell 소유권·이관·HA·업그레이드 안전성

```text
/goal P13: cell 소유권·이관·HA·업그레이드 안전성을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P04–P12. 참조: 설계 §10, §12.1, §12.4.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. 단일 cell 기본 배포를 유지하면서 project/home-cell ownership과 epoch, bootstrap/readiness, 내부 lookup을 구현한다. client routing header나 프로젝트별 Gradle 모듈을 추가하지 않는다.
2. 격리된 두 cell 시험에서 실행 PG/Redis와 공유/전용 upstream quota authority의 장애 범위를 검증한다. 같은 인스턴스를 쓰는 논리 cell을 물리 장애 격리로 간주하지 않는다.
3. source admission 동결→drain→grant/원장/상태 이관 검증→epoch 변경→destination 활성화와 중단 복구를 구현한다. UNKNOWN hold나 provider affinity를 삭제/복제해 우회하지 않는다.
4. 실제 PG HA topology에서 synchronous durability·승격·old primary fencing·async DR의 미확정 owner 격리를 시험한다. 단일 container restart를 HA 검증으로 대체하지 않는다.
5. N/N+1 app/worker/event 호환, backup restore, expand-contract migration과 data plane/worker/admin DB 최소 권한을 검증한다.
6. A52/A57/A63/A64의 독립 환경 실행 증거와 이관·rollback·DR runbook을 남긴다. 기존 운영 인프라는 변경하지 않는다.
완료 기준: 담당 gate PASS. 필요한 topology/허가가 없으면 재현 가능한 시험 구성과 정확한 대기 조건을 남기되 VERIFY_PENDING으로 유지한다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### P14. 소비자 계약·종합 검증·최종 완결 판정

```text
/goal P14: 소비자 계약·종합 검증·최종 완결 판정을(를) 구현·검증까지 완결하라.

저장소: <repository-root>
먼저 docs/design/execution-prompts.md §1 공통 실행 규약 전체와 §2의 의존성을 읽고 적용하라.
enterprise-gateway-evolution.md와 acceptance-matrix.md의 최신 내용을 확인하라.
선행: P01–P13. 참조: 설계 §4, §10–12 및 전체 활성 인수 조건.
execution-status.md의 미해결 필수 F-ID를 먼저 재현·처리하고 선행 증거가 없으면 완료를 가정하지 마라.
전체 목표가 이미 이 단위를 포함하면 새 목표 없이 이어가고, 개별 실행이면 이 단위만 목표로 삼아라.

1. execution-status.md의 전체 F-ID와 활성 A-ID를 재검토한다. 누락/실패/영향받은 오래된 PASS를 담당 단위로 돌리고 수정·회귀를 마친다. 필요 신규 조건 A66+도 검증한다.
2. 구현된 모든 API의 OpenAPI request/response/error/SSE fixture와 Java/Spring BFF·Python/FastAPI 호출을 정렬한다. 자동 retry 억제·deadline·UNKNOWN 조회·비노출 필드·성공/오류 의미를 end-to-end로 검증한다.
3. 단위/MVC/필수 Testcontainers/Konsist/Kover/소비자 계약 suite를 실행한다. skip된 container suite나 coverage 숫자만으로 기능 완료를 주장하지 않는다.
4. 3개 이상 독립 replica에서 혼합 JSON/SSE/Embeddings/Rerank/Responses 부하와 SIGTERM drain·worker rollout·PG 복구를 시험한다. offered/admitted/completed/rejected, TTFT·꼬리 지연·memory/pool·원장 누락·공정성을 보고한다.
5. 승인된 workload/SLO·model/region/profile smoke·cell/HA 결과와 runbook·migration/rollback·최소권한·비밀 비노출 증거를 최신 코드 기준으로 정리한다. 실제 유료 smoke는 명시적 허가가 있을 때만 수행한다.
6. A07/A44–A46과 전체 활성 조건을 닫는다. 미해결 필수 발견 사항, 환경 대기, 보강해야 할 계약이 하나라도 남으면 다음 턴 첫 수정/검증을 인계하고 전체 목표를 완료 처리하지 않는다.
완료 기준: 공통 규약 §1.5 전체 충족. 최종 보고는 구현 범위·실행 증거·운영 제한·미해결 필수 0건을 명시하며, 별도 허가 없는 커밋/푸시/실제 배포는 하지 않는다.

계획 밖 결함·누락은 공통 규약 §1.4에 따라 근거/재현·영향 gate·다음 행동으로 기록하라. 다음 턴에는 필수 항목을 우선 수정·회귀하고, 그것이 남아 있으면 이 단위를 완료 처리하지 마라. 종료 전 실행 증거와 다음 턴 첫 행동을 갱신하라. 실제 배포·유료 호출·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

## 5. 다음 턴 재개·보강 프롬프트

진행 중인 전체/단위 목표에 사용하는 프롬프트다. 이미 완료된 다른 목표를 임의로 다시 열지 않는다.

```text
현재 LLM 미디에이터 Gateway 목표의 작업을 이어가라.
저장소: <repository-root>

docs/design/execution-prompts.md §1과 §2, execution-status.md의 현재 단위·미해결 발견 사항·최신 검증·다음 턴 인계를 먼저 읽어라. 이어 선택한 P-ID의 §4 명세와 연결된 설계 절·인수 조건·실제 코드 및 작업 트리 변경을 확인하라. 처음 실행하거나 범위가 바뀌었다면 전체 설계·인수 조건을 읽고, 이후에는 관련 변경과 선행 증거를 중심으로 확인하라. 이전 답변의 완료 표현보다 실제 코드·gate 실행 증거를 우선하라. 상태 파일이 없으면 P00 방식으로 현재 상태를 재구성하고 과거 PASS를 만들어내지 마라.

1. 현재 목표 범위를 확인하여 같은 범위면 유지한다. 다른 활성 목표면 전환을 질문한다. 목표가 없다면 기록된 전체 또는 단위 범위의 미완료 작업을 목표로 재개한다.
2. 미해결 F-ID를 확인해 타당한 필수 결함·설계 누락부터 처리하라. 환경이 그대로인 대기 항목은 같은 probe를 반복하지 말고 재개 조건을 유지하라. 범위 밖 제안은 분리하고 새로운 API·인프라·권한 확대는 승인받아라.
   직전 검증 결과가 미확인이면 새 수정 전에 세션 종료·원본 보고서·검증 당시 코드 기준을 확인하라. 세션이 사라졌다는 사실이나 현재 XML의 성공만으로 직전 명령 전체의 성공을 추정하지 마라. 결과와 코드 기준을 복원할 수 없으면 미확인으로 남기고, 필요한 검증을 안전하게 재실행하라.
3. 지금 실행 가능한 하위 작업 하나를 선택하여 §1.3 작업 카드에 실제 파일·불변식·연결 경로·A-ID·검증 명령을 채운 뒤 구현하라. 이미 완료된 구현은 재사용하고 새로 발견한 누락만 수정하라. 카드 작성만으로 턴의 구현을 대신하지 마라.
4. 수정 후 해당 실패와 인접한 계약·동시성·금전·권한·stream 회귀를 실행하라. 관련 문서/gate와 결과 기준을 갱신하고 근거가 있는 항목만 종결하라. 새 발견도 같은 F-ID 절차에 등록하라.
5. 그 뒤 현재 P-ID의 남은 구현/검증, 다음 실행 가능한 P-ID 순서로 진행하라. 외부 gate만 대기라면 독립 작업은 계속하되 미검증 상태를 숨기지 마라.
6. 종료 전 남은 필수 F-ID, 변경 파일/심볼, 정확한 다음 명령·예상 검증, 외부 입력 질문을 기록하라. 실행 중인 검증은 session과 상태를 남기고 결과를 추정하지 마라. 작업이 남으면 목표를 complete로 처리하지 마라.

테스트 통과를 위해 기능·보안·hard budget을 끄거나 gate를 약화하지 마라. 임의 커밋/푸시·유료 호출·production 조작은 하지 마라.
```

### 턴 종료 시 제공할 구체적인 재개 프롬프트

위 재개 블록은 언제든 사용할 수 있는 공통본이다. 실제 작업 턴을 끝낼 때는 아래 틀의 대괄호를 **관찰한 값으로 채워** 응답에 함께 제공한다. 실행 기록은 `execution-status.md`만 갱신하고 별도 인계 파일을 만들지 않는다. 미확인 명령·심볼·테스트 이름은 추정해 채우지 말고 먼저 확인할 대상으로 적는다. 다음 턴에는 이 사본보다 최신 실행 기록과 작업 트리가 우선한다.

```text
현재 LLM 미디에이터 Gateway 목표를 이어가라. 새 목표를 중복 생성하지 마라.
저장소: <repository-root>

docs/design/execution-prompts.md §1·§2·§5와 docs/design/execution-status.md를 읽고, 아래 인계를 최신 코드·설계·인수 조건과 대조하라.

- 이어갈 단위: [P-ID 및 하위 작업 ID]
- 직전 변경과 검증의 경계: [반영한 불변식 / 실제 통과 범위 / 실패·미실행 범위]
- 우선 발견 사항: [필수 F-ID와 영향 A-ID, 없으면 없음]
- 첫 확인 대상: [실제 파일·심볼 / 작업 트리 변경 / 결과 확인이 필요한 세션·보고서]
- 다음 행동: [재현할 실패 → 수정할 원인 → 실제 호출 경로에 연결할 변경]
- 검증: [저장소 기준 작업 디렉터리·정확한 명령·필요 환경·기대 assertion]
- 대기 조건: [환경·승인·입력과 해당 gate; 변화가 없을 때 가능한 독립 작업]

필수 결함을 재현·수정하고 실패 경로와 인접 회귀를 검증하라. 발견 사항의 등록 자체나 테스트 파일 작성만으로 해결 처리하지 마라. 새 결함도 같은 절차로 연결하고, 현재 작업 뒤에 실행 가능한 필수 작업이 남으면 계속하라.

종료 전 실제 명령·종료값·결과 경로·검증 코드 기준과 다음 턴 첫 행동을 실행 기록에 반영하라. 필수 gate 또는 보강이 남아 있으면 목표를 완료 처리하지 마라. 유료 호출·실제 배포·비밀 변경·커밋·푸시는 별도 허가 없이는 하지 마라.
```

### 최종 검토에서 추가 보강이 발견됐을 때

```text
LLM 미디에이터 Gateway의 최종 검토에서 발견된 미흡점을 보강하고 재검증하라.
저장소는 <repository-root> 이다.
docs/design/execution-prompts.md §1과 execution-status.md의 미해결 발견 사항을 기준으로 하라.

현재 전체 목표가 활성 상태면 그 아래 복구 작업으로 수행한다. 이미 완료됐거나 목표가 없으면 기록된 필수 발견 사항의 보강·회귀를 새 목표로 시작한다. 다른 활성 목표를 덮어쓰지 않는다.
각 발견 사항의 타당성·재현을 확인하고, 원인 수정→필요한 인수 조건 보강→관련 P-ID 재검증→전체 영향 gate 회귀를 수행하라. 수정한 코드에 영향을 받는 과거 PASS는 다시 확인하라.
필수 보강이 남은 상태를 문서만 정리해 완료로 보고하지 마라. 근거 없는 개선 제안은 채택하지 않은 이유와 함께 분리하라.
새롭게 발견한 사항도 같은 F-ID 절차로 다음 턴에 연결하며, 외부 결정/검증이 없으면 정확한 대기 상태와 다음 행동을 남겨라.
완료 시 수정 범위·실제 검증·남은 운영 제한을 보고하되 전체 제품의 준비 수준을 이번 보강 범위보다 넓게 주장하지 마라. 별도 허가 없이 커밋·푸시·실제 배포는 하지 마라.
```

## 6. 프롬프트 작성 단계의 검증 범위

이 문서는 섹션별 작업·선행 관계·61개 활성 gate의 주 담당과 재개 절차를 정한 실행 명세다. 실제 구현 상태를 판정하지 않는다. 실행 시 정확한 Gradle/컨테이너/부하 명령을 탐색하여 기록해야 하므로 존재를 확인하지 않은 task 이름이나 환경별 endpoint를 명령으로 고정하지 않았다.

최종 보고는 “구현 완료”, “실제 검증 완료”, “운영 환경 검증 대기”를 구분한다. 외부 검증이 남아 있다면 단순히 다음 턴을 반복하는 대신 필요한 모델/권한/환경/SLO와 재개 조건을 구체적으로 제시한다.
