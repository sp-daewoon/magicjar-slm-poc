# R-2: KIS API 차단·Rate Limit 대응

> ADR 0094 §3-7 P0-OPS-5 · ADR 0037 (KIS Rate Limit Bucket4j) · KisHttpErrorClassifier evidence 기반

## 0. 사용 시점

다음 evidence 중 하나라도 발견 시:
- `account API GET /api/account/inquire-balance` 502/504 응답
- KIS REST 응답 body에 `rt_cd != "0"` + `msg_cd ∈ {EGW00123, EGW00201, EGW00133}`
- `/system` 페이지에 SystemEvent `category = KIS_RATELIMIT` WARN 누적
- `docker logs magicjar-api 2>&1 | grep "EGW00"` 빈도 ↑
- 사용자 화면 SymbolPanel·Watchlist 데이터 멈춤 (KIS 인증 실패 추정)

R-1 §2-3 분기 LOCK — KIS 분류 시 본 runbook 진입.

---

## 1. 진단 (Diagnose)

### 1-1. Redis 토큰 TTL 확인

KIS OAuth 토큰은 사용자별 + (REAL · MOCK) 분리 캐시. ADR 0042 (Position Bootstrap) + KisTokenCacheHelper 권위.

```bash
# 사용자별 TTL 조회 (초 단위)
docker exec magicjar-redis redis-cli TTL "kis:token:<userId>:REAL"
docker exec magicjar-redis redis-cli TTL "kis:token:<userId>:MOCK"

# 토큰 본문 확인 (디버깅 only — 토큰 평문 노출 주의)
docker exec magicjar-redis redis-cli GET "kis:token:<userId>:REAL" | head -c 80
```

| TTL 값 | 진단 |
|--------|------|
| `-2` (key 없음) | 첫 호출 직전 또는 명시적 DEL 이후 — 정상 |
| `-1` (TTL 없음) | 비정상 — TTL 미설정 결함 (backend-core escalate) |
| `> 0` (예: 21000) | 유효 토큰 — 그러나 KIS 측에서 invalidate 됐을 수 있음 (stale token 결함) |
| `< 60` | 곧 만료 — KisTokenCacheHelper refresh 직전 race risk |

### 1-2. KIS 응답 body grep

`KisHttpErrorClassifier` (`domain/.../market/ratelimit/KisHttpErrorClassifier.kt`) 권위 매트릭스:

```bash
# 최근 5분 KIS 에러 코드 분포
docker logs --since 5m magicjar-api 2>&1 | grep -oE "EGW00[0-9]+" | sort | uniq -c | sort -rn
```

| msg_cd | 의미 | classifier 분류 | 임시 fix |
|--------|------|----------------|----------|
| **EGW00123** | 토큰 invalidate (만료·중복 발급·서버 invalidate) | `STALE_TOKEN` | Redis DEL → 재발급 |
| **EGW00201** | TPS 초과 (1초 5건 한도) | `RATE_LIMIT` | Bucket4j 대기 — onTpsExceeded() signal |
| **EGW00133** | 모의/실전 키 매칭 오류 (모의 키로 실전 endpoint) | `KEY_MISMATCH` | 사용자 SettingsModal에서 키 확인 |
| **EGW00121** | App Key 유효성 실패 | `INVALID_KEY` | 키 재발급 (사용자 화면) |
| 기타 EGW00xxx | 분류 미정의 | `UNKNOWN` | architect spec 발행 의제 |

### 1-3. SystemEvent 누적 확인

`KIS_RATELIMIT` category는 `BatchRedisKisRateLimiterAdapter`·`ApiRedisKisRateLimiterAdapter`·`ConsumerRedisKisRateLimiterAdapter` 3 모듈에서 emit (진단 PR #639 P0 hotfix 정합 — P3 catalog → P1 활성).

```sql
-- 최근 1시간 KIS_RATELIMIT WARN 누적
SELECT actor, COUNT(*) AS cnt, MAX(occurred_at) AS latest
FROM system_event
WHERE category = 'KIS_RATELIMIT'
  AND severity = 'WARN'
  AND occurred_at >= NOW() - INTERVAL '1 hour'
GROUP BY actor
ORDER BY cnt DESC;
```

| 결과 패턴 | 진단 |
|-----------|------|
| `actor = batch-app` 단독 다수 | DailyCandleBackfillJob burst — cron 시간대 정상 |
| `actor = consumer-app` 다수 | 시그널 burst 또는 KIS WS reconnect 후 inquire burst |
| `actor = api-app` 다수 | 사용자 행동 burst (Watchlist 추가·SymbolPanel 진입) |
| 3 모듈 동시 polling 빈도 ↑ | **Bucket4j 단일 글로벌 bucket 정합 검증 의무** (ADR 0037 amend — backlog §0 P1-4 Phase 2) |

---

## 2. 격리 (Isolate)

### 2-1. 영향 범위 파악

| 영향 | 격리 lever |
|------|----------|
| 자본 path (주문) | KillSwitch ON 검토 — R-3 §3 |
| 데이터 path만 (시세·캔들·잔고 조회) | 격리 불요 — fix 까지 사용자 화면 "데이터 없음" 표시 (사용자 메모리 `feedback_no_mock_data` 정합) |
| 토큰 stale 단독 | Redis DEL 즉시 임시 fix (§3-1) |

### 2-2. KillSwitch 검토 분기

- 주문 path가 inquire-balance 의존 → balance 조회 실패가 OrderRouter 진입 차단 → 자본 영향 0 (read-only)
- BUT KisHttpErrorClassifier가 `STALE_TOKEN` 시 retry → 무한 burst risk 있으면 KillSwitch ON 검토

---

## 3. fix (Resolve)

### 3-1. EGW00123 (STALE_TOKEN) 임시 fix

KisTokenCacheHelper hotfix가 자동화될 때까지 수동 fix:

```bash
# 1) Redis DEL — 사용자별 + 환경별 둘 다
docker exec magicjar-redis redis-cli DEL \
  "kis:token:<userId>:REAL" \
  "kis:token:<userId>:MOCK"

# 2) 다음 API 호출 시 KisTokenCacheHelper가 자동 재발급
curl http://localhost:18080/api/account/inquire-balance \
  -H "X-User-Id: <userId>"
# → 첫 호출이 토큰 발급 (~500ms) + 잔고 조회
```

**관찰 evidence**:
```bash
docker logs --since 30s magicjar-api 2>&1 | grep -E "KisToken|access_token"
# 정상: "KisTokenCacheHelper.fetchFresh — user=<userId> type=REAL ttl=21600"
```

자동화 dispatch 의제: `KisTokenCacheHelper` 자동 invalidate (EGW00123 응답 감지 시 자동 DEL + 재발급) — backlog 또는 backend-core 후속 hotfix.

### 3-2. EGW00201 (RATE_LIMIT) — Bucket4j onTpsExceeded 트리거 확인

ADR 0037 Bucket4j-Redis 단일 글로벌 bucket이 4 모듈 동시 polling 시 자동 throttle. onTpsExceeded() signal evidence:

```bash
# 4 모듈 onTpsExceeded 로그 patrol
docker logs --since 5m magicjar-batch    2>&1 | grep "TPS exceeded signal"
docker logs --since 5m magicjar-api      2>&1 | grep "TPS exceeded signal"
docker logs --since 5m magicjar-consumer 2>&1 | grep "TPS exceeded signal"

# 정상 패턴:
# [KisRateLimit/batch] TPS exceeded signal — count={} (Phase 3 Circuit Breaker 합류 대기)
```

`onTpsExceeded()` 호출 빈도 ≥ 10/min → SystemEvent `KIS_RATELIMIT` emit (BatchRedisKisRateLimiterAdapter §163). 사용자 화면 `/system` 페이지에 WARN row 표시.

**Bucket4j 동작 검증** (Redis ZSET):
```bash
# Bucket4j Lettuce backend Redis key 확인 (BatchKisRateLimitProperties §36 정합)
docker exec magicjar-redis redis-cli KEYS "bucket4j:*" | head -10
docker exec magicjar-redis redis-cli TTL "bucket4j:kis:global"
```

### 3-3. EGW00133·EGW00121 (키 결함)

사용자 본인 SettingsModal에서 KIS 키 재확인 — backend sub-agent 자동 fix 불가 (사용자 직접 입력 path만).

```text
1. frontend `/` 진입 → 우상단 ⚙️ SettingsModal
2. "KIS 키" Section
3. 실전 키 (KIS_APP_KEY · KIS_APP_SECRET) + 계좌 (CANO · ACNT_PRDT) 재입력
4. 저장 → DB AES 암호화 (사용자 메모리 `project_kis_key_management` 정합)
5. Redis 토큰 DEL (§3-1) — 새 키로 재발급 강제
```

### 3-4. 알 수 없는 EGW (UNKNOWN)

KisHttpErrorClassifier 분류 매트릭스 미정의 — architect spec 발행 의제. 임시:
```bash
# evidence 수집
docker logs --since 5m magicjar-api 2>&1 | grep -A 2 "msg_cd.*EGW00" | head -30
```

수집 후 `_workspace/backlog.md`에 row 추가 + architect dispatch.

---

## 4. 검증 (Verify)

### 4-1. account API 200 확인

```bash
# 정상 응답 확인
curl -s -o /dev/null -w "%{http_code}\n" \
  http://localhost:18080/api/account/inquire-balance \
  -H "X-User-Id: <userId>"
# → 200 기대

# 응답 body raw 확인 (디버깅)
curl -s http://localhost:18080/api/account/inquire-balance \
  -H "X-User-Id: <userId>" | jq '.holdings | length'
# → 보유 종목 수 (정상: > 0 또는 0 — drift 없음 LOCK)
```

### 4-2. Redis 토큰 정상 발급 확인

```bash
# TTL > 21000 (KIS 토큰 보통 6시간 = 21600초)
docker exec magicjar-redis redis-cli TTL "kis:token:<userId>:REAL"
```

### 4-3. SystemEvent KIS_RATELIMIT 재발사 없음

```sql
-- 최근 5분 KIS_RATELIMIT 신규 emit 0
SELECT COUNT(*) FROM system_event
WHERE category = 'KIS_RATELIMIT'
  AND occurred_at >= NOW() - INTERVAL '5 minutes';
-- → 0 기대
```

### 4-4. frontend 화면 회복 확인

- Dashboard SymbolPanel 실시간 표시 (1초 이내)
- Watchlist 종목 currentPrice 갱신
- Positions 페이지 잔고 표시 (drift 없음)

---

## 5. post-mortem

R-1 §6 통합. 추가 evidence:
- 1주일 누적 KIS_RATELIMIT 빈도 ≥ 10건 → **backlog §0 P1-4 Rate Limit Bucket4j Phase 2** 우선순위 ↑
- EGW00123 stale token 빈도 ≥ 일 5건 → **KisTokenCacheHelper 자동 invalidate hotfix dispatch**
- 알 수 없는 EGW 새로 발견 → **KisHttpErrorClassifier 매트릭스 amend ADR**

### 5-1. ADR cross-link

- **ADR 0037** — KIS Rate Limit Bucket4j 권위 (Phase 1 baseline + Phase 2 amend backlog)
- **ADR 0042** — Position Bootstrap (토큰 부팅 워밍)
- **ADR 0094 §3-7 P0-OPS-5** — 본 runbook 발행 권위
- **ADR 0094 §10-1** — KIS WS 연결률 SLO > 99% (장중 09:00~15:30) — 본 runbook 빈도 ≥ 주 1회 → SLO 갱신 trigger

---

## 6. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-21 | 신규 발행 — ADR 0094 §3-7 P0-OPS-5 · backend-core 1차 dispatch. EGW00123/00201/00133/00121 분류 매트릭스 + Bucket4j 검증 evidence + SystemEvent KIS_RATELIMIT 회복 SQL. |
