# magicJar 운영 Runbook 인덱스

> ADR 0094 §3-7 P0-OPS-5 · 운영 헌법 §10 정합 · backend-core 단일 owner · v3.8.0 운영 baseline 진입 전 P0

## 1. 본 디렉토리의 본질

운영자(사용자 단독, ADR 0094 §4-2 §10-3 on-call 1차)가 **화면 보고 step-by-step 따라할 수 있는 actionable 절차**. mock 추론·일반론 0, 모든 step이 실제 코드·DB·docker 명령·Slack·STOMP evidence 기반.

운영 단계 mindset (ADR 0094 §4-1):
- 첫 질문 = **"SLA 충족하는가 + 롤백 가능한가 + Visual 회귀 0건인가"**
- 사이클 단위 = **"incident + 주간 운영"**
- 발사 가능한 lever = KillSwitch 토글 / KIS 토큰 비우기 / 컨테이너 재기동 / DB 복구 / 재배포

## 2. 5 시나리오 인덱스

| ID | 제목 | 트리거 | 1차 actor | 상세 |
|----|------|--------|-----------|------|
| **R-1** | [Incident Response 표준](R1-incident-response.md) | SystemEvent ERROR · Slack alert · 사용자 dogfooding | 사용자 + backend-core | 인지→분류→격리→fix→검증→post-mortem 6단 |
| **R-2** | [KIS API 차단·Rate Limit 대응](R2-kis-api-block.md) | EGW00201 burst · KIS_RATELIMIT SystemEvent · account API 502 | backend-core | Redis 토큰 TTL → KIS 응답 grep → 임시 fix → Bucket4j 트리거 확인 |
| **R-3** | [KillSwitch 토글 (수동·자동)](R3-killswitch-toggle.md) | 자본 사고 의심 · POSITION_DRIFT · 사용자 수동 OFF 결정 | 사용자 단독 (수동 OFF) | UI/REST 토글 + 자동 ON A·B·C 가드 evidence |
| **R-4** | [DB 복구·Backup](R4-db-recovery.md) | postgres 결함 · table drop · Flyway 실패 | backend-core | pg_isready → pg_restore → Flyway info → 자본 path 무결성 검증 |
| **R-5** | [재배포](R5-redeploy.md) | PR 머지 · hotfix · KIS 키 회전 · observability 갱신 | backend-core + qa | gradle build → build-and-deploy.sh → docker logs → Visual 회귀 |

## 3. when-to-use 매트릭스

| 상황 evidence | 우선 적용 runbook |
|---------------|------------------|
| `/system` 페이지에 SystemEvent ERROR 행 표시 | R-1 (격리 후 영역별 fan-out — R-2·R-3·R-4 중 분기) |
| `/system` 페이지에 SystemEvent `KIS_RATELIMIT` WARN/ERROR 누적 | R-2 |
| account API `GET /api/account/inquire-balance` 502/504 응답 | R-2 |
| account API 응답 body에 `rt_cd != "0"` + `msg_cd ∈ {EGW00123, EGW00201, EGW00133}` | R-2 |
| SettingsModal KillSwitchSection 표시 = ON + reason = `POSITION_DRIFT` | R-3 (자동 ON evidence 확인 → 수동 OFF 결정) |
| 사용자 자본 사고 의심 (체결가 이상·주문 폭주·UI 자본 표시 불일치) | R-3 (수동 ON 즉시) → R-1 (post-mortem) |
| `docker ps`에 `magicjar-postgres` 미존재·`unhealthy` | R-4 |
| Flyway 부팅 실패 (`docker logs magicjar-api` startup 로그에 `FlywayException`) | R-4 |
| PR 머지 직후·hotfix 적용·KIS 키 회전·observability stack 갱신 | R-5 |
| `qa:visual` Playwright snapshot diff > 0 | R-1 (회귀 격리) → 영역별 runbook |
| Slack alert 채널에 P1/P2 발사 (NotificationDispatcher) | R-1 (분류 → 영역 runbook fan-out) |

## 4. 사용 룰

1. **순서 LOCK**: R-1은 모든 incident의 "지붕" — R-2~R-5는 R-1 §2 분류 단계 분기에서 호출.
2. **evidence-first**: 각 runbook step에 명시된 명령·SQL·KIS 응답 raw를 그대로 인용해야 audit 가능 (ADR 0094 §10-5 관측 매트릭스 의무).
3. **수동 OFF 만**: KillSwitch OFF는 사용자 단독 결재 — automation·sub-agent 자동 OFF 0건 LOCK (ADR 0043 §2-3).
4. **post-mortem 의무**: R-1 §6 통과 시 SystemEvent emit + `_workspace/backlog.md` 또는 ADR 행 추가. 단순 fix 후 종결 금지 (사용자 메모리 `feedback_verification_strict` 정합).
5. **분기 사이클 갱신**: 운영 evidence 누적되면 분기 1회 본 인덱스 + 5 runbook revisit (ADR 0094 P2-OPS-4).

## 5. 관련 SoT

| 주제 | 위치 |
|------|------|
| **운영 헌법 §10** | `_workspace/arch/agent-charter.md` §10 (SLO/SLA·변경 관리·on-call·backup/DR·운영 ADR 양식) |
| 관측 stack 셋업 | `docs/operations/observability.md` (P0-OPS-1·2 산출물) |
| 초기 설치 | `docs/operations/initial-setup.md` |
| KIS API 레퍼런스 | `docs/operations/kis-api-reference.md` |
| KillSwitch 결정 권위 | ADR 0043 (5대 행동 명세 + §2-11 A·B·C race-safe) |
| Reconcile 권위 | ADR 0041 · 0070 |
| KIS Rate Limit Bucket4j | ADR 0037 + backlog §0 P1-4 Phase 2 |
| Notification Slack | ADR 0044 + system_config webhook |
| Outbox·Persistence-shared | ADR 0088 |
| 운영 단계 transition | ADR 0094 §3·§4·§10 |

## 6. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-21 | **신규 — P0-OPS-5 backend-core 1차 dispatch** — 5 runbook(R-1~R-5) + 본 인덱스 발행. ADR 0094 §3-7 P0-OPS-5 정합. v3.8.0 운영 baseline 진입 전 의무 산출물. |
