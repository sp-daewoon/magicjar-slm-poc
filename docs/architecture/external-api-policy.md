# 외부 API 호출 정책 (KIS · DART)

- 작성일: TBD
- 2026-05-11 갱신: **Yahoo Finance §3 전면 제거** — ADR 0057 (KIS OpenAPI 단일 데이터 출처) 정합. 사용자 명시 "kis에 있는 데이터만 사용하라고" (2026-05-11 dogfooding).
- 2026-05-11 amend: 정보 수집 API는 **prod credential 단일 채널** (모의/실전 분기는 주문 라우터 한정 — 사용자 명시 정책 정정). ADR 0019 supersede 인용 제거 → "Accepted (Yahoo 의존 부분만 보류)"로 정정 — 5m~1h은 1m 합산 가공 방향 확정 (backlog §31 트랙).
- 2026-05-12 amend (v3.2.3 doc cut — backlog §35): **§2-5 NXT 매트릭스 추가** (KIS `fid_cond_mrkt_div_code` 옵션 J/NX/UN 본문 추가) + **KOSPI→UN / KOSDAQ→J 분기 룰 명시** (PR #304 구현 결과 인용) + **§2-5-A·§2-5-B 신설** (raw 출처 인용 — `inquire_time_dailychartprice.py` + `inquire_daily_itemchartprice.py`). ADR 0062·0063 Accepted 정합 (5m~1h 백엔드 precomputed 합산 + NXT 08:00~20:00 KST cover + threshold 700).
- 2026-05-20 amend (ADR 0091): **KOSPI→UN / KOSDAQ→J market-level 분기 폐기**. `UN`은 종목 단위 NXT 후보(`is_kospi200` 또는 `is_kosdaq150`)에만 우선 적용하고, 응답 0건이면 `J`로 fallback한다.
- 2026-05-31 amend: 합산 interval은 3m·5m·15m·30m·90m·1h·4h·12h로 확장한다. 합산봉 재수집은 KIS 1m 원천(`FHKST03010230`)을 다시 수집한 뒤 `CandleAggregator`로 재생성하며, KIS가 직접 제공하지 않는 interval에 대해 외부 보조 API나 mock 데이터를 사용하지 않는다.
- 2026-05-31 amend (ADR 0105): KIS `FHKST03010230`은 과거 1m gap 복구 source로 채택한다. 1m retention은 기본 6개월, admin/manual 최대 1년이며, 장기 backfill은 KIS REST 10 TPS budget 안에서 BACKFILL/INGESTION_LOW priority로 분산 처리한다.
- 2026-06-09 amend (ADR 0118 amend): **`fid_cond_mrkt_div_code` 전종목 `J`(KRX 본시장) 단일화**. ADR 0091의 NXT 후보(`is_kospi200`/`is_kosdaq150`) `UN` 우선 분기를 폐기한다. KIS 측 결함으로 `UN`/`NX` 일봉이 일부 종목에서 2026-02-11 에 동결된 채 stale 행을 반환하는데, fallback은 "UN 0건"일 때만 `J`로 가므로 stale을 그대로 채택해 일부 종목 일봉이 영구 동결됐다(실측: SK하이닉스·LG화학·한국가스 등). `J`만 전 종목 항상 최신·정상이라 `J` 단일 primary로 확정. trade-off로 일봉 거래량에서 NXT 분이 빠지나(본시장 거래량만) 정합성·신뢰성을 우선한다. **REST 시장구분과 WS 통합 체결가(H0UNCNT0·ADR 0124)는 별개 축** — WS는 통합 채택, REST는 J 단일. ADR 0063·0091은 본 amend로 deprecated.

magicJar는 외부 API에 무분별하게 호출하지 않는다. 각 API의 공식 한도와 우리 운영 정책을 정리한다.

> ADR 정합:
> - **ADR 0057** — KIS OpenAPI 단일 데이터 출처 (Yahoo·네이버 등 보조 출처 폐기)
> - **ADR 0062** — 3m·5m·15m·30m·90m·1h·4h·12h 합산 가공 백엔드 precomputed (2026-05-11 Accepted, 2026-05-31 확장)
> - **ADR 0063** — NXT/통합거래 매트릭스 cover (2026-05-11 Accepted)
> - **ADR 0091** — NXT 통합 시장코드 종목 단위 resolver (2026-05-20 Accepted → **2026-06-09 ADR 0118 amend로 deprecated**, `UN` 분기 폐기·전종목 `J` 단일)
> - **ADR 0118 amend** — `fid_cond_mrkt_div_code` 전종목 `J` 단일화 (2026-06-09 Accepted, `UN`/`NX` KIS측 stale 동결 근본 fix) ← **최신**
> - **ADR 0105** — 캔들 Reconciliation + 장기 1m 보관/복구 정책 (2026-05-31 Accepted)
> - **ADR 0037** — KIS API Rate Limit 강제 (초당 10건 + 토큰 버킷)
> - **ADR 0038** — KIS OAuth 24h 토큰 + Redis 분산락
> - **ADR 0039** — 종목 마스터 갱신 Cron (KIS 13시각 +5분)
> - **ADR 0028** — Watchlist 기반 Ingestion (외부 호출의 진입 단위)
> - **ADR 0019** — 캔들 저장 정책 (Accepted, 2026-05-12 §2 NXT/합산 interval 재통합)

---

## 1. 정책 한 줄 요약

> **공식 한도 안에서 강제 통과. 토큰은 단일 store + 23h 사전 갱신 + 분산락. 모든 KIS 호출은 공통 RateLimiter 경유 의무.**

---

## 2. KIS OpenAPI

### 2-1. 공식 한도

| 항목 | KIS 한도 | 위반 시 |
|---|---|---|
| 토큰 발급 | **1분당 1회** (계정·앱키당) | EGW00133 |
| 토큰 유효 | 24시간 | — |
| **REST 호출** | **초당 10건 (TPS)** (계정·앱키당) | **EGW00201 또는 HTTP 429** |
| WebSocket 동시 구독 | **40건** (msg_cd OPSP0008 "MAX SUBSCRIBE OVER" 시 거부 — 2026-06-11 ADR 0123 KIS-first 정정, 기존 "41건" 1건 과대) | 초과 거부. 한도 단위 = **채널 (code × tr_id)** — 종목당 tr_id별 별도 slot. **출처**: open-trading-api `examples_user/kis_auth.py` `__runner()` `if len(open_map.keys()) > 40: raise ValueError("Subscription's max is 40")` |
| 모의/실전 키 분리 | 별도 발급 | base URL도 다름 (`openapivts.koreainvestment.com:29443` vs `openapi.koreainvestment.com:9443`) |

**magicJar credential 정책 (사용자 명시 2026-05-11)**: 정보 수집 API(시세·캔들·호가·종목 마스터 등)는 **prod(실전) credential 단일 채널** 사용. `AccountType.REAL` 자격으로 KIS REST/WS 호출. 모의/실전 분기는 **주문 라우터(거래 액션)에만** 적용. 정보 수집에서 모의 credential 사용 0건.

### 2-2. Rate Limit 강제 (ADR 0037)

| 항목 | 정책 |
|---|---|
| **메커니즘** | 토큰 버킷 (Bucket4j Redis 분산 모드) — 모든 JVM이 동일 Redis bucket key share |
| **공통 진입점** | `domain/.../market/ratelimit/KisRateLimiterPort` — 모든 KIS REST 호출 직전 `acquire(priority)` 의무 |
| **우회 금지** | 어댑터별 직접 RestClient 호출 금지. WebSocket approval(`/oauth2/Approval`)도 limiter 경유(INGESTION) 의무. 단 OAuth 토큰(`/oauth2/tokenP`)은 EGW00133(1분 1회) 별도 한도라 TPS bucket 미합류(Redis 23h cache·분산락 가드) |
| **우선순위 차등 라우팅 (현행 — ADR 0116 §2-A 정식화. commit 2837b2cf, 2026-06-05. ※ 코드 주석의 "ADR 0112 의제"는 채번 충돌 — ADR 0112는 AI 종목발굴이며, 본 결정의 정식 ADR은 0116. R1에서 17파일 주석 정정)** | 단일 bucket → **2-bucket reserve**. shared pool(capacity = bucketCapacity − `userPathReserveTokens`, default 7)은 모든 priority 1차 소비. **USER_PATH 전용 reserve**(`{bucketKey}:user-reserve`, capacity = `userPathReserveTokens`, default 3)는 USER_PATH(주문 hashkey·order-cash·order-rvsecncl + 현재가·차트·잔고)가 shared 소진 시 fallback. 비주문(INGESTION·BACKFILL·INGESTION_LOW)은 reserve 미접근 → 비주문 burst가 shared를 전부 소진해도 주문 묶음이 굶지 않음. 두 bucket 합 = bucketCapacity(KIS 초당 10건 절대 미초과·증설 0). `userPathReserveTokens=0` 시 단일 bucket 회귀(rollback) |
| **Phase 2 계획 (ADR 0116 §2-B — Proposed, 별도 브랜치 · canary 전 급반영 금지)** | 2-bucket reserve → **priority별 별도 버킷 3-tier**: USER 버킷(`kis-rate-limit:user`, capacity 4 — USER_PATH 전용) + SHARED 버킷(`:shared`, capacity 3 — INGESTION approval·watchlist) + BG 버킷(`:bg`, capacity 3 — BACKFILL·INGESTION_LOW). 합=10. **역방향 fallback**: USER_PATH→[user,shared,bg], INGESTION→[shared,bg], BACKFILL/INGESTION_LOW→[bg] (상위 priority가 더 많은 버킷 접근 → 하위 burst가 상위를 굶기지 못함). 현 2-bucket(USER_PATH만 보호) 대비 INGESTION(approval — WS 재연결 자본 path 인접)도 BG cron burst로부터 보호. ENV `MAGICJAR_KIS_PRIORITY_BUCKETS_ENABLED=false` 시 단일 bucket(capacity 10) 회귀 |
| **우선순위 순서** | 사용자 path(현재가·차트·주문 = USER_PATH, reserve fallback 보유) > Watchlist Ingestion·approval(INGESTION) > backfill·corp action(BACKFILL) > 시장 추세 cron(INGESTION_LOW) |
| **refill 방식** | Redis bucket + interval refill. greedy refill은 한 초 안 burst를 키워 KIS EGW00201을 유발할 수 있어 사용하지 않는다. shared·reserve 두 bucket 모두 동일 1초 interval refill |
| **EGW00201 / 429** | 1초 대기 + 1회 재시도. 주문 전 잔고조회는 재시도 후에도 실패하면 주문 미전송 fail-closed로 보류한다. 주문 직후 account 강제 새로고침은 single-flight + debounce(3s)로 KIS 재조회 burst 차단(`AccountQueryService`). 연속 5분 50% 이상 fail 시 5분 circuit breaker(Phase 3 후속) |

### 2-3. OAuth 토큰 (ADR 0038)

| 항목 | 정책 |
|---|---|
| **store** | Redis HASH `kis:token:{scope}` |
| **분산락** | Redisson `RLock` — multi-JVM single-flight 발급 |
| **갱신 마진** | 23h 시점 사전 갱신 (1h 마진) |
| **invalidate** | 401 응답 시 즉시 invalidate + 분산락 재발급 |
| **broadcast** | RTopic — 한 JVM이 invalidate하면 모든 JVM 알림 |
| **L1 캐시** | 각 JVM in-memory copy (Redis read 빈도 절감) |
| **fallback** | Redis 다운 시 in-memory adapter + ERROR 로그 (`[kis-auth][redis-down]`) |
| **scope 분리** | System(시세) vs User(주문·잔고) — 사용자별 분산락 |

### 2-4. 어댑터 매핑

| 어댑터 | 모듈 | 용도 |
|---|---|---|
| `KisChartCandleAdapter` | api | D1·W·MO 사용자 차트 fetch |
| `KisInstrumentInfoAdapter` | api | 종목 상세 (PER·PBR·시총) |
| `KisInvestorTrendAdapter` | api | 투자자 동향 |
| `KisMarketIndexAdapter` | api | 코스피·코스닥 지수 |
| `KisOrderbookAdapter` | api | 호가 |
| `KisRecentTradesAdapter` | api | 최근 체결 |
| `KisInstrumentMasterAdapter` | batch | 종목 마스터 sync (ADR 0039 cron 13개) |
| `KisCorpActionAdapter` | batch | 권리주 일정 |
| `KisDailyCandleBackfillJob` | batch | 일봉 backfill (Watchlist 풀) |
| `KisOrderAdapter` | consumer | 주문·체결 |
| `KisWebSocketBridge` | consumer | 실시간 tick·체결통보 |

모든 어댑터는 `KisRateLimiterPort` 통과 + `KisAuthCommonModule` 토큰 사용 의무.

### 2-5. 캔들 데이터 KIS 단일 출처 (ADR 0057·0062·0063 정합)

2026-05-11 ADR 0057 발효 이후 magicJar의 캔들 데이터는 **KIS OpenAPI 단일 출처**다. Yahoo Finance·네이버 금융 등 보조 출처는 폐기. **모든 호출은 prod credential 단일 채널** (사용자 명시 정책 정정 2026-05-11).

**2026-05-31 갱신 (ADR 0105)**: 3m·5m·15m·30m·90m·1h·4h·12h은 **백엔드 precomputed 합산** (CandleAggregator 도메인 본체 + AggregateBackfillRunner). 1m baseline은 KIS 1m 원천(`FHKST03010230`)이며, 사용자 재수집 path도 같은 원천만 사용한다. 1m은 기본 6개월, 최대 1년을 목표로 하며, `FHKST03010230` 공식 제약은 실전계좌 1회 최대 120건 + 날짜/시간 cursor + KIS 서버 보관 최대 1년이다.

**2026-06-09 갱신 (ADR 0118 amend)**: `fid_cond_mrkt_div_code`는 **전종목 `J`(KRX 본시장) 단일**이다. ADR 0091의 NXT 후보 `UN` 우선 분기는 폐기됐다(`UN`/`NX`가 KIS측 결함으로 2026-02-11 동결된 stale을 반환해 일부 종목 일봉이 영구 동결된 근본 원인). 코드의 `resolveMrktDivCodeCandidates()`는 항상 `listOf("J")`를 반환한다.

| Interval | KIS endpoint | tr_id | fid_cond_mrkt_div_code | 비고 |
|---|---|---|---|---|
| 틱 | WebSocket `H0UNCNT0` (통합 — ADR 0124) | — | — | Redis only. WS 통합과 REST `J` 단일은 별개 축 |
| 1m (1분봉) — 당일 | `inquire-time-itemchartprice` | `FHKST03010200` | `J` | 당일 한정 — Redis & DB |
| **1m (1분봉) — 장기 backfill/reconciliation** | `inquire-time-dailychartprice` | **`FHKST03010230`** | **`J`** | **기본 6개월, 최대 1년. 120건/호출 + 날짜/시간 cursor** |
| **3m·5m·15m·30m·90m·1h·4h·12h (백엔드 합산)** | — | — | — | **CandleAggregator 합산 (ADR 0062 §3 권장안 A-1 백엔드 precomputed). 직접 KIS REST 호출 0건, 1m 원천 기반** |
| 1d (일봉) | `inquire-daily-itemchartprice` | `FHKST03010100` (period='D') | `J` | 전체 기간 — DB 영속 |
| 1wk (주봉) | `inquire-daily-itemchartprice` | `FHKST03010100` (period='W') | `J` | 전체 기간 — DB 영속 |
| 1mo (월봉) | `inquire-daily-itemchartprice` | `FHKST03010100` (period='M') | `J` | 전체 기간 — DB 영속 |
| 1y (년봉) | `inquire-daily-itemchartprice` | `FHKST03010100` (period='Y') | `J` | 전체 기간 — DB 영속 |
| 현재가 | `inquire-price` | `FHKST01010100` | `J` | 시가·고가·저가·거래량 + 등락 |

### 2-5-A. `fid_cond_mrkt_div_code` 매트릭스 (ADR 0118 amend 정합 — 전종목 `J` 단일)

KIS raw spec — 시장 분류 코드 옵션 3종 + magicJar 현행 적용:

| 옵션 | KIS 공식 의미 | magicJar 적용 |
|---|---|---|
| **J** | KRX 주식 (KOSPI + KOSDAQ 포함) | **전종목 단일 채택** (ADR 0118 amend). 항상 최신·정상 |
| **NX** | NXT 단독 | 사용 안 함 |
| **UN** | KRX + NXT 통합 | **사용 안 함 (deprecated)** — KIS측 결함으로 일부 종목 일봉이 2026-02-11 stale 동결, 신뢰 불가 |

**현행 룰** (ADR 0118 amend, 2026-06-09 사용자 결재):
- 모든 종목 → `J` 단일. `is_kospi200`/`is_kosdaq150` 여부와 무관하게 분기 없음.
- 사유: `UN`/`NX`는 KIS측 stale 동결 risk로 일봉 신뢰 불가. `J`만 전 종목 항상 최신.
- trade-off: 일봉 거래량에서 NXT 분 제외(본시장 거래량만). 정합성·신뢰성 우선.
- (역사) ADR 0063 market-level `UN` / ADR 0091 종목 단위 `UN` allow-list는 본 amend로 deprecated.

**raw 출처 인용 (헌법 §3 KIS-first)**:
- 1분봉 페이지네이션: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py`
  - spec line: `fid_cond_mrkt_div_code (str): [필수] 조건 시장 분류 코드 (ex. J:주식, NX:NXT, UN:통합)`
- 일봉: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py`
  - spec line: `fid_cond_mrkt_div_code (str): [필수] 조건 시장 분류 코드 (ex. J:KRX, NX:NXT, UN:통합)` — magicJar는 `J`만 사용(ADR 0118 amend)

### 2-5-B. NXT 시간대 cover 매트릭스 (역사 — REST는 `J` 단일이라 NXT 분 일봉 미반영, ADR 0118 amend)

- **NXT pre-hour**: 08:00 ~ 09:00 KST
- **NXT main-hour**: 09:00 ~ 15:30 KST (KRX 정규장과 동일)
- **NXT after-hour**: 15:30 ~ 20:00 KST
- 1m baseline 720 bar/일 (KRX 정규장 390 + NXT pre/after-hour 330)
- `MIN_1M_CANDLE_THRESHOLD = 700L` (ADR 0063 §2-D 옵션 C 채택 — 720의 97% 마진)
- 합산 interval boundary 정렬 매트릭스는 ADR 0062 §9-2 참조 (ADR 0019 §2-1·ADR 0029 §2-1 정합)

**raw 출처 (헌법 §3)**: https://github.com/koreainvestment/open-trading-api (examples_user/domestic_stock + examples_llm/domestic_stock 디렉토리)

---

## 3. Yahoo Finance — **폐기 (ADR 0057, 2026-05-11)**

ADR 0019 시점에서 분봉 데이터 보조 출처로 활용했으나, 2026-05-11 사용자 dogfooding에서 다음 결함 발견 후 전면 폐기:

- root cause: Yahoo v8 chart endpoint(`https://query1.finance.yahoo.com/v8/finance/chart/{ticker}`)가 한국 오래된 상장종목 `interval=1d&range=max` 조합에서 **monthly downsample** 응답 반환 → 일봉 위치에 월봉이 INSERT되어 사용자 차트 결함
- 사용자 명시 결정 (2026-05-11): "야후꺼 쓰지 말라니깐" / "kis에 있는 데이터만 사용하라고"

후속 처리:
- ADR 0019 — Accepted (Yahoo 의존 부분만 보류 — 폐기 아님, 사용자 명시 "ADR 폐기는 하지말어 백로그로 남겨")
- 본 §3 — 폐기 표기만 보존 (rollback 사례 발생 시 §3-old 복원 참조 — ADR 0057 §4-2)
- `consumer/.../infrastructure/yahoo/YahooCandleFetcherAdapter.kt` — `@Deprecated` 마킹 (별도 market-data sub-agent PR)

**Yahoo 부활 시나리오**: ADR 0057 §4-2 Rollback 시나리오 정독 의무 (KIS rate limit 영향 측정 또는 `FHKST03010230` 도입 후 합산 interval 성능 부족이 발견될 시만 검토).

---

## 4. DART (전자공시)

### 4-1. 공식 한도

| 항목 | 한도 |
|---|---|
| API key | 무료 발급, 1일 최대 10,000건 |
| 응답 형식 | JSON / XML |

### 4-2. 우리 정책

| 영역 | 정책 |
|---|---|
| **호출 위치** | `DartFinancialAdapter` (api) — 종목 상세에서 재무·공시 fetch |
| **빈도** | 종목당 6시간 캐시 (Redis) |
| **재시도** | 5xx → 1회. 404 → fallback (재무 정보 null) |
| **corp_code 맵** | 최초 1회 ZIP 다운로드 (~3.5MB) → in-memory 보존 |

---

## 5. 운영 가이드

### 5-1. 새 어댑터 추가 시 체크리스트

1. [ ] **캔들 데이터는 KIS 단일 출처 의무 (ADR 0057)** — Yahoo·네이버 등 보조 출처 신규 어댑터 발행 금지
2. [ ] **정보 수집 API는 prod credential 단일 채널 의무 (사용자 명시 2026-05-11)** — `AccountType.REAL` 자격으로만 호출. 모의 credential 사용 0건. 모의/실전 분기는 주문 라우터에만 적용.
3. [ ] **`KisRateLimiterPort` 통과** (KIS 어댑터인 경우, ADR 0037 강제)
4. [ ] **`KisAuthCommonModule` 사용** (KIS OAuth 토큰, ADR 0038)
5. [ ] Rate limiter 적용 (DART=종목별 6h 캐시)
6. [ ] 재시도 정책 헬퍼 사용
7. [ ] `application.yml`에 base URL + key env 명시
8. [ ] 본 정책 문서에 어댑터 매핑 한 줄 추가
9. [ ] **KIS-first raw fetch 의무 (헌법 §3)** — 새 KIS endpoint 도입 시 raw URL KDoc 인용

### 5-2. 모니터링

- KIS 토큰 발급 횟수: `[kis-token] 발급` INFO 로그 (기대: 24h당 1회/scope)
- EGW00133 발생: WARN 로그 → 발생 시 분산락 path 점검
- EGW00201 발생: WARN 로그 → Rate Limiter 큐 상태 점검
- DART 5xx: api WARN 로그
- Health: `/actuator/health/kisAuth` (DEGRADED 시 Redis 점검)

### 5-3. KIS 키 회전

- 24h마다 자동 갱신 (KisAuthCommonModule)
- 키 자체 회전 시: SettingsModal → DB AES 암호화 → `KisTokenStorePort.invalidate()` 호출
