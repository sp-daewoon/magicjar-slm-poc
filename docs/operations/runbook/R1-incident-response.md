# R-1: Incident Response 표준 (운영 이벤트 대응)

> ADR 0094 §3-7 P0-OPS-5 · 운영 헌법 §10-3 on-call · 모든 incident의 "지붕" runbook

## 0. 사용 시점

다음 evidence 중 **하나라도** 발견 시 본 runbook 진입:
- `/system` 페이지 (frontend SystemEventPage)에 ERROR/WARN 행 표시
- Slack alert 채널에 NotificationDispatcher P1/P2 발사
- 사용자 본인 dogfooding (예: 2026-05-21 09:43~11:05 KillSwitch false positive — ADR 0043 §2-11 amend trigger)
- `qa:visual` Playwright snapshot diff > 0
- `docker logs magicjar-{api,batch,consumer}` 최근 5분 ERROR · `FlywayException` · `OutOfMemory`
- `/actuator/prometheus` metric SLO 위반 (운영 헌법 §10-1 — 시그널→주문 P95 > 5s · KIS WS 연결률 < 99% 등)

---

## 1. 인지 (Detect)

### 1-1. evidence 수집 매트릭스

| evidence source | 위치/명령 |
|----------------|----------|
| 알람센터 (frontend `/system`) | `/system` 페이지 — SystemEventCategory · severity · actor · payload JSON |
| Slack alert | `system_config.notification_slack_webhook` URL 채널 — NotificationDispatcher 7 listener (ADR 0044 §2) |
| Grafana alert | http://localhost:3001 (P0-OPS-2 산출물 — `docs/operations/observability.md` §3) |
| docker logs | `docker logs --since 10m magicjar-api 2>&1 \| grep -E "ERROR\|WARN"` (4 모듈 각각) |
| Actuator metric | `curl -s http://localhost:8080/actuator/prometheus \| grep -E "killswitch\|kis_ratelimit\|outbox"` |
| 사용자 dogfooding 메모 | `_workspace/devlog/YYYY-MM-DD*.html` 또는 SessionLog Markdown |

### 1-2. 시간·범위 LOCK

evidence 발견 시각 (KST) + 영향 사용자 (multi-tenant userId) + 영향 모듈 (api·batch·consumer·frontend) 3건 즉시 메모. R-1 §6 post-mortem audit row 의무.

```bash
# 1줄 evidence 합성 — 4 모듈 최근 5분 ERROR
for m in api batch consumer; do
  echo "─── magicjar-$m ───"
  docker logs --since 5m "magicjar-$m" 2>&1 | grep -E "ERROR|FATAL" | tail -10
done
```

---

## 2. 분류 (Classify)

### 2-1. severity 매트릭스 (운영 헌법 §10-1 SLO 위반 = 자동 P1+)

| severity | 정의 | 예시 | 1차 actor |
|----------|------|------|----------|
| **CRITICAL** | 자본 손실 · 잘못된 주문 발사 · DB 무결성 손상 | 미체결 주문 중복 발사 · 차익 강제 손절 미발사 · `kill_switch_state` 행 분실 | 사용자 (즉시 KillSwitch ON) → backend-core (DR 가동) |
| **ERROR** | 핵심 흐름 차단 · 사용자 화면 표시 불가 | account API 500 · KIS WS 끊김 5분 이상 · Outbox drain 정지 | backend-core (R-2·R-4 fan-out) |
| **WARN** | 회복 가능 · SLO 위반 · 누적 시 ERROR 승급 | EGW00201 burst · Outbox lag > 30s · STOMP fanout 지연 | backend-core (관측 → 누적 검토) |
| **INFO** | 운영 안내 · audit only | KillSwitch 가드 A·B·C 보류 evidence (ADR 0043 §2-11 audit) | log only — Slack 발사 0 |

### 2-2. 영향 범위 매트릭스

| 범위 | 격리 lever | runbook fan-out |
|------|----------|----------------|
| **자본 path** (주문·position·KillSwitch·reconcile) | KillSwitch ON (R-3) | R-3 → R-4 (DB 무결성 검증) |
| **실시간** (STOMP·KIS WS·signal) | consumer 컨테이너 재시작 | R-5 |
| **데이터 path** (KIS REST·Yahoo·DART) | KIS 토큰 비우기 (R-2) | R-2 |
| **UI** (frontend·LWC v4·dashboard) | frontend 컨테이너 재시작 + qa Visual 재현 | R-5 + qa Visual dispatch |
| **인프라** (postgres·redis·kafka) | docker compose restart 해당 서비스 | R-4 (postgres) 또는 docker logs 검사 |

### 2-3. 분기 결정 LOCK

| evidence | 분기 |
|----------|------|
| `account API 502/504` 또는 KIS 응답 `rt_cd != "0"` | **R-2** (KIS API 차단) |
| `kill_switch_state.enabled = true` + reason `POSITION_DRIFT` | **R-3** (KillSwitch 자동 ON evidence) |
| postgres unhealthy 또는 Flyway 부팅 실패 | **R-4** (DB 복구) |
| 코드 hotfix 적용 필요 | R-1 §4 fix → **R-5** (재배포) |
| 위 4건 외 (UI 회귀 · 일반 backend 결함) | R-5 + qa Visual + ADR 발행 검토 |

---

## 3. 격리 (Isolate)

자본 path 영향 evidence 있으면 **우선 KillSwitch ON** (사용자 단독 결재). 회복 검증 전 신규 주문 자동 차단.

### 3-1. KillSwitch ON (자본 path 보호 — 최우선)

```bash
# UI 경로 (사용자 1차)
# SettingsModal → KillSwitchSection → engaged=true + reason 5~64자 입력 → 저장

# REST 경로 (사용자 부재 시 sub-agent 보조)
curl -X PUT http://localhost:18080/api/system-config/kill-switch \
  -H "Content-Type: application/json" \
  -H "X-User-Id: <userId>" \
  -d '{"engaged": true, "reason": "incident-<id> isolation per runbook R-1"}'
```

상세 R-3 §3 참조.

### 3-2. KIS 토큰 비우기 (KIS API 차단 의심)

```bash
# 사용자별 REAL·MOCK 둘 다 DEL
docker exec magicjar-redis redis-cli DEL "kis:token:<userId>:REAL" "kis:token:<userId>:MOCK"
```

상세 R-2 §3 참조.

### 3-3. 컨테이너 재시작 (실시간/UI 결함)

```bash
# consumer 단독 (KIS WS·signal·reconcile 영역)
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml \
  --env-file "$PWD/.env" restart consumer-app

# frontend 단독 (UI 결함)
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml \
  --env-file "$PWD/.env" restart frontend
```

CLAUDE.md 운영 함정 가드 §2 정합 — `--env-file` 절대경로 + `-f` 명시 + `--project-directory` 미사용 LOCK.

### 3-4. 즉시 가능한 영향 격리 의무

격리 step 완료 후 **즉시 사용자에게 격리 상태 보고** (severity·격리 lever·예상 fix 시간). 운영 헌법 §10-3 on-call 1차 사용자 가시화 의무.

---

## 4. fix (Resolve)

### 4-1. orchestrator dispatch 결정 (ADR 0094 §4-7)

| 트리거 | 1순위 actor | 2순위 actor |
|--------|------------|------------|
| 자본 path 결함 | strategy-engine · market-data | backend-core (운영 영향 평가) |
| KIS API 결함 | market-data | backend-core |
| persistence·DB 결함 | backend-core | architect (ADR 발행) |
| UI 결함 | frontend | qa (Visual 재현) |
| 운영 인프라 (logback·Actuator·docker) | backend-core | architect |
| 정책 결정 필요 (KillSwitch 임계·grace window 등) | architect (ADR amend) | 영역 sub-agent |

### 4-2. hotfix PR 발행 절차 (사용자 메모리 `feedback_release_policy_patch_first` 정합)

```bash
# 1) 작업 시작 (worktree 분리 + author config)
git worktree add ../magic_ch1_<slug> -b hotfix/<slug>
cd ../magic_ch1_<slug>
git config user.email "bot+backend-core@magicjar.local"
git config user.name "magicjar-backend-core"

# 2) task 파일 생성 + commit (헌법 §1-C 의무)
node scripts/task.mjs start --agent backend-core \
  --goal "<incident-id> hotfix" \
  --deliverables "<산출물 1>; <산출물 2>; ..."
git add _workspace/tasks/T-*-*.md

# 3) 변경 + 자체 검증
./gradlew :api:check :consumer:check  # 영향 모듈만
./gradlew ktlintFormat                # husky pre-commit 통과 의무

# 4) commit + push + PR
git commit -m "hotfix(<area>): <slug> — <한줄 사유>"
git push -u origin hotfix/<slug>
gh pr create --base main --title "hotfix(<area>): <slug>" --body "<...>"
```

### 4-3. CRITICAL severity 의무 (즉시 사용자 결재)

자본 손실·잘못된 주문 발사 evidence 시:
1. KillSwitch ON 즉시 (R-1 §3-1)
2. 사용자에게 evidence + 격리 상태 + 예상 fix 시간 보고
3. 사용자 결재 후 fix 사이클 진입
4. fix 머지 후 사용자가 직접 KillSwitch OFF (ADR 0043 §2-3 LOCK — 자동 OFF 0)

---

## 5. 검증 (Verify)

### 5-1. 재배포 절차

R-5 §3 참조. 표준:
```bash
scripts/build-and-deploy.sh   # 전체
# 또는 부분: scripts/build-and-deploy.sh api consumer
```

### 5-2. dogfooding 시나리오 재현 의무 (사용자 메모리 `feedback_verification_strict`)

PR 머지·gradle check GREEN만으로 "완료" 종결 **금지**. 원래 incident가 재현되지 않는지 evidence 인용:

| evidence | 확인 명령 |
|----------|----------|
| docker logs ERROR 0건 | `docker logs --since 5m magicjar-<m> 2>&1 \| grep -E "ERROR\|FATAL" \| wc -l` (4 모듈 모두 = 0) |
| SystemEvent ERROR 신규 발사 0 | `/system` 페이지 새로고침 — 최근 5분 ERROR 행 0 |
| 자본 path 정상화 | `curl http://localhost:18080/api/account/inquire-balance -H "X-User-Id: <userId>"` → 200 + 데이터 |
| STOMP fanout 정상 | frontend Dashboard 진입 → SymbolPanel 실시간 표시 1초 이내 |
| Redis hit ratio > 95% | `docker exec magicjar-redis redis-cli INFO stats \| grep keyspace_hits` (전후 비교) |
| KillSwitch 정상화 (격리 lever 적용 시) | R-3 §6 무결성 검증 |

### 5-3. qa Visual 회귀 가드 (운영 헌법 §10-2 의무)

frontend 핵심 화면(Dashboard·Orders·Positions·Settings) 변경 PR 또는 incident 분류 = UI 영역 시:
```bash
cd frontend && pnpm qa:visual
```

baseline 대비 snapshot diff = 0 확인. diff > 0 시 qa dispatch — Visual 회귀 책임 owner.

---

## 6. post-mortem (Audit)

**의무**: 단순 fix 후 종결 금지. evidence·root cause·재발 방지 정책 audit row 발행.

### 6-1. SystemEvent log evidence 의무

```sql
-- DB 영구 보존 audit row
SELECT id, category, severity, actor, occurred_at, payload
FROM system_event
WHERE occurred_at >= '<incident 인지 시각 KST>'
  AND occurred_at <= '<incident 회복 시각 KST>'
ORDER BY occurred_at DESC;
```

`payload` JSON에 다음 5 키 의무: `incidentId`, `severity`, `isolationLever`, `rootCause`, `fixPr`.

### 6-2. 재발 방지 분기 매트릭스

| root cause 유형 | 발행물 |
|----------------|--------|
| 정책 missing (예: KillSwitch race window 미고려) | **ADR amend** — 예 ADR 0043 §2-11 amend (2026-05-21 dogfooding 결과) |
| 코드 결함 (예: KisHttpErrorClassifier EGW00123 누락) | hotfix PR만 (ADR 0094 §3-7 P0-OPS-3 SLO alert routing matrix 갱신 검토) |
| 운영 절차 missing (예: 본 runbook step 부족) | **본 runbook §X 갱신** + `docs/operations/runbook/README.md` 변경 이력 row |
| 데이터 무결성 (예: position drift) | DR drill 의제 (`_workspace/backlog.md`) — 분기 1회 |
| 외부 의존성 (KIS API 장애·DART 결함) | NotificationDispatcher Slack 임계 갱신 (운영 헌법 §10-1) |

### 6-3. backlog 또는 ADR 행 추가

```bash
# backlog row 예시 (운영 무관 후순위 제외 — ADR 0094 §4-4)
# _workspace/backlog.md §X에 다음 형식 추가:
#
# ### §X. <incident-id> — <한 줄 요약>
# - **발생**: YYYY-MM-DD HH:MM KST · severity=<L> · actor=사용자
# - **격리**: <lever — R-X §Y>
# - **fix**: PR #<N>
# - **재발 방지**: <ADR amend 또는 본 runbook §갱신 또는 SLO 갱신>
```

### 6-4. 분기 사이클 runbook 갱신 evidence

운영 헌법 §10-5 관측 매트릭스 의무 — 다음 분기 사이클에 incident 빈도 / MTTR / 회복률 누적 보고:
- incident 1주 누적 횟수 ≥ 3건 → backend-core 사이클 (runbook §갱신 또는 SLO 임계 조정)
- 같은 root cause 재발 ≥ 2회 → ADR 발행 의무

---

## 7. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-21 | 신규 발행 — ADR 0094 §3-7 P0-OPS-5 · backend-core 1차 dispatch. 6단 표준 (인지·분류·격리·fix·검증·post-mortem) + R-2~R-5 fan-out 매트릭스. |
