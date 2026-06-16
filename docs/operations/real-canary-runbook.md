# REAL Canary Runbook — 실계좌 소액 1일 canary 절차

- 작성: architect (2026-06-05)
- 위치 권위: `docs/operations/` (운영 가이드 — ADR 0108 분류 권위표)
- 관계: ADR 0043(KillSwitch) · ADR 0095(SLO·alert) · ADR 0096(DR/backup) · ADR 0106(RiskPlan 필수) · **ADR 0109(canary 모드 hard cap ENV — 본 runbook 발행과 동반)**
- runbook 형제: `runbook/R1-incident-response.md` · `R2-kis-api-block.md` · `R3-killswitch-toggle.md` · `R4-db-recovery.md` · `R5-redeploy.md`
- readiness 게이트: `docs/assessments/2026-06-05-autotrading-readiness.html` §"완성도를 더 채우는 순서" +5 P1 — "장중 1일 canary: 시그널→리스크→KIS 결과 0/1건 단위 완전 추적 evidence"

> **★ 본 runbook의 본질**: 실계좌 무인 자동매매 진입의 마지막 게이트인 "소액 1일 canary"를 안전하게 수행·관측·중단·증거수집하기 위한 절차서다.
> 실행 자체는 **최고 관리자 결재·참여 하에 별도 진행**한다. 본 문서는 절차와 확인 항목만 LOCK한다.
> **canary는 "전략이 진짜 실주문을 내고, 체결되고, 알림이 오고, 멈출 수 있는가"를 실 자본 최소 단위로 1회 증명하는 행위**이지, 수익을 내려는 운용이 아니다.

---

## 0. 한눈에 보는 흐름

```
[사전 조건 체크리스트(§1)]  →  [canary 파라미터 LOCK(§2)]  →  [staging 30분 dwell(§3-1)]
   →  [REAL 게이트 ON + 첫 시그널 관찰(§3-2~3-3)]  →  [시간대별 모니터링(§4)]
   →  [정상 종료 or 즉시 중단(§5)]  →  [evidence 수집(§6)]  →  [MOCK 복귀 rollback(§7)]
```

자본 흐름 권위: 시그널 → RiskGateway(consumer) → `order.intent.v1` → OrderRouterKafkaListener(3중 게이트 + KillSwitch + 장운영시간 + KIS sync) → KisOrderHttpAdapter(REAL tr_id TTTC0012U/TTTC0011U) → KIS 체결 → KisExecutionWsListener → 알림(order.filled.v1 즉시 Slack).

---

## 1. 사전 조건 체크리스트 (canary 시작 전 100% 충족 의무)

각 항목은 실제 화면·API·SQL로 확인한다. 하나라도 미충족이면 canary 진입 금지.

### 1-1. 실계좌 이중 게이트 + 동의 (3중 게이트 — TradingModeGateImpl LOCK)

| # | 항목 | 확인 방법 | 통과 기준 |
|---|------|-----------|-----------|
| G-1 | `MAGICJAR_MODE=REAL` | `.env` 확인 + `docker exec magicjar-consumer-app printenv MAGICJAR_MODE` | `REAL` |
| G-2 | `MAGICJAR_ALLOW_REAL=true` | `.env` 확인 + `docker exec magicjar-consumer-app printenv MAGICJAR_ALLOW_REAL` | `true` |
| G-3 | 실거래 동의 기록 | `GET /api/user-account/real-trading-consent?userId=<acct>` | `consented=true` + `realTradingConsentAt` non-null |

> G-1·G-2·G-3 셋 다여야 REAL 주문이 KIS로 나간다(TradingModeGateImpl 조건 1·2·3). 하나라도 빠지면 `REAL_GATE_DENIED:<condition>` 으로 차단되며 잔고 변동 0이다. **canary 종료 후 §7 rollback에서 G-2를 false로 되돌리는 것이 1차 안전선이다.**

### 1-2. KIS 실전 키 (주문용 vps 키 분리 정합)

| # | 항목 | 확인 방법 | 통과 기준 |
|---|------|-----------|-----------|
| K-1 | 주문용 실전 키 등록 | SettingsModal ⚙️ → KIS 키 입력 후 저장 | `user_kis_credential` row `account_type='REAL'` 존재 |
| K-2 | 키 복호화 정상 | canary 직전 MOCK 주문 1회 또는 잔고 조회 1회 | `account API 200` + `SecretCipher.decrypt` 예외 0 |
| K-3 | KIS IP 화이트리스트 | KIS 개발자센터 등록 IP = canary 실행 호스트 공인 IP | 일치 |

> 정보 수집(시세·캔들·호가·잔고)은 항상 실전 키다(사용자 메모리 LOCK). 모의/실전 분기는 **주문 액션의 tr_id에만** 적용된다(REAL=TTTC0012U/0011U, MOCK=VTTC). canary는 REAL tr_id를 사용한다.

### 1-3. 자본 보호 게이트 상태

| # | 항목 | 확인 방법 | 통과 기준 |
|---|------|-----------|-----------|
| P-1 | KillSwitch OFF | `GET /api/system-config/kill-switch?userId=<acct>` 또는 SettingsModal KillSwitchSection | `enabled=false` |
| P-2 | RiskPlan 설정 완료 (ADR 0106) | 대상 전략의 `fixed_stop_loss_pct > 0` + `take_profit_pct > 0` | 둘 다 > 0 (없으면 BUY fail-closed) |
| P-3 | canary hard cap ENV (ADR 0109) | `printenv MAGICJAR_CANARY_*` (§2 LOCK 값) | §2 표 일치 |
| P-4 | RiskConfig 한도 | `risk_config` row 또는 SettingsModal — `maxPositionAmount`·`maxTradesPerDay` | canary 한도 이하로 축소 설정 |

### 1-4. 대상 축소 (canary 범위 LOCK)

| # | 항목 | 통과 기준 |
|---|------|-----------|
| S-1 | 전략 1개 | ACTIVE 전략 정확히 1개만 (나머지 PAUSED) — `SELECT id,name,status FROM strategy WHERE status='ACTIVE'` 결과 1 row |
| S-2 | 종목 1개 | 해당 전략 대상 종목 1개 (유동성 충분한 대형주 권장) |
| S-3 | 인터벌 | 단타 회피 — 1m 시그널이라도 §2 일일 주문 횟수 상한으로 제한 |
| S-4 | 최소 수량 | 1주 (또는 가격 높은 종목은 hard cap notional 이내 최소 정수 수량) |

### 1-5. 운영 인프라

| # | 항목 | 확인 방법 | 통과 기준 |
|---|------|-----------|-----------|
| O-1 | 백업 최신 (ADR 0096) | `_workspace/backups/postgres/daily/` 당일 또는 전일 dump 존재 | RPO 24h 이내 dump 1건 |
| O-2 | Slack 알림 수신 | `MAGICJAR_SLACK_WEBHOOK_URL` 설정 + 테스트 메시지 1회 도달 | Slack 채널에 도달 확인 |
| O-3 | 4 모듈 기동 정상 | `docker compose ps` + `docker logs` ERROR 0 | api·batch·consumer·frontend Up + ERROR 0 |
| O-4 | SLO 관측 가능 (ADR 0095) | Grafana SLO dashboard 진입 | S-1~S-5 패널 렌더 |
| O-5 | 장운영시간 게이트 ON | `printenv MAGICJAR_SIGNAL_MARKET_HOURS_GATE_ENABLED` | `true` (default) |

---

## 2. canary 파라미터 LOCK

canary 1회당 아래 값을 **사전 결정·고정**한다. ENV는 ADR 0109 hard cap 게이트가 강제한다(전략 qty 설정과 독립).

| 파라미터 | LOCK 값(권장) | 강제 위치 | 비고 |
|---------|--------------|-----------|------|
| **기간** | 장중 1일 09:00~15:30 KST | 장운영시간 게이트(MarketSessionResolver + BusinessDayCalendar) | 정규장만. NXT 야간장 비대상 |
| **주문 1건 수량 상한** | 1주 (또는 notional 이내 최소 수량) | `MAGICJAR_CANARY_MAX_ORDER_QTY` (ADR 0109) | 전략 budget과 별개 hard cap |
| **주문 1건 notional 상한** | **350,000원 (2026-06-11 사용자 결재 amend — 구 100,000은 대상 종목 042700 주가 ~27만원에 1주도 차단되어 상향)** | `MAGICJAR_CANARY_MAX_ORDER_NOTIONAL_KRW` (ADR 0109) | qty × price 상한 |
| **일일 REAL 주문 횟수 상한** | 5건/일 (사용자 결재 값) | `MAGICJAR_CANARY_MAX_ORDERS_PER_DAY` (ADR 0109) | RiskConfig maxTradesPerDay와 별개 전역 cap |
| **손실 한도(절대)** | 50,000원 (사용자 결재 값) | RiskConfig `dailyLossLimitAmount` (기존 DAILY_LOSS_LIMIT 룰) | 도달 시 BUY BLOCKED |
| **staging dwell** | 30분 (헌법 §9-2) | 수동 — §3-1 절차 | dev→staging→REAL 단계 |
| **동시 보유 종목** | 1 | RiskConfig `maxConcurrentPositions=1` | MAX_CONCURRENT_POSITIONS 룰 |

> hard cap ENV(ADR 0109)는 **canary 진입을 위한 최후 방어선**이다. 전략 설정 실수·POSITION_LIMIT budget 오산·다전략 누적으로 인한 과대 주문을 OrderRouter 단에서 강제 차단한다. ENV 미설정 또는 0 이하이면 canary 모드가 아니라고 보고 일반 운용으로 진행한다(기존 RiskConfig 게이트만 적용 — fail-open 회피를 위해 §1-3 P-3 체크 의무).

---

## 3. 시작 절차

### 3-1. staging 30분 dwell (헌법 §9-2 — REAL 진입 전 의무)

1. staging compose 또는 MOCK 모드(`MAGICJAR_MODE=MOCK`)로 동일 전략·종목·RiskPlan 기동.
2. 대상 전략 ACTIVE 후 30분 관찰 — 시그널 발생·RiskGateway 통과·MOCK 주문(VTTC) ACK·order.filled.v1 Slack 도달까지 흐름 1회 이상 완주 확인.
3. `docker logs magicjar-consumer-app` 에서 ERROR 0, `RiskGatewayEvaluator BLOCKED` 사유가 의도된 것뿐인지 확인.
4. 30분 무사고 통과 시에만 REAL 진입.

### 3-2. REAL 게이트 ON

순서 LOCK (자본 직결 — 역순 금지):

1. canary hard cap ENV(§2 ADR 0109) `.env` 기입.
2. `MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` `.env` 기입.
3. `scripts/build-and-deploy.sh --no-gradle` (코드 변경 0 가정 — ENV만 갱신 시) 또는 `scripts/build-and-deploy.sh consumer-app api-app` 으로 재배포.
4. 재배포 후 `printenv` 로 G-1·G-2·P-3 재확인(§1-1·§1-3).
5. `GET /api/user-account/real-trading-consent` 로 G-3 동의 재확인.
6. KillSwitch OFF 재확인(P-1).

### 3-3. 첫 시그널 관찰 (개장 직후 09:00~09:30)

1. 대상 전략 1개 ACTIVE 전환(SettingsModal 또는 strategy API). 나머지 전략 PAUSED 유지.
2. 첫 BUY 시그널 발생 시 다음을 1건 단위로 추적(§6 evidence 형식과 동일):
   - `signal` row INSERT → `RiskGatewayEvaluator ALLOWED` 로그 → `order.intent.v1` publish → `OrderRouterKafkaListener OrderIntent source=AUTO` 로그 → `KIS 주문 ACK ODNO=...` → `order.filled.v1` Slack 도달.
3. 첫 1건 완주 확인 전까지 곁에서 관찰(무인 방치 금지).

---

## 4. 모니터링 체크포인트 (시간대별)

각 체크포인트에서 SLO(ADR 0095 S-1~S-5)와 자본 path 1건 단위 정합을 확인한다.

### 4-1. 개장 직후 (09:00~09:30)

| 항목 | 확인 위치 | 정상 기준 |
|------|-----------|-----------|
| 첫 시그널→주문 지연 (S-1) | Grafana `magicjar_signal_to_order_latency` p95 | < 5s |
| KIS WS 연결 (S-2) | Grafana `WS_DISCONNECTED` count / 종목 상세 캔들 배지 | 0건/5분 · 배지 FRESH/OK_REALTIME |
| 첫 주문 ACK | `docker logs` `KIS 주문 ACK` + `order_request.status` | REQUESTED→ACK 전이 |
| hard cap 작동 | (의도적 과대 시그널 발생 시) `CANARY_QTY_CAP` reject 로그 | hard cap 초과 시 REJECTED |

### 4-2. 정오 (12:00~12:30)

| 항목 | 확인 위치 | 정상 기준 |
|------|-----------|-----------|
| 누적 REAL 주문 횟수 | `SELECT count(*) FROM order_request WHERE mode='REAL' AND requested_at::date=CURRENT_DATE` | ≤ 일일 상한(§2) |
| KIS rate limit (S-4) | Grafana `KIS_RATELIMIT` count | < 1건/5분 |
| account API (S-3) | Grafana `/api/account/*` p95 | < 500ms |
| 미체결 주문 누적 | `SELECT * FROM order_request WHERE status IN ('REQUESTED','ACK') AND mode='REAL'` | 비정상 적체 0 |
| 일일 손실 | Positions 화면 또는 `dailyRealizedPnl` | 손실 한도(§2) 이내 |

### 4-3. 마감 전 (15:00~15:30)

| 항목 | 확인 위치 | 정상 기준 |
|------|-----------|-----------|
| KillSwitch 자동 ON (S-5) | Grafana `killswitch.engaged.v1` count | 0건/일 (정상) |
| 미체결 잔여 주문 | 위 SQL | 마감 전 정리 판단(§5-3) |
| 체결-잔고 정합 | Positions 화면 vs KIS 잔고 | OrderReconciliationWorker drift 0 |
| order.filled 알림 누락 | Slack 채널 vs `order_request.status='FULL_FILL'` | 체결 건수 = Slack TRADE 건수 |

---

## 5. 즉시 중단 조건 + 절차

### 5-1. 즉시 중단 트리거 (하나라도 발생 시)

- 의도하지 않은 주문이 KIS로 나감(수량·종목·횟수 hard cap 초과 의심).
- KillSwitch 자동 ON 발생(S-5) — 자본 path 결함 신호.
- KIS rate limit critical(S-4 ≥ 5건/5분) 또는 KIS WS critical 단절(S-2 ≥ 3건/5분).
- 체결-잔고 drift 발생(`reconcile.drift.v1` Slack).
- 시그널→주문 지연 critical(S-1 ≥ 10s for 2m).
- 손실이 한도(§2) 도달.

### 5-2. 중단 절차 (순서 LOCK — R3 runbook §3-1 정합)

1. **KillSwitch ON (1차 즉시)** — SettingsModal KillSwitchSection 토글 ON, 또는 UI 불가 시:
   ```
   PUT /api/system-config/kill-switch?userId=<acct>
   { "engaged": true, "reason": "CANARY_ABORT:<사유>" }
   ```
   → 이후 모든 신규 주문은 OrderRouter Step 4에서 `KILLSWITCH_ON` REJECTED(자본 path 차단). KillSwitch 격리 5대 행동은 R3 §2 참조.
2. **이중 차단 (2차)** — `.env` `MAGICJAR_ALLOW_REAL=false` 로 변경 후 `scripts/build-and-deploy.sh consumer-app api-app` 재배포 → REAL 게이트 자체 폐쇄(`REAL_GATE_DENIED:MAGICJAR_ALLOW_REAL=false`).
3. **미체결 주문 취소** — 미체결(`REQUESTED`/`ACK`) REAL 주문 확인 후 취소:
   - UI: 주문 내역 화면에서 취소, 또는 `ManualOrderController` 취소 경로(`OrderCancelService` → KisOrderHttpAdapter.cancel — REAL tr_id TTTC0013U).
   - 취소 후 `order_request.status` 와 KIS 잔고 재확인.
4. **reconcile 강제** — 체결-잔고 정합 재확인(OrderReconciliationWorker 또는 `ReconcileController`).
5. KillSwitch OFF는 **사용자 단독 결재**(R3 §3-2 LOCK) — canary 중단 후 즉시 OFF 금지, evidence 수집(§6) 완료 후 판단.

### 5-3. 마감 전 미체결 정리 판단

장 마감(15:30) 임박 시 미체결 주문은 다음 거래일로 넘어가지 않도록 §5-2 step 3 절차로 취소하거나, 사용자 판단 하에 유지한다. canary는 1일 단위이므로 종료 시점에 REAL 보유 포지션·미체결을 명시적으로 정리하는 것이 원칙이다.

---

## 6. 종료 후 evidence 수집 (readiness 차기 평가 입력 형식)

canary 1일 종료 후 아래를 1건 단위로 추적·기록한다. readiness 평가 본문이 요구하는 "시그널→리스크→KIS 결과 0/1건 단위 완전 추적 evidence" 형식이다.

### 6-1. 자본 path 1건 단위 추적 SQL

```sql
-- 당일 REAL 시그널 → 주문 → 체결 전 단계 join
SELECT s.id            AS signal_id,
       s.code, s.side, s.price_at_signal,
       o.id            AS order_id,
       o.status        AS order_status,
       o.quantity, o.limit_price, o.mode,
       o.kis_order_no, o.reject_reason,
       o.requested_at, o.acknowledged_at, o.completed_at
FROM   signal s
LEFT   JOIN order_request o ON o.signal_id = s.id
WHERE  s.created_at::date = CURRENT_DATE
  AND  o.mode = 'REAL'
ORDER  BY s.created_at;
```

### 6-2. RiskGateway 판정 추적 (로그)

```bash
docker logs magicjar-consumer-app 2>&1 | grep -E "RiskGatewayEvaluator (ALLOWED|BLOCKED)" | grep "$(date +%Y-%m-%d)"
docker logs magicjar-consumer-app 2>&1 | grep "OrderRouterKafkaListener" | grep -E "OrderIntent|ACK|REJECTED" | grep "$(date +%Y-%m-%d)"
```

### 6-3. audit trail (DB)

```sql
SELECT order_id, user_id, event_type, payload_json, created_at
FROM   order_audit_event
WHERE  created_at::date = CURRENT_DATE
ORDER  BY created_at;
-- 기대 event_type 흐름: KIS_ACK (정상) 또는 REAL_GATE_DENIED/KILLSWITCH_DENIED/KIS_REJECT (차단)
```

### 6-4. 체결·알림 정합 체크리스트

| evidence | 수집 위치 | 기록 항목 |
|----------|-----------|-----------|
| 체결 건 | `order_request.status='FULL_FILL'` + `executed_*` | 체결 수량·가격·시각 |
| TRADE 알림 | Slack 채널 (order.filled.v1) | 체결 건수 = Slack TRADE 메시지 건수 |
| 거절 알림 | Slack 채널 (order.rejected.v1) | 거절 사유 사람어 매핑(NotificationFormatter) |
| SLO 관측 | Grafana 스크린샷 | S-1~S-5 1일 그래프 |
| KillSwitch 발동 | `kill_switch_state` + `killswitch.engaged.v1` | 자동 ON 0건(정상) |

### 6-5. evidence 산출물 위치

- canary 결과 일지: `_workspace/devlog/YYYY-MM-DD-real-canary.html` (`/devlog` slash 발행 — 사용자 메모리 정합).
- readiness 차기 평가(`docs/assessments/`)의 §6 검증 evidence 입력으로 그대로 인용.

---

## 7. rollback — MOCK 복귀

canary 종료 후 무인 운용 진입 결재 전까지 안전 default(MOCK)로 복귀한다.

1. **REAL 게이트 폐쇄** — `.env` `MAGICJAR_ALLOW_REAL=false` (1차) + 필요 시 `MAGICJAR_MODE=MOCK` (2차).
2. canary hard cap ENV(ADR 0109)는 유지해도 무해(MOCK에서는 비활성). 정리 시 `.env`에서 주석 처리.
3. `scripts/build-and-deploy.sh consumer-app api-app` 재배포.
4. 재배포 후 `printenv` 로 `MAGICJAR_ALLOW_REAL=false` 확인 + MOCK 주문(VTTC) 1회로 흐름 정상 재확인.
5. KillSwitch가 canary 중 ON 되었다면, evidence 수집 완료 후 사용자 결재 하에 OFF(R3 §3-2).
6. 대상 전략 status·RiskConfig 한도를 canary 전 값으로 복원할지 사용자 판단.

> rollback 후 시스템은 readiness 평가가 "MOCK 안정 운영 가능"으로 판정한 상태로 복귀한다. canary evidence는 차기 readiness 재평가에서 REAL 점수 상한(현 78) 해제 입력으로 사용된다.

---

## 8. 운영 코드 가드 cross-link

| 가드 | 코드 위치 | runbook 참조 |
|------|-----------|-------------|
| 3중 게이트 | `consumer/.../order/TradingModeGateImpl.kt` | §1-1 G-1·G-2·G-3 |
| KillSwitch 재검증(fail-closed) | `consumer/.../order/OrderRouterKafkaListener.kt` Step 4 | §5-2 |
| 장운영시간 게이트(AUTO) | `OrderRouterKafkaListener.isWithinTradingHours` | §2 기간 · §1-5 O-5 |
| KIS 체결 sync(REAL) | `OrderRouterKafkaListener.ensureKisExecutionSyncIfReal` | §4 정합 |
| RiskGateway 룰 | `consumer/.../risk/RiskGatewayEvaluator.kt` | §2 손실·동시보유 한도 |
| RiskPlan 필수(ADR 0106) | strategy `fixed_stop_loss_pct`·`take_profit_pct` | §1-3 P-2 |
| **canary hard cap(ADR 0109)** | OrderRouter REAL qty/notional/일일횟수 cap (후속 구현) | §2 P-3 |
| KillSwitch 토글 | `api/.../service/KillSwitchService.kt` (`PUT /api/system-config/kill-switch`) | §5-2 |
| TRADE 즉시 Slack | `consumer/.../notification/NotificationDispatcher.kt` (order.filled.v1 P0) | §6-4 |
