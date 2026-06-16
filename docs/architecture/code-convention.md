# magicJar Code Convention (v2)

> **Status**: ACTIVE — 마스터 아키텍처 정합  
> **참조**: `docs/architecture/master-architecture.md` · `docs/architecture/development-rules.md` · `_workspace/arch/adr/`

이 문서는 Claude Code가 magicJar repo에서 코드를 수정할 때 반드시 지켜야 할 작업 원칙과 코드 컨벤션이다.

## 1. Project Overview

magicJar는 룰 기반 quant trading 시스템 + AI 옵션 분석 보조 + 미래 MCP 서버 노출의 멀티 모듈 구조다.

```text
.
├─ domain/     Kotlin java-library, pure domain and ports only, no Spring
├─ api/        Spring Boot, REST, @Scheduled, STOMP, 외부 어댑터 (KIS·Yahoo·DART·Naver)
├─ batch/      Spring Boot, Spring Batch (KIS REST·종목 마스터·백테스트·corp action)
├─ consumer/   Spring Boot, Spring Kafka, KIS WS bridge, signal engine, order, journal, AI 분석 보조
├─ frontend/   React 19, TypeScript, Vite, shadcn/ui, TanStack Query, Zustand, Lightweight Charts v4
├─ docker/     multi-stage, non-root images, compose modes: full, infra, dev override
└─ scripts/    smoke.sh, task.mjs, render-status.mjs, build-and-deploy.sh
```

**Core stack**:
- JVM: Kotlin 2.0 (K2), JDK 21, Spring Boot 3.3, Spring AI 1.0.0-M3, Spring Kafka, Spring Batch, Gradle Kotlin DSL
- Frontend: React 19, TypeScript, Vite, shadcn/ui, TanStack Query, Zustand, TradingView Lightweight Charts v4 (자체 도형/주석/패턴은 official API 위에 — ADR 0025)
- Infra: PostgreSQL 16, Kafka 3.7 KRaft, Redis 7, KIS OpenAPI REST/WebSocket
- Docker: local integrated MSA, multi-stage builds, non-root runtime

## 2. Non-Negotiable Rules

1. Do not break module boundaries.
2. `domain` must not depend on Spring, Kafka, HTTP clients, database drivers, web frameworks, or external infrastructure.
3. Business rules live in `domain`; infrastructure orchestration lives in `api`, `batch`, or `consumer`.
4. Do not introduce duplicate models for the same concept unless boundary reason (API DTO, persistence entity, vendor payload).
5. Do not silently swallow exceptions. Convert to typed domain results, typed errors, or logged operational failures.
6. Do not add global mutable state.
7. Do not hard-code secrets, account numbers, API keys, tokens, endpoints, credentials.
8. Prefer small, testable changes over broad rewrites.
9. **Code first, test last**: 트랙 코드 완성 후 데이터 흐름·사용자 시퀀스 기반 일괄 테스트 (§9·§24 참조). 테스트 작성하다가 코드 방향 흝뜨림 절대 금지.
10. If unsure whether a change belongs in `domain` or app module, keep `domain` pure.
11. Use only official TradingView Lightweight Charts v4 APIs. 자체 도형·주석·패턴은 official primitives(Series·LineSeries·Markers·Plugins) 위에서 조립. monkey-patch·internal access 금지.
12. **Never default new trading or order code to real-money execution.** Variant 명명 명시(§4.2).
13. Keep external integrations behind ports/adapters. KIS·Spring AI·Kafka·Redis·PostgreSQL·외부 HTTP는 domain 진입 금지.
14. **AI 매매 결정권 0**. AI는 사용자 트리거 + 코멘트 산출만. 어떤 코드도 AI 응답을 매매 트리거로 사용하면 안 됨 (§13).
15. **모든 본체 컴포넌트는 오프라인(AI·외부 API 끊김) 동작 가능해야 함** (§21).

## 3. Architecture Rules

### 3.1 Domain Module

`domain` contains pure Kotlin domain logic.

**Allowed**:
- Entities, value objects, domain services
- Domain events
- Use cases that depend only on ports
- Port interfaces (`{Capability}Port`, `{ExternalSystem}Port`, `{RepositoryLike}Repository`)
- Domain exceptions and typed failure models (sealed class 권장)
- Pure calculation, validation, strategy, order, journal, market, session 로직
- AI analysis contracts and normalized result models (provider-specific client code 제외)
- 시크릿·사용자 도메인 모델 (`Secret`·`User`·`UserTradingConfig` — 암호화 알고리즘 자체는 application module로)

**Forbidden**:
- `org.springframework.*`
- Spring AI 클래스 (`ChatClient` 등)
- JPA·Kafka 어노테이션/클래스
- HTTP 클라이언트 (`RestClient`·`WebClient`·`OkHttpClient`)
- JSON 직렬화 어노테이션 (이미 표준화된 공유 DTO 외)
- DB·Redis·WebSocket·파일 시스템·환경 변수 접근

**Package intent** (`com.mj.domain.{X}`):

| 패키지 | 책임 |
|---|---|
| `common` | 공유 primitive·money·time·identifier·result type |
| `market` | tick·candle·quote·symbol·session·session FSM |
| `strategy` | signal·indicator·strategy rule·DSL·backtest-friendly logic |
| `order` | order·execution·position·risk check (Guard chain 정의) |
| `journal` | trade journal·decision·reasoning·performance record |
| `ai` | **AI port + 결과 모델만**. 본체 흐름은 호출하지 않음 (옵션 분기에서만 호출). provider-specific 코드는 application/infra |
| `news` | 시장 뉴스 도메인·source 추상화·정규화 |
| `secret` | 시크릿 도메인 모델·인증 정보 (암호화 자체는 application) |
| `user` | 사용자·trading config·multi-tenant ownership |

**중요**:
- `domain.ai`는 마스터 §3 컴포넌트 맵에서 **옵션 컴포넌트**다. 본체 use case가 `domain.ai` port를 호출하면 비전 위반. 사용자 트리거 진입점(controller·application service)에서만 호출.
- 새 도메인 패키지 추가 시 마스터 §3 컴포넌트 맵 갱신 의무.

### 3.2 Application Modules

`api`, `batch`, `consumer`는 Spring Boot 앱 모듈.

**Responsibilities**:
- Wire domain use cases to infrastructure
- Implement domain ports (Adapter 구현)
- Spring 설정 소유
- Controller·scheduler·Kafka listener·STOMP handler·batch job 소유
- 외부 DTO → domain 모델 boundary 변환
- Domain result → 외부 응답 DTO 변환

**Rules**:
- Controller는 비즈니스 로직 X
- Kafka listener는 application service에 빠르게 위임
- Scheduled method도 application service 위임
- Constructor injection only (field injection 금지)
- `@Transactional`은 application service 메서드에
- Bean Validation은 controller 경계
- Spring AI·Spring Kafka·Spring Batch·persistence·Redis·KIS 접근은 adapter로
- Adapter-specific DTO는 domain 패키지 진입 금지
- Cross-cutting manager class 대신 use case 단위 application service

**모듈별 주 책임**:
- `api`: REST·@Scheduled·STOMP fanout·Flyway 마이그레이션 baseline·외부 HTTP 어댑터 (KIS·Yahoo·DART·Naver)
- `batch`: KIS REST 어댑터·종목 마스터 sync·corp action·백테스트 job·일봉 backfill
- `consumer`: KIS WS bridge·tick fanout·signal engine·order send/audit·journal 기록·AI 분석 보조 진입점

### 3.3 Frontend

**Responsibilities**:
- UI 상태·표시
- REST/STOMP client 통합
- Chart 렌더링 (Lightweight Charts v4 + 자체 도형/주석/패턴은 official primitives 위)
- 사용자 입력 검증·포맷팅
- Server state는 TanStack Query
- Local UI state는 Zustand

**Rules**:
- Functional component + hooks
- API 호출은 dedicated client/service module
- Chart setup/cleanup은 focused chart component/hook 안
- Domain 계산은 component 깊이 진입 금지 — pure function 추출
- API 응답은 명시 type
- `any` 회피 → `unknown` + parsing/narrowing
- shadcn/ui primitive 일관 사용
- Server state를 Zustand 저장 금지 (TanStack Query 영역)
- TanStack cache state를 local state로 중복 보유 금지

**Charts (LWC v4 + 자체 도형 — ADR 0025)**:
- 인스턴스 생성/dispose는 예측 가능한 lifecycle
- 데이터 변환은 chart 렌더링과 분리
- Source market data 배열 in-place mutation 금지
- 시간 변환 utility 한 곳에 집중
- **자체 도형/주석/패턴 시각화는 official Series API + plugin/primitives 조합**. internal 접근·monkey-patch 금지. TradingView Advanced 의존 금지.

## 4. Kotlin Convention

### 4.1 Style

- Kotlin 2.0 (K2), JDK 21, Kotlin official style, 4-space indent
- Expression body for short obvious functions
- `val` over `var`
- Immutable DTO/value object는 data class
- 작고 의도가 드러나는 함수
- 약어는 도메인 표준만 (`KIS`·`AI`·`DTO`·`WS`·`MCP`·`DSL`)
- Java time API (`Instant`·`LocalDate`·`LocalDateTime`·`ZoneId`) — legacy date 금지
- 가격·수량·금액은 `BigDecimal` 또는 explicit minor unit. **floating point 금지** (트레이딩 정확성 본질)

### 4.2 Naming

- Classes/objects: `PascalCase`
- Functions/properties: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Test classes: `{Subject}Test`
- Spring services: `{UseCaseName}Service` 또는 `{Capability}ApplicationService`
- Port interfaces: `{Capability}Port`·`{ExternalSystem}Port`·`{RepositoryLike}Repository`
- **Adapter implementations**: `{Variant?}{ExternalSystem}{PortName}Adapter`
  - Variant은 옵션. Real·Stub·Mock·Fake 중 하나
  - 예: `RealKisOrderAdapter`·`StubKisRestAdapter`·`KillSwitchJpaAdapter`·`UserJpaAdapter`
  - 외부 시스템 단일 + persistence 어댑터인 경우 Variant 생략 (`OrderJpaAdapter`)
  - Variant은 trading safety (§14) 핵심 — Real ↔ Stub/Mock 명시 분리

```kotlin
interface KisRestPort {
    fun fetchCandles(symbol: Symbol, interval: Interval): List<Candle>
}

class RealKisRestAdapter(
    private val client: RestClient,
    private val tokenManager: KisTokenManager,
) : KisRestPort

class StubKisRestAdapter(
    private val fixture: KisFixtureLoader,
) : KisRestPort
```

### 4.3 Nullability

- Domain 모델 nullable 회피. 부재가 의미 있을 때만 허용
- 예상 실패는 sealed result type 또는 `Result`-like wrapper
- `!!` 사용 금지 (test·증명된 unreachable 외)

### 4.4 Error Handling

- 예상 도메인 실패는 명시 표현 (sealed class)
- 예상 외 인프라 실패는 context와 함께 로그 + 경계에서 변환
- Vendor-specific error를 domain caller에 노출 금지 (예: KIS error code → `KisApiException` → `OrderRejectReason.KisApiError`)

### 4.5 Spring

- Constructor injection only
- `@ConfigurationProperties` 선호
- Field injection 금지
- `@Transactional`은 application service
- Bean Validation은 controller 경계
- Spring Boot 3.3 idiom, 폐기 설정 회피
- Spring AI 1.0.0-M3는 application/infra 모듈에만. domain 노출 금지
- STOMP topic 명명 안정·신규 destination 문서화 의무 (마스터 §4 데이터 플로우 참조)
- Scheduled job은 overlap·idempotency·retry 명시

### 4.6 Gradle

- Kotlin DSL
- 버전 카탈로그/convention plugin이 있으면 중앙화
- `domain` 의존성은 framework-free·domain-safe만
- 모듈별 dependency, root-level leakage 회피
- Test fixture 명시. `domain`이 app 모듈 의존 금지

## 5. Frontend Convention

### 6.1 TypeScript

- strict TypeScript
- `any` 회피
- Simple object/union: `type`, 확장/구현: `interface`
- API 모델과 UI view model 분리 (diverge 시)

### 6.2 React 19

- Functional component
- 컴포넌트 파일 focused
- 재사용 hook 추출은 reuse/complexity가 정당화할 때만
- `useMemo`/`useCallback` 남용 금지 (측정·명백 이유 있을 때만)
- Side effect는 `useEffect` 또는 dedicated hook
- Subscription·timer·chart instance·STOMP connection clean up
- Server mutation은 explicit, TanStack cache invalidation 의도적
- Zustand store는 small·feature-scoped·client-only

### 6.3 Styling

- 기존 styling 시스템 따름
- shadcn/ui primitive 선호
- magic color·반복 spacing 회피, token/CSS variable 사용
- Responsive 명시
- shadcn/ui generated component 강한 fork 금지

### 6.4 Charts (LWC v4 — ADR 0044)

- LWC instance 생성·dispose 예측 가능
- 데이터 변환과 chart 렌더링 분리
- Source market data 배열 mutation 금지
- 시간 변환 utility 중앙화
- **Official LWC v4 API only**. 자체 도형·주석·패턴은 Series·Markers·Plugins/Primitives 조합으로 구현. internal 접근 금지
- Chart 진입 전 데이터 정규화

## 7. API and DTO Rules

### 7.1 Boundary Mapping

- 외부 request DTO → application command/query → domain model
- Domain result → application response → 외부 response DTO
- Raw controller DTO를 domain 깊이 전달 금지

### 7.2 Response Shape

- 일관 envelope 사용 (있으면). 새 envelope 도입 시 모든 caller 갱신 의무

### 7.3 Validation

- 문법·shape: API boundary
- 비즈니스 invariant: domain
- Frontend validation 단독 의존 금지

## 8. Logging and Observability

- Application boundary에서 운영 이벤트 로그
- 안정 식별자 포함 (symbol·order id·strategy id·journal id·correlation id)
- secret/token/credential/account 정보 로그 금지
- `info`: 의미 있는 lifecycle
- `warn`: 회복 가능 비정상
- `error`: 주의 필요 실패
- Hot market-data path 노이즈 회피

## 9. Testing Convention

> **v2 정책**: 코드 완성 후 데이터 흐름·시퀀스 기반 일괄 테스트 (§24 참조). 트랙 진행 중 테스트 우선 도입으로 코드 방향 흝뜨림 금지.

### 9.1 Domain Tests

- Fast·deterministic
- Spring 없이 비즈니스 룰 테스트
- Fixed clock/time
- Edge case 커버 (order sizing·risk check·signal generation·market session)

### 9.2 Spring Tests

- Slice test 선호
- Full context는 wiring 검증 필요할 때
- 외부 시스템 mock (KIS·Kafka·AI provider·broker API)
- 실제 네트워크 의존 회피
- Kafka flow는 idempotency·retry·duplicate 검증
- Batch는 reader/processor/writer 단위 + restart 가정

### 9.3 Frontend Tests

- Pure data transform 분리 테스트
- 매매·chart·realtime 핵심 UI 동작 테스트
- REST/STOMP client mock
- TanStack Query는 test query client
- Zustand는 plain state transition

## 10. File Organization

### 10.1 Kotlin

Package-by-feature 선호.

```text
consumer/src/main/kotlin/com/mj/consumer/
├─ application/        # use case service
├─ infrastructure/
│  ├─ kis/             # KIS adapter
│  ├─ persistence/     # JPA adapter
│  └─ messaging/       # Kafka adapter
├─ config/
└─ MagicJarConsumerApplication.kt
```

`service`·`manager`·`util` generic 패키지 회피. `util`은 framework-free helper만.

### 10.2 Frontend

```text
frontend/src/
├─ app/
├─ components/
├─ features/
├─ hooks/
├─ lib/
├─ services/
├─ types/
└─ utils/
```

Feature-specific code는 feature 근처. 공유는 reuse 명확해진 후 promote.

## 11. Configuration

- Runtime config: env var·Spring config·typed config file
- Local dev default는 safe (예: trading mode default = mock)
- Real secret commit 금지
- 새 env var 추가 시 문서화
- Docker compose 3 모드 유지 (full·infra·dev override)
- KIS REST/WS 자격증명은 env/secret manager
- Paper ↔ real trading mode는 explicit config (§14)
- Kafka·PostgreSQL·Redis 연결은 env-driven
- Spring `@ConfigurationProperties` 사용

### 11.1 Infrastructure

- PostgreSQL 16 = primary
- Kafka 3.7 KRaft (ZooKeeper 가정 금지)
- Redis 7 = cache·lightweight state
- KIS REST/WS는 adapter 격리
- Local compose는 infra 독립 시작 가능

## 12. Concurrency and Realtime Rules

- Market data = high-volume·latency-sensitive
- Hot realtime path에서 blocking call 회피
- Backpressure·buffering·throttling 의도적
- WebSocket/STOMP subscription 정리 의무
- Kafka consumer idempotent·safe retryable
- Order execution 중복 제출 회피
- KIS WS reconnect는 duplicate subscription 회피
- REST polling과 WS-driven update 충돌 방지
- Order·signal·AI analysis·journal에 correlation id/deterministic key

## 13. AI Integration Rules (v2 — 옵션 보조 한정)

> **v2 핵심 원칙 (마스터 §2 #1·#2)**:
> - **AI는 옵션 분석 보조**. 본체 흐름은 AI 호출 0
> - **AI 매매 결정권 0**. AI 응답이 BUY/SELL 트리거 절대 X
> - 호출 진입점은 사용자 명시 트리거 (버튼 클릭) 또는 W4 분석 보조 영역만

**Rules**:
- **AI output은 매매 명령으로 사용 금지**. 사용자에게 보여줄 코멘트·분석·해설로만 변환
- AI provider-specific 코드는 `domain` 외 (application/infra)
- `domain.ai`는 port + 결과 모델만. **본체 use case가 호출하면 비전 위반**
- AI 호출은 옵션이므로 실패·timeout·malformed가 본체 흐름 차단하면 안 됨 (graceful degradation)
- Prompt/template은 유지 가능한 위치에 (application 모듈 또는 `_workspace/ai/prompts/`)
- AI request metadata는 careful logging (sensitive account 데이터 제외)
- AI 응답은 normalization + validation 후 사용자에게 노출
- Spring AI 1.0.0-M3은 application/infra만
- AI analysis 영속 시 prompt version·model/provider·input summary·normalized output 보존 (audit/reproducibility)
- Malformed/partial/low-confidence는 recoverable failure (절대 trading approval 아님)

**MCP 서버 모드 (W4 — ADR 0045)**:
- magicJar는 MCP **서버**. AI client (Claude·Cursor·기타)가 magicJar tool 호출
- 노출 후보: `get_candles`·`run_screener`·`get_journal`·`explain_indicator`·`backtest_strategy`
- AI client가 우리를 사용하는 구조 — 우리가 AI를 사용하는 것 아님

## 13.1 KIS Integration Rules

- KIS REST/WS DTO는 adapter/infra 패키지에만
- KIS market/order payload → domain model boundary 변환
- Token refresh·rate limit·reconnect·heartbeat·vendor error code 명시 처리
- KIS WS bridge는 `consumer` (domain 금지)
- Raw KIS credential·account secret·full Authorization header 로그 금지
- Mock/paper ↔ live KIS 분리는 explicit config + Variant 명명 (`StubKisXxxAdapter` ↔ `RealKisXxxAdapter`)

## 14. Trading Safety Rules (v2 강화)

**Order placement**:
- 명시적·auditable·테스트 (가능한 경우) 커버
- Paper ↔ real trading 명명 명시 (Variant prefix)
- **Real-money default 금지**. 신규 trading 코드는 mock/stub default
- Profile 분기로 실거래 진입 (mock/dev/prd · trading_mode = MOCK_KIS|REAL_KIS)

**Risk Guard chain 6단** (마스터 §3 + ADR 0042):
1. **KillSwitch** — 전역 kill 스위치 (DB 상태 기반)
2. **MarketSession** — 장외/휴장 차단 (ADR 0032)
3. **DailyOrderLimit** — 일일 주문 카운터
4. **PositionLimit** — 종목별·전체 포지션 한도
5. **DrawdownStop** — MDD 임계 도달 시 신규 진입 차단
6. **PriceSanity** — 전일 종가 ±N% 범위 검증

각 Guard는 독립 application service. 순서 명시 (`@Order`).

**REJECTED audit trail** (ADR 0034):
- Guard reject·KIS exception·MarketSession reject 모두 `orders` row INSERT (`status=REJECTED`) + `order_events` row INSERT (`event_type=REJECTED`, reason 포함)
- 멱등 키 정책 유지
- `kis_order_no` NULLABLE + partial UNIQUE index

**Duplicate prevention**:
- Retry·Kafka redelivery·scheduler rerun·UI 반복 클릭 모두 고려
- correlation id / deterministic key 사용

**Multi-tenant** (ADR 0043):
- 모든 user-scoped 테이블에 `user_id` 컬럼
- 1번 트랙에서 도입된 `users`·`user_trading_config`·`orders.user_id`·`order_events.user_id`·`daily_order_counter.user_id` 표준
- 인증 도입은 post-MVP (현재 default user 시드)

## 15. Scripts and Automation

- 스크립트 deterministic·rerun-safe
- `scripts/smoke.sh` = 통합 smoke entrypoint
- `scripts/task.mjs` (ADR 0018) = 트랙 task 파일 CLI
- `scripts/render-status.mjs` = 하네스 대시보드 상태 렌더
- `scripts/build-and-deploy.sh` = host gradle bootJar + docker compose build/up 헬퍼
- 명확 CLI argument·exit code
- 잘못된 input에 loudly fail
- Command failure 숨김 금지

## 16. Docker Convention

- Infra ↔ application 분리
- Compose 모드 유지: full · infra · dev override
- Image에 secret bake 금지
- 의존 서비스에 healthcheck
- Port·service name 문서화
- Multi-stage build
- Runtime container는 non-root
- JVM·Python·frontend layer cache 친화
- ZooKeeper 가정 금지 (Kafka KRaft)

## 17. Git and Change Discipline

**ADR 0017 — Author 분리 의무**:
- 화이트리스트: `sp-daewoon@gmail.com` · `seonjupark94@gmail.com` · `bot+<agent>@magicjar.local`
- Husky `pre-commit` hook이 강제 (off-list reject)
- 머지 정책: `--rebase` (`--squash`는 GitHub UI가 author를 web user로 덮어씀)
- 에이전트별 commit author:
  - architect → `bot+architect@magicjar.local`
  - backend-core → `bot+backend-core@magicjar.local`
  - market-data → `bot+market-data@magicjar.local`
  - strategy-engine → `bot+strategy-engine@magicjar.local`
  - ai-engineer → `bot+ai@magicjar.local`
  - frontend → `bot+frontend@magicjar.local`
  - qa → `bot+qa@magicjar.local`
- Commit 명령:
  ```bash
  git -c user.name="magicjar-<agent>" -c user.email="bot+<agent>@magicjar.local" \
      commit -m "feat(<scope>): ..."
  ```

**ADR 0018 — Task 파일 시스템**:
- 트랙 시작 시 `node scripts/task.mjs start --agent <a> --goal "..."` 의무
- 결과물 완료 → `task.mjs check "<항목>"`
- 막힘 → `task.mjs block --reason "..."`
- PR 생성 → `task.mjs done --pr <N>`
- 머지 후 → `task.mjs archive T-...`

**Branch 전략** (ADR 0015):
- `agent/<agent>/<track>-<slug>` 명명
- main rebase 머지 직행 (`agent/* → main`)
- qa 영속 브랜치 폐기
- `release/v*`은 immutable (push·머지·삭제 금지 — Husky 차단)

**Change discipline**:
1. 관련 파일 먼저 inspect
2. Smallest coherent change
3. 무관 파일 reformat 금지
4. 작업 외 rename 금지
5. 사용자 변경 revert 금지 (명시 요청 외)
6. 가장 좁은 관련 test/check 실행
7. 무엇이 바뀌고 무엇을 검증했는지 설명

## 18. Claude Code Working Instructions

**Before editing**:
- 근처 파일·기존 패턴 read
- 변경 소유 모듈 식별
- domain·infra·UI·script 어디 영향인지 확인
- **마스터 §X 어디에 위치하는 변경인지 명시** (§24)

**While editing**:
- 기존 style 보존 (이 문서와 충돌 외)
- Explicit name > clever abstraction
- 코드 자체로 자명하지 않을 때만 코멘트
- 광범위 framework·dependency 도입 금지 (필요 명확할 때만)

**After editing**:
- 관련 test·linter·build·smoke 실행 (가능 시)
- 검증 불가 시 사유 명시
- 동작 변화 요약 (파일 변화 아님)

## 19. Preferred Verification Commands

```bash
# Kotlin/Spring
./gradlew test
./gradlew :domain:test
./gradlew :api:test
./gradlew :batch:test
./gradlew :consumer:test

# Frontend
cd frontend && npm run lint
cd frontend && npm run test
cd frontend && npm run build

# Smoke
./scripts/smoke.sh
```

## 20. Definition of Done

변경 완료 조건:
- 올바른 모듈이 로직 소유
- domain framework-free 유지
- Public API·DTO typed·validated
- Realtime/order path duplicate·retry 안전
- 관련 test/check pass (또는 미실행 사유 문서화)
- 사용자 가시 동작 변화·검증 요약

---

## 21. Offline-First Principle (v2 신규)

> **마스터 §2 #1**: 시스템 본체는 AI·외부 인터넷 없이 동작 가능.

**필수 검증**:
- 모든 본체 컴포넌트(종목 마스터·지표·DSL·시그널·Guard·주문·매매일지·차트 화면)는 AI provider/외부 데이터 source 없이도 작동
- 시세 source 끊김 시 fallback (cache·last-day-close + stale=true 표시 — ADR 0033)
- AI 호출 실패는 사용자에게 "분석 일시 불가" 표시. 본체 흐름 차단 X
- 데이터 흐름도에 "오프라인 경로"가 명시되어야 함

**위반 패턴**:
- 본체 use case에서 `Anthropic*Adapter`·`Gemini*Adapter` 직접 의존
- 시세 fallback 없는 quote 표시 (live source 끊기면 화면 멈춤)
- AI 응답 대기 동안 주문 처리 차단

## 22. Indicator Source Pluggability (v2 신규 — ADR 0039)

> **마스터 §2 #4**: 지표는 source 추상화 plug-in.

**Port**:
```kotlin
interface IndicatorSource {
    fun availableIndicators(): List<IndicatorDescriptor>
    fun compute(spec: IndicatorSpec, candles: List<Candle>): IndicatorSeries
}
```

**Adapter 후보**:
- `KisStrategyBuilderIndicatorSource` — KIS 80지표 import (1차)
- `NativeIndicatorSource` — Kotlin 자체 구현 (성능 핵심 지표)
- `KrxIndicatorSource` — KRX 공식 지표 (향후)

**Rules**:
- 새 source 추가 시 `IndicatorSource` 구현만. 본체 코드 수정 0
- IndicatorSpec은 source-agnostic (sma/ema/rsi/macd 등 표준 ID + params)
- Source-specific quirk는 adapter 안에서 정규화
- `domain.strategy`에 IndicatorDescriptor·IndicatorSpec·IndicatorSeries 정의

## 23. MCP Server Pattern (v2 신규 — ADR 0045)

> **마스터 §2 #3**: magicJar는 MCP **서버**. AI client가 호출.

**역할 분리**:
- magicJar = MCP server (W4에서 노출)
- Claude·Cursor·기타 AI = MCP client
- 우리가 AI를 사용하는 게 아님 — AI가 magicJar를 사용

**노출 후보 tool**:
- `get_candles(symbol, interval, lookback)`
- `run_screener(universe, dsl)`
- `get_journal(date_range, strategy_id)`
- `explain_indicator(name)`
- `backtest_strategy(yaml, range)`

**Rules**:
- MCP tool 정의는 application 모듈 (예: `api/mcp/`)
- Tool은 internal use case 위에 thin wrapper
- 인증·범위·rate limit 명시
- AI client에 절대 매매 트리거 권한 부여 금지 (read·analysis만)

## 24. Track-based Development (v2 신규)

> **마스터 §10 트랙 로드맵 + development-rules.md**

**모든 변경은 트랙 위에서**:
- 각 PR 본문에 "마스터 §X 어디에 위치하는 변경인가" 한 줄 의무
- 트랙 시작 시 task 파일 (§17 ADR 0018)
- 트랙 코드 완성 후 데이터 흐름·시퀀스 기반 일괄 테스트 (§9·§25)
- QA on-demand (ADR 0016) — MVP 단계는 호출 0 default. 7 트리거 시에만

**단편 수정 금지**:
- 비전 어긋나는 quick fix 금지
- 마스터 컴포넌트 맵에 자리 없는 코드 추가 금지
- 새 도메인 패키지·새 source·새 외부 연동 → 마스터 §X 갱신 의무

## 25. Test Timing v2 (v2 신규)

> **사용자 명시 (2026-05-02)**: "코드 완료 후 테스트 순서는 데이터 흐름·사용자 시퀀시 기반으로 진행. 절대 테스트 하면서 방향 흝으러짐 있으면 안 된다."

**v2 테스트 정책**:
- 트랙 진행 중 = **코드 작성 우선**. 테스트 작성으로 코드 방향 흝뜨림 금지
- 트랙 종료 시 = **데이터 흐름·사용자 시퀀스 시나리오** 기반 일괄 통합 테스트
- 단위 테스트는 도메인 로직(순수 계산)만 코드와 함께 작성 OK
- 통합·e2e 테스트는 트랙 종료 후 일괄
- QA 게이트는 MVP 단계 0 호출 (ADR 0016)

**시나리오 우선순위**:
1. 사용자 진입 시퀀스 (Dashboard → 종목 검색 → 차트 → 시그널 확인 → 매수)
2. 데이터 흐름 (KIS WS → tick → Kafka → STOMP → frontend)
3. Guard 시나리오 (장외 시간 매수 시도 → REJECTED)
4. 백테스트 흐름 (DSL upload → 과거 데이터 → 결과 표시)
5. AI 옵션 흐름 (사용자 분석 버튼 → AI 코멘트 표시. 실패 시 graceful)

## 26. Reactor `.block()` non-blocking 계약 (v2 신규 — ADR 0099)

> **권위**: `_workspace/arch/adr/0099-reactor-block-non-blocking-contract.md` 본문 LOCK. 본 § 본문은 ADR 0099 본문 인용 + Before/After 예시 + async re-entry path 5종 + BlockHound 통합 테스트 의무 본문화.

### 26.1 root cause — `publishOn` vs `subscribeOn` 의미 차이

v3.8.0 cut hotfix가 실측 ERROR 잔존 root cause:

| operator | 실행 위치 변경 대상 | subscription·request·`.block()` 호출 thread |
|----------|-------------------|----------------------------------------------|
| `publishOn(Scheduler)` | downstream operator (map·filter·doOnNext 등) | **caller thread 그대로** |
| `subscribeOn(Scheduler)` | subscription chain 전체 (source emission + downstream) | **worker thread로 위임** |

→ `.block()` 호출 thread를 worker로 위임하려면 **`subscribeOn` 의무**. `publishOn`은 응답 수신 후 처리 부담만 위임 (캡슐화). 

### 26.2 WebClient + `.block()` 동시 사용 시 **2단 방어 의무**

#### 26.2.1 callee 측 — `subscribeOn(Schedulers.boundedElastic())` 부착

```kotlin
// ✅ 올바른 패턴
fun fetchExecutions(...): Response {
    return webClient.get()
        .uri(...)
        .retrieve()
        .bodyToMono(Response::class.java)
        .subscribeOn(Schedulers.boundedElastic())   // ← subscription chain 전체 위임
        .block() ?: throw KisApiException("empty response")
}

// ❌ 잘못된 패턴 (v3.8.0 hotfix가 이 함정)
fun fetchExecutions(...): Response {
    return webClient.get()
        .uri(...)
        .retrieve()
        .bodyToMono(Response::class.java)
        .publishOn(Schedulers.boundedElastic())     // ← downstream만 위임 (caller thread 그대로)
        .block() ?: throw KisApiException("empty response")
}
```

#### 26.2.2 caller 측 — async re-entry path에 `isInNonBlockingThread()` 가드

```kotlin
// ✅ 올바른 패턴 — WS reconnect callback이 reactor-http-nio thread에서 호출
fun reconnectAndRefresh() {
    if (Schedulers.isInNonBlockingThread()) {
        Schedulers.boundedElastic().schedule {
            kisAuthService.refreshToken()    // .block() 호출 caller
        }
    } else {
        kisAuthService.refreshToken()
    }
}
```

### 26.3 magicJar 통신 thread 모델

| thread prefix | source | blocking 허용 여부 | 가드 필요 |
|---------------|--------|------------------|----------|
| `reactor-http-nio-*` | Netty event-loop (WebClient HTTP 인입) | **금지** (non-blocking 전용 — BlockHound 차단 대상) | ★★★ 의무 |
| `boundedElastic-*` | Schedulers.boundedElastic() worker pool | 허용 (blocking 의도 worker pool) | 0 (이미 안전) |
| `scheduler-*` | @Scheduled cron worker (Spring TaskScheduler) | 허용 | 0 (Spring 표준) |
| `parallel-*` | Schedulers.parallel() (CPU bound) | 권장 안 함 (CPU bound 전용) | ★ caller 의도 확인 |
| `kafka-*` | Kafka listener container thread | 허용 (Spring Kafka standard) | 0 |
| `http-nio-*` | Tomcat servlet thread | 허용 (Spring MVC standard) | 0 |

### 26.4 5단 진단 체크리스트 LOCK

신규 WebClient + `.block()` 코드 작성·결함 진단 시 의무 체크리스트:

1. **에러 thread 이름으로 caller 식별** — ERROR 로그의 thread prefix 확인 (§26.3 표 참조)
2. **`publishOn` vs `subscribeOn` 의미 차이 명확화** — `.block()` thread 위임은 `subscribeOn` 의무 (§26.1)
3. **callee + caller 2단 방어** — callee `subscribeOn(boundedElastic)` + caller `isInNonBlockingThread()` 가드 (§26.2)
4. **BlockHound 호환성 통합 테스트 의무** — consumer 모듈 우선 (KIS WS callback caller 영역) + 5 시나리오 (정상·timeout·circuit break·WS reconnect·@Scheduled 동시)
5. **async re-entry path 일괄 점검** — §26.5 5종 path 점검

### 26.5 async re-entry path 5종 + 1차 방어 가드 예시

신규 코드 머지 전 다음 5종 path 점검 의무:

| # | path 종류 | caller thread 가능성 | 1차 방어 가드 |
|---|----------|-------------------|--------------|
| 1 | **WS callback** (KIS WS message·disconnect·reconnect handler) | `reactor-http-nio-*` ★ | `isInNonBlockingThread()` 가드 + worker re-schedule |
| 2 | **Kafka listener** (`@KafkaListener`) | `kafka-*` (대부분 안전) | inner async chain 점검 |
| 3 | **@Scheduled cron** (Spring TaskScheduler) | `scheduler-*` (대부분 안전) | inner Mono callback chain 점검 |
| 4 | **CompletableFuture.thenApply** | non-blocking thread 잔류 가능 | `isInNonBlockingThread()` 가드 |
| 5 | **Mono callback chain** (`doOnNext`·`doOnSuccess`·`flatMap` 등 callback 내부 `.block()`) | callback에 따라 다양 | callback 내부 `.block()` 0 권장 + 필요 시 `subscribeOn` 부착 |

### 26.6 BlockHound 통합 테스트 의무 (consumer 모듈 우선)

- 신규 WebClient + `.block()` 코드 머지 전 통합 테스트에서 BlockHound install 의무
- consumer 모듈 우선 — KIS WS callback caller 영역이 가장 race risk 높음
- 5 시나리오 ALL GREEN 의무: (1) 정상 응답 (2) timeout (3) circuit break (4) WS reconnect 중 refresh (5) @Scheduled 동시 호출

```kotlin
// 통합 테스트 setup 예시 (consumer 모듈)
class BlockHoundConsumerTest {
    init {
        BlockHound.install()   // application boot 직전
    }

    @Test
    fun `KIS WS reconnect token refresh — non-blocking thread 차단 검증`() {
        // ... 5 시나리오 ALL GREEN 의무
    }
}
```

### 26.7 적용 대상 매트릭스 (v3.8.0 cut 후 hotfix LOCK)

| 파일 | 결함 | 본 § 적용 패턴 | 사이클 |
|------|------|--------------|--------|
| `KisOrderInquireCcldHttpAdapter` | publishOn 잘못 적용 | callee subscribeOn 정정 + caller 가드 | v3.8.x patch PR-1 (market-data) |
| `KisOrderInquireDailyCcldHttpAdapter` | publishOn 잘못 적용 | callee subscribeOn 정정 + caller 가드 | v3.8.x patch PR-1 (market-data) |
| `KisExecutionBackfillService` | caller가 reactor-http-nio (WS callback) | `Schedulers.isInNonBlockingThread()` 가드 | v3.8.x patch PR-1 (market-data) |

→ 신규 WebClient + `.block()` 코드 추가 시 review checklist 5단 강제 (architect/qa Visual 의무).
