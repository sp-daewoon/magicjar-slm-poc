# magicJar v2 개발 진행 가이드 (룰북)

> **상태**: DRAFT — 사용자 검토 대기
> **작성일**: 2026-05-03
> **작성자**: architect (opus 4.7 1M)
> **선행**: master-architecture.md v2, tracks-roadmap.md v2
> **트리거**: 사용자 명시(2026-05-02) — "이젠 진짜 제대로 된 시스템. 코드 먼저 완성, 테스트는 데이터 흐름·사용자 시퀀스 기반 마지막 일괄. 테스트하면서 방향 흝뜨림 절대 X. QA 게이트 배제, 단일 책임, API 빌딩 한 스텝씩 순차"

본 문서는 **v2 베이스라인 트랙 진행 시점부터 적용되는 개발 룰**이다. 메인 세션·architect·각 sub-agent 모두 본 룰을 일상 참조.

---

## §1. 핵심 룰 5건 (사용자 명시)

### 1-1. QA 게이트 배제

- **모든 트랙·PR에서 QA on-demand 호출 0회 default**
- 기존 QA on-demand 트리거 정책 (옛 ADR — 폐기)의 7 조건은 **무시**
- 코드 품질은 다른 메커니즘으로 보장 (§2 참조)
- **Sunset 조건**: 실서비스 진입 시점(`MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` + 외부 사용자 노출). 그 시점에 본 룰 폐기 + 강제 QA 게이트 회귀 (별도 ADR 발행)

**근거**: 사용자 — 토큰 최소화 + 형식주의 회피.

### 1-2. 단일 책임 코드

- 각 모듈·클래스·함수 **단일 책임**
- 한 트랙·한 영역. 영역 간 침범 시 architect 협의 필수
- "이거 추가하는 김에 저거도" 패턴 금지
- 도메인 모델은 도메인에만, 영속은 application layer에만

**검증 방법**: PR 본문 "본 PR 영향 영역" 한 줄 — 영역 ≥2 시 사유 명시 의무

### 1-3. API 빌딩 한 스텝씩 순차

- 컨트롤러 → 서비스 → 어댑터 → 영속 **한 layer씩 완성 후 다음**
- 한 PR에서 4 layer 동시 변경 금지 (트랙2 spec/plan은 예외 — 라운드 분할로 해소)
- 라운드 분할 권장: R1 (도메인 + 시그니처), R2 (어댑터 + 영속), R3 (UI + 일괄 테스트)

**검증 방법**: 라운드별 PR 분리. R1 머지 + 빌드 통과 후 R2 진입.

### 1-4. 코드 먼저 완성 → 테스트 마지막 일괄

- 트랙 코드 R1·R2 진행 중엔 **테스트 작성 0**
- R3 마지막 단계에서 데이터 흐름·사용자 시퀀스 기반 시나리오 짜고 일괄 테스트
- **테스트 작성하다가 코드 방향 흝뜨리는 패턴 절대 금지**
- 단 명백히 단순 단위 테스트(BDD 시나리오 1~2개)는 R1·R2에서도 OK

**검증 방법**: R1·R2 PR에 테스트 파일 0건 검증. R3 PR에 일괄 테스트 추가.

**Sunset 조건**: 실서비스 진입 시. 그 시점에 단위 테스트 R1·R2 동반 작성 의무 회귀.

### 1-5. AI 호출 사용 자제

- 본 시스템 코드에서 LLM 호출은 **W4 트랙 외 0건**
- W1·W2·W3 트랙에서 AI provider import·호출 코드 발견 시 review reject
- dev에서 prompt 디버깅 비용도 통제 — `MAGICJAR_AI_ENABLED=false` default

**근거**: master ADR 0001 (AI는 옵션, 매매 결정권 0).

---

## §2. 코드 품질 보장 메커니즘 (QA 게이트 대신)

QA 게이트가 없어도 다음으로 품질 보장:

### 2-1. 자동 검사 (Husky pre-commit·pre-push)

- pre-commit: 시크릿 차단 (`detect-secrets` 기반) + ktlint + author 화이트리스트 (ADR 0036)
- pre-push: `./gradlew check` (단위 + 통합 테스트, R3 PR 머지 시점 활성)
- frontend: vitest + eslint

### 2-2. 셀프 리뷰 + 사용자 1회 승인

- PR 본문 양식 (의무 4 슬롯):
  - 영향 모듈 (단일 책임 검증)
  - 신규 ADR (있으면) — master §10 매핑
  - 테스트 보류 사유 한 줄 (R1·R2: "코드 우선, R3 일괄"; R3: "본 PR에 데이터 흐름 시나리오 테스트 N건")
  - 사용자 검증 방법 (수동 — `localhost:3000/...` 접속 후 어떤 결과 보이면 PASS)

### 2-3. 도메인 모델·공개 API KDoc 의무

- public class·function에 KDoc 1줄 이상
- domain 모듈 port는 계약 명시 (입력·출력·실패 케이스)

### 2-4. 에러 처리 sealed class·Result 타입

- 도메인 실패 케이스는 `RejectReason` 계열 sealed class
- application 흐름 실패는 `Result<T>` 또는 sealed class
- 예외 throw는 외부 어댑터 경계에서만 + 즉시 sealed class 전환

### 2-5. 외부 호출 Resilience4j Circuit Breaker

- KIS REST·KIS WS·LLM provider 모두 Circuit Breaker 의무
- timeout default 5s + retry 3회 + circuit open 30s
- 실패 시 fallback 명시 (KIS REST → 캐시; LLM → "AI 응답 없음")

### 2-6. 시크릿 — 사용자 secret은 DB AES + 마스터 키 단일 예외 (ADR 0046)

- 코드·로그·docker compose 파일에 시크릿 평문 금지
- **사용자 secret(KIS APP_KEY·SECRET·DART·Slack URL)은 환경변수 X** — 셋업 마법사로 입력 → AES-256-GCM 암호화 → DB 저장 (ADR 0046)
- `.env`에는 **마스터 키 1개만** (`MAGICJAR_SECRET_MASTER_KEY` base64(32) — DB 암호화에 필요)
- DB BYTEA 컬럼은 항상 암호문만 (`app_key_enc`·`secret_key_enc`·`dart_api_key`·`notification_slack_webhook`)
- 메모리 hold → 즉시 AES → DB → 메모리 zero-fill (key array fill) — 메모리 `feedback_secret_handling.md` 정합

### 2-7. 로그 구조화 + 마스킹

- SLF4J + Logback (또는 JSON encoder)
- 민감 데이터(KIS app key·account number·user PII) 자동 마스킹

### 2-8. DSL 등록 검증 sandbox (ADR 0047)

- **Static 검증 의무** — `POST /api/strategy` · `POST /api/strategy/import?format=kis-yaml` 진입 시 KisYamlDslParser는 다음 한도를 모두 통과해야 200 OK
  - AST depth ≤ **32** (`MAGICJAR_DSL_AST_DEPTH_LIMIT`)
  - AST 노드 수 ≤ **10000** (`MAGICJAR_DSL_NODE_COUNT_LIMIT`)
  - `indicator()` 호출 수 ≤ **50** (`MAGICJAR_DSL_INDICATOR_CALL_LIMIT`)
  - 키워드 화이트리스트만 (조건/연산자/논리/산술/`indicator()`·`pattern()`·`value()`·메타)
- **Runtime 평가** — JvmStrategyEvaluator는 반드시 `strategy-eval-pool` (consumer @Bean fixed 4 threads, queue 100, CallerRunsPolicy) 경유. 직접 caller thread 평가 금지
- **Timeout** — realtime 100ms / backtest 1000ms. 1회 timeout 발생 시 strategy `disabled_reason='TIMEOUT'`로 즉시 auto-disable + `risk.violation.v1` publish (ADR 0044 통지)
- **Complexity 응답 의무** — 등록 성공 응답 body에 `complexity` 필드 (astDepth·nodeCount·indicatorCallCount·estimatedEvalTimeMs·complexityClass) 포함
- **검증 누락 차단** — T-DSL·T-SIG·T-BT R3 진입 시 architect 1회 grep — strategy 평가 직접 호출(pool 우회) 발견 시 reject

### 2-9. 각 모듈 README

- 모듈 루트에 `README.md` — 책임·의존·실행 방법
- W1 진입 시 모든 모듈에 README 의무화

---

## §3. 운영 정책 (ADR 0036 유지)

### 3-1. Commit author 분리 (ADR 0036)

각 에이전트가 본인 author로 commit:

| 작업 주체 | name | email |
|---|---|---|
| 사용자 | `sp-daewoon` | `sp-daewoon@gmail.com` 또는 `seonjupark94@gmail.com` |
| architect | `magicjar-architect` | `bot+architect@magicjar.local` |
| backend-core | `magicjar-backend-core` | `bot+backend-core@magicjar.local` |
| market-data | `magicjar-market-data` | `bot+market-data@magicjar.local` |
| strategy-engine | `magicjar-strategy` | `bot+strategy@magicjar.local` |
| ai-engineer | `magicjar-ai` | `bot+ai@magicjar.local` |
| frontend | `magicjar-frontend` | `bot+frontend@magicjar.local` |
| qa | `magicjar-qa` | `bot+qa@magicjar.local` (W4 sunset까지 호출 0 기대) |
| 메인 세션 | `magicjar-orchestrator` | `bot+orchestrator@magicjar.local` |

Husky pre-commit에 author 화이트리스트 가드 적용 (ADR 0036 강제).

### 3-2. Branch 전략 (ADR 0034)

- `agent/<agent>/<track>-<slug>` 기본
- 머지: main rebase (ADR 0036 정합 — squash가 author 덮어씀 회피)
- chore/docs/hotfix는 메인 세션 직접 commit OK

**v2 베이스라인 진입 시 branch 명명 권장**:
- W1: `agent/architect/w1-track2-r1`·`agent/backend-core/w1-track3-r1` 등
- W2: `agent/backend-core/w2-track5-r1`·`agent/frontend/w2-track8-r1`
- W3: `agent/strategy-engine/w3-track11-r1`
- W4: `agent/ai-engineer/w4-track16-r1`·`agent/architect/w4-track17-r1`

### 3-3. Task 파일 (ADR 0036)

- 트랙 시작 시 `_workspace/tasks/T-YYYYMMDD-HHMM-<slug>.md` 의무
- 양식: id·agent·model·branch·goal·deliverables·status·last_activity
- 트랙 완료 시 `_workspace/tasks/done/`로 이동
- 메인 세션이 활성 task 모니터링

### 3-4. PR 본문 양식 (의무)

```markdown
## 본 PR 영향 영역
<단일 영역 — 트랙2 hotfix·instrument adapter 등>

## 신규 ADR
<있으면 번호·제목 · master §X 매핑. 없으면 "없음">

## 테스트 보류 사유
<R1·R2: "v2 룰북 §1-4 — 코드 우선, R3 일괄". R3: "본 PR에 N건 시나리오 테스트 추가">

## 사용자 검증 방법
1. <docker compose ... 또는 localhost:3000/...>
2. <기대 결과 — N건 데이터 보임 / 차트 표시 / ...>
```

### 3-5. 모델 차등 (ADR 0035 유지)

- architect · ai-engineer · strategy-engine: opus
- backend-core · market-data · frontend · qa: sonnet
- 1회 escalation 가능 (sonnet → opus, 사용자 명시 시)

### 3-6. 단일 worktree·순차 (ADR 0033)

- 같은 트랙 내 sub-agent 동시 실행은 architect 판단 + 메인 세션 승인
- worktree isolation 부담이 가치 미달 시 단일 worktree 진행

### 3-7. 자본 모듈 장중 배포 drain 절차 (2026-06-10 신설 — ADR 0115 §2-P2-④ / 헌법 §11)

> **권위**: ADR 0115 §2-P2-④(장중 배포 in-flight drain 가드) + 운영 헌법 §9-2(변경 관리)·§11. **입력 evidence**: 2026-06-10 005930·전략8 incident **G1** — 13:11 AUTO SELL 발사 직후 재배포로 consumer가 13:11~13:16 다운되어 KIS WS 체결통보 유실(MOCK은 WS 미지원이라 backfill만이 복구 경로). in-flight 주문이 있는 consumer를 장중에 재배포하면 체결통지가 유실되어 strategy_position 미반영(G2·G4로 연쇄)으로 이어진다.

- **룰**: consumer(체결·자본 경로) 재배포는 **장중(09:00~15:30) in-flight 주문 0 확인 후** 또는 **장 마감 후 배포** 원칙. in-flight 주문(`order_request.status IN (REQUESTED, ACK, PARTIALLY_FILLED)`) count > 0이면 drain 대기 또는 명시 force(`--force-drain`) + 부팅 직후 backfill 흡수 확인.
- **배포 게이트**: `scripts/build-and-deploy.sh consumer`에 in-flight count 확인 게이트(ENV `magicjar.deploy.drain.in-flight-check.enabled`, default true). count > 0이면 경고 출력 + 사용자 확인. 부팅 직후 `KisExecutionBackfillService` 1회 backfill이 유실 윈도를 흡수하나 완전 방지는 아니므로(MOCK WS 미지원), drain 0 확인이 1순위·force-drain 후 backfill 확인이 차순위다.
- **적용 범위**: consumer(체결 수신·귀속·정합·리스크·알림 본체). api·batch는 in-flight 주문 보유 모듈이 아니므로 본 절차 면제(단 api KIS producer 재배포 시에도 장중 신규 주문 발의 중단 권장).
- **헌법 cross-link**: 헌법 §9-2(canary dev→staging→REAL + 30분 staging dwell)에 본 drain 절차 동반. 운영 runbook(`docs/operations/`) 신규/보강 의제.

---

## §4. 트랙 진입·종료 체크리스트

### 4-1. 트랙 진입 전 (architect 1회)

- [ ] master §X 위치 명시
- [ ] 선행 트랙 상태 확인 (DONE 또는 R3 머지)
- [ ] 신규 ADR 후보 식별 (있으면 master §10 + ADR 0030 SoT 운영 규약 참조)
- [ ] task 파일 생성
- [ ] 데이터 영향 식별 (Flyway 번호·Kafka 토픽·Redis key 매트릭스)
- [ ] 사용자 결정 필요 사항 사전 컨펌 (외부 자원·아키 결정)

### 4-2. R1 (도메인 + 시그니처) 진입

- [ ] 도메인 모델 + port 정의
- [ ] 단순 stub adapter (실 호출 X)
- [ ] 통합 테스트 0
- [ ] PR 본문 양식 충족
- [ ] 빌드 통과
- [ ] 사용자 1회 승인 → main rebase

### 4-3. R2 (어댑터 + 영속) 진입

- [ ] 실 KIS·외부 API 호출
- [ ] Flyway 마이그레이션 + 영속 어댑터
- [ ] Kafka 토픽 발행/구독
- [ ] Redis 캐시
- [ ] 단위 테스트 1~2건 (smoke 한정)
- [ ] PR 본문 양식 충족
- [ ] 빌드 통과
- [ ] 사용자 1회 승인 → main rebase

### 4-4. R3 (UI + 일괄 테스트) 진입

- [ ] 컨트롤러 REST + STOMP fanout
- [ ] frontend UI
- [ ] **데이터 흐름·사용자 시퀀스 기반 통합 테스트 N건** (시나리오)
- [ ] vitest·pytest·gradle test 모두 PASS
- [ ] PR 본문 양식 충족
- [ ] 9 컨테이너 healthy 검증
- [ ] 사용자 1회 승인 → main rebase

### 4-5. 트랙 종료 시

- [ ] task 파일 done/ 이동
- [ ] master §10에 ADR 반영 (있으면)
- [ ] tracks-roadmap §3에 트랙 outline 갱신 (있으면)
- [ ] `_workspace/_harness-state.json` 갱신
- [ ] CLAUDE.md 변경 이력 1행 추가

---

## §5. 사용자와의 인터페이스

### 5-1. 사용자 컨펌이 필요한 3 케이스

1. 아키텍처 레벨 결정 전환 (언어·프레임워크·증권사·AI 공급자 등)
2. 외부 자원·자격 필요 (API 키·계정·권한)
3. 블로킹 상충 (architect·sub-agent 결론 충돌, 메인 세션 판단 불가)

이외엔 메인 세션·architect 자율.

### 5-2. 사용자 결정 누적 카드 (master §12)

각 트랙 진입 시 사용자 결정 필요 사항을 master §12에 누적. 사용자가 한 번에 답하면 일괄 진행.

### 5-3. 사용자 검증 방법

- `localhost:3000/...` 접속 → 기대 화면
- `curl localhost:8080/api/...` → 기대 응답
- `docker compose ps` → 9 컨테이너 healthy
- 매매일지 entry 1건 + 차트 스냅샷 보임

각 트랙 R3 PR 본문에 검증 단계 명시 의무.

---

## §6. AI provider 사용 통제 (W4 한정)

### 6-1. AI 호출 정책

- W1·W2·W3 트랙: AI 호출 0 (코드·테스트·prompt 디버깅 모두)
- W4 트랙 (T-AI·T-MCP): AI 호출 가능
  - dev: `MAGICJAR_AI_ENABLED=true` + provider 선택 + 5분 prompt cache + 사용자 트리거만
  - prd: 동일

### 6-2. AI provider 1차 결정 (사용자 결정 필요)

| provider | 비용 | tier 한도 | 권장 |
|---|---|---|---|
| Anthropic Claude | 유료 | 분당 토큰 한도 (tier별) | 본 시스템 sentiment·explain 1차 |
| Google Gemini | free tier | 분당 60회 | dev 디버깅·1차 무료 옵션 |
| OpenAI GPT | 유료 | tier별 | 백업·비교 |

마스터 §12 결정 #5에 등록.

### 6-3. AI 응답 audit (매매 영향 0이지만 비용·드리프트 추적)

- `ai_comment_log` 테이블 (V20)
- 모든 호출의 model · prompt_template_id · tokens_in · tokens_out · latency_ms · cost_usd 기록
- prompt cache hit는 별도 플래그

---

## §7. 트랙별 룰 적용 매트릭스

| Wave | 룰 적용 강도 | 특이 |
|---|---|---|
| W1 | 강제 — 단일 책임 + R1·R2·R3 분할 | 트랙2 spec/plan 그대로 살림 (문서 D-2 수정만) |
| W2 | 강제 — 단일 책임 + 80지표 일괄 OK 단 R3에서 정확성 일괄 검증 | T-SB GUI 블록 빌더 1차 골격은 frontend agent 단독 |
| W3 | 강제 — AI 호출 0 검증 + Guard chain 6단 | T-SIG·T-RISK 시그널/risk 통합 시점 단위 통합 테스트 |
| W4 | LLM 호출 OK 단 5분 cache + 사용자 트리거만 | T-AI·T-MCP ai-engineer + architect 협업 |
| W5 | 사용자와 함께 진행 | 룰북 자체도 W5에서 사용자 검토 후 v3로 |

---

## §8. 사용자 결정 필요 사항

본 룰북 검토 시 사용자가 확정:

1. **§1-1 QA 게이트 배제 강도** — 7 트리거 조건 모두 무시 OK? 보안 경계만 예외 둘까?
2. **§1-4 테스트 마지막 일괄** — R3에서 일괄 OK? 또는 R2 끝나면 단위 테스트 + R3 통합 테스트로 분할?
3. **§6-2 AI provider 1차** — Anthropic / Gemini / OpenAI 중 default?
4. **§3-2 branch 전략** — `agent/<agent>/<track>-<slug>` 그대로 OK?
5. **§5-1 사용자 컨펌 케이스** — 3 케이스 그대로 OK? 추가/삭제?

---

## §9. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-06-10 | **§3-7 신설 — 자본 모듈 장중 배포 drain 절차 (ADR 0115 §2-P2-④ / 헌법 §11 / 최고 관리자 전체 승인)**. 2026-06-10 005930·전략8 incident G1(13:11 AUTO SELL 직후 재배포로 consumer 13:11~13:16 다운 → KIS WS 체결통보 유실) evidence. consumer 재배포는 장중 in-flight 주문(`order_request.status IN REQUESTED·ACK·PARTIALLY_FILLED`) 0 확인 후 또는 장 마감 후 배포 원칙 + build-and-deploy drain 게이트(ENV default true) + 부팅 backfill 흡수 확인. (architect) |
| 2026-05-03 | 초안 — 베이스라인 룰 + 운영 정책 + 트랙별 매트릭스 |
| 2026-05-07 (PR-2) | AI 변수명 통일 (`MAGICJAR_AI_OPTION_ENABLED` → `MAGICJAR_AI_ENABLED`, .env.example 정합). (architect) |
| 2026-05-07 (PR-3c) | §2-8 DSL 등록 검증 sandbox 룰 신규 추가 (ADR 0047 정합) — static 검증 한도(AST depth 32·노드 10000·indicator 50·키워드 화이트리스트), strategy-eval-pool 경유 의무, timeout 100ms/1000ms·auto-disable, complexity 응답 의무, R3 grep 검증. 기존 §2-8 README 항목은 §2-9로 시프트. (architect) |
