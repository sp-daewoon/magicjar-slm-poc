# R-3: KillSwitch 토글 (수동·자동)

> ADR 0094 §3-7 P0-OPS-5 · ADR 0043 (KillSwitch 행동 5대 명세 + §2-11 race-safe A·B·C) · ADR 0070 §2-11 권위 위임

## 0. 사용 시점

- 자본 사고 의심 (체결가 이상·주문 폭주·잔고 표시 불일치)
- POSITION_DRIFT 자동 ON evidence — `kill_switch_state.reason = POSITION_DRIFT`
- strategy 폭주 (시그널 1분 ≥ 10건)
- 사용자 수동 OFF 결정 (incident fix 머지 후 자본 path 정상화 확인)
- 분기 DR drill 시 (ADR 0094 §10-4)

R-1 §2-3 분기 LOCK — KillSwitch 분류 시 본 runbook 진입. R-1 §3-1 격리 lever와 짝.

---

## 1. 현재 상태 확인

### 1-1. SettingsModal KillSwitchSection 표시

```text
frontend `/` 진입 → 우상단 ⚙️ SettingsModal → KillSwitchSection (Section 4)
```

표시 항목 (KillSwitchStateResponse — `api/.../controller/SystemConfigDto.kt`):
- `enabled` — true(ON, 주문 차단) / false(OFF, 정상)
- `reason` — 토글 사유 (60자 이내) 또는 null (초기)
- `updatedAt` — 마지막 토글 시각 UTC ISO-8601
- `updatedBy` — `AUTO` (자동 ON) 또는 `USER:<userId>` (수동 OFF)

### 1-2. REST 조회

```bash
curl -s http://localhost:18080/api/system-config/kill-switch \
  -H "X-User-Id: <userId>" | jq
# 응답 예시:
# {
#   "enabled": true,
#   "reason": "POSITION_DRIFT",
#   "updatedAt": "2026-05-21T02:05:31.000Z",
#   "updatedBy": "AUTO"
# }
```

### 1-3. DB 직접 조회 (audit)

```sql
SELECT user_id, enabled, reason, updated_at, updated_by, last_triggered_reason
FROM kill_switch_state
WHERE user_id = <userId>;
```

### 1-4. 상태 매트릭스

| enabled | updatedBy | 의미 | 다음 액션 |
|---------|-----------|------|----------|
| false | (null 또는 USER:`<userId>`) | 정상 운영 — 자동매매 가능 | 액션 없음 |
| true | AUTO | 자동 ON 발동 (A·B·C 가드 통과 후) | §4 자동 ON evidence 분석 → §3-2 수동 OFF 결재 |
| true | USER:`<userId>` | 사용자 수동 ON | 자본 path 정상화 확인 후 §3-2 수동 OFF |

---

## 2. 격리 효과 (KillSwitch ON 시 5대 행동 — ADR 0043 §2-1)

KillSwitch enabled = true 시 시스템 자동 행동 (`api`·`consumer` 책임 분리):

| # | 행동 | 책임 컨테이너 | 구현 위치 |
|---|------|--------------|----------|
| 1 | 신규 시그널 emit 즉시 정지 | `consumer` | `SignalGenerator` — KillSwitch 상태 read → ON이면 evaluate skip |
| 2 | Pending 주문 cancel | `consumer` | DB `orders.status ∈ {PENDING, SUBMITTED, PARTIALLY_FILLED}` → KIS REST `order-rvsecncl` 일괄 호출 |
| 3 | STOMP push | `api` | `killswitch.engaged.v1` Kafka topic sub → `/topic/risk/{userId}` fanout |
| 4 | 신규 주문 ingestion 차단 | `api` | `OrderRouter` 진입 직전 KillSwitch 검사 → ON이면 422 Unprocessable Entity |
| 5 | 체결통보는 정상 수신·기록 | `consumer` | KIS WS `H0STCNI0` 체결통보는 정상 처리 (이미 발사된 주문 audit 의무) |

→ 사용자 화면 즉시 표시: frontend Banner ON · SystemEventPage `KILLSWITCH_ENGAGED` row · Slack alert (system_config webhook).

---

## 3. 수동 토글 (사용자 결재 의무)

### 3-1. 수동 ON

#### UI 경로 (1차)

```text
1. SettingsModal → KillSwitchSection
2. 토글 스위치 OFF → ON
3. 사유 입력 (5자 이상 64자 이하 — DB reason VARCHAR(64) 정합)
   예: "수동 ON — incident-<id> isolation per runbook R-3"
4. 저장 버튼
```

#### REST 경로 (사용자 부재 또는 sub-agent 자동화 시)

```bash
curl -X PUT http://localhost:18080/api/system-config/kill-switch \
  -H "Content-Type: application/json" \
  -H "X-User-Id: <userId>" \
  -d '{
    "engaged": true,
    "reason": "incident-2026-05-21 isolation per R-3"
  }'
```

응답 = `KillSwitchStateResponse` (200) · 400(reason 길이 미달) · 412(온보딩 미완료).

### 3-2. 수동 OFF ★ 사용자 단독 결재 LOCK

★ **ADR 0043 §2-3 LOCK — 자동 OFF 0건**. sub-agent·automation·runbook 자동화 OFF **금지**. 사용자 명시 결재만.

#### OFF 결재 의무 evidence (사용자 직접 확인)

OFF 결재 전 다음 5건 evidence 의무:
1. **incident root cause 명확** — R-1 §6 post-mortem 진입 완료
2. **자본 path 정상화 확인** — account API 200 + Dashboard 표시 + 잔고 drift 0
3. **DB 무결성 검증** — §6 SQL 5건 통과
4. **재발 방지 정책 발행** — ADR amend 또는 backlog row (R-1 §6-3)
5. **Slack 안내 발사** — 사용자 본인 결재 + 시각 메모

#### OFF 절차

```text
1. SettingsModal → KillSwitchSection
2. 토글 스위치 ON → OFF
3. 사유 입력 — 예: "incident-<id> resolved per R-3 §3-2, evidence: account API 200 + position drift 0"
4. 저장
```

```bash
# REST 경로 (UI 불가 시)
curl -X PUT http://localhost:18080/api/system-config/kill-switch \
  -H "Content-Type: application/json" \
  -H "X-User-Id: <userId>" \
  -d '{
    "engaged": false,
    "reason": "<incident-id> resolved — evidence verified per R-3 §3-2"
  }'
```

#### 60초 cooldown (frontend 담당)

ADR 0074 §3-4 §j 채택 — OFF 직후 60초 cooldown은 frontend Banner 표시 (backend 강제 0). 사용자가 자본 path 정상화 시간 확보용. backend OrderRouter는 cooldown 무관 즉시 재개.

---

## 4. 자동 ON evidence 분석 (KillSwitchAutoToggler 발동 시)

`updatedBy = AUTO` evidence 확인 시 다음 분석 의무.

### 4-1. Kafka 토픽 evidence

```bash
# killswitch.engaged.v1 topic 최근 발사 확인
docker exec magicjar-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic killswitch.engaged.v1 \
  --from-beginning --max-messages 5 --timeout-ms 5000
```

응답 schema (`ReconcileEventKafkaPublisher` 정합):
```json
{
  "userId": <userId>,
  "engaged": true,
  "reason": "POSITION_DRIFT",
  "triggeredBy": "AUTO",
  "occurredAt": "2026-05-21T02:05:31.000Z",
  "payload": {
    "symbol": "005930",
    "expectedQty": 10,
    "actualQty": 13,
    "driftQty": 3
  }
}
```

### 4-2. ADR 0070 §2-11 race-safe 정책 evidence (PR #647·#648 baseline)

A+B+C 3중 가드 — KillSwitchAutoToggler 진입 시점 evidence:

#### A. Pending Order Exclusion (reconcile worker filter)

```bash
# consumer 로그 — reconcile drift publish 직전 pending 종목 필터 evidence
docker logs --since 10m magicjar-consumer 2>&1 | grep -E "reconcile.*pending.*excluded"
```

`consumer/reconcile/BalanceReconcileJob` — `reconcile.drift.v1` Kafka publish 직전 `orders.status ∈ {PENDING, SUBMITTED, PARTIALLY_FILLED}` 종목 제외.

#### B. Grace Window 5분 (KillSwitchAutoToggler.onDriftDetected 진입)

```bash
# gate-B 보류 evidence (ADR 0043 §2-11 audit 의무)
docker logs --since 10m magicjar-consumer 2>&1 | grep "KillSwitch auto-ON gate-B blocked"
# 예: "KillSwitch auto-ON gate-B blocked: user=1 symbol=005930 reason=lastFilledAt within 5min"
```

`KillSwitchAutoToggler.onDriftDetected` 진입 직후 `now - lastFilledAt < 5min`이면 보류.

#### C. N-Strike (N=2, in-memory ConcurrentHashMap)

```bash
# gate-C 보류 evidence
docker logs --since 10m magicjar-consumer 2>&1 | grep "KillSwitch auto-ON gate-C blocked"
# 예: "KillSwitch auto-ON gate-C blocked: user=1 symbol=005930 reason=strike count 1<2"
```

같은 `(userId, symbol)` 쌍 5분 sliding window 안에 drift 2회 이상이면 ON.

### 4-3. Micrometer 차단 카운터 (관측 매트릭스)

```bash
# Prometheus query — gate 별 차단 빈도
curl -s http://localhost:8082/actuator/prometheus | grep "killswitch_auto_on_blocked"
# 예: killswitch_auto_on_blocked_total{gate="B"} 3
```

Grafana panel 의제 — `docs/operations/observability.md` §6 dashboard 추가.

### 4-4. 자동 ON 발동 확정 evidence (5단 게이트 모두 통과)

```bash
# 자동 ON 발동 시점 단일 로그
docker logs --since 10m magicjar-consumer 2>&1 | grep "KillSwitch auto-ON engaged"
# 예: "KillSwitch auto-ON engaged: user=1 reason=POSITION_DRIFT driftSymbols=[005930]"
```

journal_event row 의무 (T-J 마감 시점 이후):
```sql
SELECT id, type, payload, occurred_at
FROM journal_event
WHERE type = 'KILLSWITCH_ENGAGED'
ORDER BY occurred_at DESC LIMIT 5;
```

---

## 5. STOMP fanout 검증

ADR 0070 §2-1 `StompReconcileNotifier` — `killswitch.engaged.v1` Kafka sub → `/topic/risk/{userId}` 발행.

```text
1. frontend Dashboard 진입 (브라우저 DevTools Network → WS frame 관찰)
2. /topic/risk/{userId} 구독 확인
3. KillSwitch ON 시점 frame 수신:
   {
     "type": "killswitch_auto_engaged",
     "userId": 1,
     "reason": "POSITION_DRIFT",
     "triggeredBy": "AUTO",
     "occurredAt": "2026-05-21T02:05:31.000Z"
   }
4. frontend Banner 표시 (KillSwitchBanner 컴포넌트 — frontend Visual 회귀 1순위)
```

---

## 6. DB 무결성 검증 (수동 OFF 결재 전 의무 — §3-2 evidence 5건 중 3)

```sql
-- 1. kill_switch_state 단일 row + enabled=true 확인
SELECT * FROM kill_switch_state WHERE user_id = <userId>;

-- 2. 최근 5분 drift 검출 빈도
SELECT COUNT(*) FROM reconcile_run
WHERE user_id = <userId>
  AND drift_count > 0
  AND started_at >= NOW() - INTERVAL '5 minutes';
-- → 0 기대 (drift 사라짐 확인)

-- 3. orders.status pending 종목 잔여 확인 (A 게이트 evidence)
SELECT symbol, status, COUNT(*) FROM orders
WHERE user_id = <userId>
  AND status IN ('PENDING', 'SUBMITTED', 'PARTIALLY_FILLED')
GROUP BY symbol, status;

-- 4. risk_event KILLSWITCH_TOGGLED 행 audit
SELECT id, type, payload, occurred_at FROM risk_event
WHERE type = 'KILLSWITCH_TOGGLED'
  AND payload->>'userId' = '<userId>'
ORDER BY occurred_at DESC LIMIT 10;

-- 5. position ↔ KIS 잔고 drift 직접 비교 (수동 reconcile trigger 후)
-- POST /api/risk/reconcile/<userId> → 200 응답 + drift_count = 0 기대
```

수동 reconcile 트리거:
```bash
curl -X POST http://localhost:18080/api/risk/reconcile/<userId> \
  -H "X-User-Id: <userId>"
# 응답 ReconcileRun + drift_count = 0 기대
```

---

## 7. post-mortem

R-1 §6 통합. 추가 evidence:
- KillSwitch 자동 ON 빈도 ≥ 주 1회 → **ADR 0094 §4-5 점진 고도화 #1** trigger (reconcile 정밀도 개선 사이클)
- 가드 보류 (gate-A·B·C) 빈도 → 운영 헌법 §10-1 SLO 갱신 evidence (Micrometer `killswitch.auto_on.blocked` tag 카운터)
- 사용자 수동 ON 빈도 ≥ 일 3회 → ADR 0043 §3-3 잦은 trigger 경고 — 시스템 점검 필요

### 7-1. ADR cross-link

- **ADR 0043** — KillSwitch 5대 행동 명세 + §2-11 race-safe A·B·C (권위 SoT)
- **ADR 0070** — T-RECON Phase 1·2 (drift 검출 → KillSwitch 자동 ON 흐름)
- **ADR 0074 §2-3 §3-4** — KillSwitchSection UI + 60초 cooldown frontend 담당
- **ADR 0094 §10-3 on-call** — 사용자 단독 OFF 권한 LOCK

---

## 8. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-21 | 신규 발행 — ADR 0094 §3-7 P0-OPS-5 · backend-core 1차 dispatch. ADR 0043 §2-11 A·B·C race-safe (PR #647·#648) evidence + 자동 OFF 0 LOCK + STOMP fanout 검증 + DB 무결성 SQL 5건. |
