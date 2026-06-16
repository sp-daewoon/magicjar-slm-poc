# magicJar 트랙 로드맵

> **선행**: `docs/architecture/master-architecture.md`
> **작성일**: TBD

---

## §1. Wave 구조

| Wave | 범위 | 트랙 ID | AI 호출 | 오프라인 동작 |
|---|---|---|---|---|
| **W1 — Watchlist + 데이터 기반** (오프라인 1순위) | **관심종목 등록·Ingestion (최우선)** + 종목 마스터·시세 fallback·세션·corp action | **T-W1·T-W2·T-S1** + T-MS1·T-IM1·T-CA1 | 0 | 필수 |
| **W2 — 분석 도구** | 80지표·57패턴·DSL·Strategy Builder UI | T-IND·T-PAT·T-DSL·T-SB | 0 | 필수 |
| **W3 — 자동 매매 본체** (룰만) | 백테스트·시나리오 리플레이 QA·screener·signal·risk·order 통합·**reconcile**·**notify**·perf | T-BT·**T-SIM**·T-SC·T-SIG·T-RISK·T-O·**T-RECON**·**T-NOTIFY**·T-PERF | 0 | 필수 |
| **W4 — 시장 추세 + AI 옵션 + MCP 서버** | 시장 지수·섹터 추세·시장 캘린더 + 사용자 트리거 AI 코멘트·기업 분석·AI 종목발굴·MCP server | T-MARKET·T-AI·T-MCP | 옵션 (사용자 트리거만) | 필수 (AI 끊겨도 본체 영향 0) |
| **W5 — 사용자 학습 + 일원화** | LWC 확장 시각화·사용자 학습 시나리오·데이터 정합성 검증 | T-LWC·T-LEARN | 0 | 필수 |

**핵심**:
1. **W1 진입 시 T-W1(Watchlist CRUD UI) → T-W2(Ingestion) → T-S1(Symbol Detail) 순서가 최우선** (ADR 0040 정합). 이 흐름이 끝나면 사용자가 종목 등록·차트·시세까지 가능.
2. W1·W2·W3 끝나면 magicJar 룰 본체 풀 가동 — AI 0건. W4·W5는 옵션·마무리.

---

## §2. 트랙 목록표 (총 25 트랙 — T-SIM Scenario Replay Sandbox Accepted 2026-06-03 · ADR 0107 · T-MARKET Accepted 2026-05-25 · ADR 0100 + 0104 · T-MAN 신규 트랙 2026-05-18 v3.6.15 patch · ADR 0082 Proposed · T-ACCT 신규 트랙 v3.6.14 patch · ADR 0081 Proposed)

| ID | 이름 | 마스터 §위치 | 목표 한 줄 | 선행 | 후행 | Wave | 사이즈 | 담당 |
|---|---|---|---|---|---|---|---|---|
| **T-ONBOARD** ★★ | **Onboarding Setup (ADR 0046 + 0069 + 0074 — 셋업 마법사 + 운영 변경 SettingsModal 풀세트 + 테이블 3종 + AES)** | §3-1 부수 | 첫 부팅 시 KIS 키 등록 마법사 7-step + system_config·user_account·user_kis_credential 테이블 + AES-256-GCM 암호화 + 마스터 키 단일 예외 + **운영 변경 SettingsModal (ADR 0074 풀세트 — KIS 키 변경 PUT/GET/DELETE + KillSwitch 수동 토글 GET·PUT + AI 키 카테고리 사전 정합 · backlog §44+§45 묶음 흡수 · v3.6.5 patch 재발행)** | - | **T-W1** | **W1 ★★** | L | architect+backend-core+frontend |
| **T-W1** ★ | **Watchlist CRUD (UI + REST + DB)** + Position Bootstrap (ADR 0042) | §D.15 | 관심종목 등록 화면(최우선) + REST + Flyway watchlist 테이블 + 부팅 시 KIS 잔고 워밍 | **T-ONBOARD** | T-W2·T-S1·T-SB | **W1 ★** | M | frontend+backend-core |
| **T-W2** ★ | **Watchlist Ingestion Worker** | §A.2 | consumer 1~5초 polling + KIS WS sub + Yahoo cold-start fetch | T-W1 + ADR 0028·0037·0038 | T-S1·W3 | **W1 ★** | L | market-data+backend-core |
| **T-S1** ★ | **Symbol Detail (현재가·종목상세·차트)** | §A.2·§D.17 | Watchlist row 클릭 → 상세 화면 (현재가 + PER/PBR/시총 + LWC 차트) | T-W2 + ADR 0019·0029 | T-SB·T-O | **W1 ★** | M | frontend+market-data |
| T-MS1 | Market Session + Data Fallback | §A.2·§3.2 | 장 상태 인지·시세 fallback·REJECTED audit | T-W2 | W3 | W1 | M | backend-core+market-data |
| T-IM1 | Instrument Master | §A.1 | KOSPI·KOSDAQ·ETF·ETN universe + 검색 + 13시각 cron sync | - | T-CA1·T-IND·T-SC | W1 | M | backend-core+market-data |
| T-CA1 | Corporate Action & Adjusted Price | §A.3 | 액면분할·증자·배당락 보정 + retroactive 재계산 | T-IM1 | T-BT·T-SC | W1 | M | market-data |
| T-IND | Technical Indicators (80) + Source 추상화 | §B.5 | 80지표 일괄 + `IndicatorSource` plug-in 인터페이스 | T-MS1·T-IM1 | T-PAT·T-DSL·T-SIG | W2 | XL | backend-core+ai-engineer |
| T-PAT | Candlestick Patterns (57) | §B.6 | 57개 패턴 인식 엔진 + 차트 마커 | T-IND | T-DSL·T-SIG | W2 | M | backend-core |
| T-DSL | Strategy DSL + sandbox (ADR 0047) | §B.7 | `.kis.yaml` 호환 superset 파서·평가기 + static 검증(AST depth/노드/indicator 호출/화이트리스트) | T-IND·T-PAT | T-SB·T-BT·T-SIG | W2 | L | architect+backend-core |
| T-SB | Strategy Builder UI (GUI 블록 빌더) | §B.8 | GUI 블록 빌더 + 발굴 결과 → Watchlist 등록 | T-W1·T-DSL | T-BT | W2 | XL | frontend |
| T-BT | Backtest Engine 강화 + DSL sandbox (ADR 0047·0056) — R1 stateless run 머지 / **R2 영속 + R3 frontend 잔여 (PR-50 plan 발행 — 사용자 결정 대기)** | §B.9 | 룩어헤드·세금·수수료·슬리피지 모델 + 성과 카드 + 백테스트 timeout 1000ms·abort 정책 + R2 영속 5 endpoint + R3 `/strategy/backtest` 페이지 | T-CA1·T-DSL | T-SIG·T-PERF | W3 | L (R1 머지 · R2 M · R3 M) | backend-core (R2) + frontend (R3) |
| **T-SIM** ★ | **Scenario Replay Sandbox (ADR 0107 Accepted 2026-06-03)** | §C.QA | 장중이 아니어도 과거 KIS candle을 virtual clock으로 재생해 strategy signal → scenario risk → scenario order → scenario fill/reject/held → timeline report를 확인. strategy/risk 순수 계산만 재사용하고 `order.intent.v1`, `OrderRouterKafkaListener`, `KisOrderHttpAdapter`, live 주문·리스크 테이블, Slack TRADE 알림은 절대 사용하지 않는다. | T-BT·T-SIG·T-RISK·T-O·ADR 0105 | T-LEARN·운영 QA | **W3 보강 v3.11.0** | L (Phase 0 docs · Phase 1 pure engine · Phase 2 `scenario_*` · Phase 3 UI) | backend-core + frontend + qa |
| **T-SC** ★ | **Screener (universe × DSL → 후보 + 랭킹 + Redis cache 30m + 옵션 영속 V28 + Strategy Builder 합류) — ADR 0071 Accepted (2026-05-14, architect 자율 결정 · 사용자 위임 동일)** | §C.10 | inline 동기 평가 mode 1차(POST /api/screener/evaluate → universe ~3000 × DSL eval 500ms timeout = ~5s inline) + Redis cache `screener:cache:{dslHash}:{filterHash}` TTL 30m + multi-criteria 4 기준 fixed weights(signal 0.40 + volume 0.30 + atr 0.20 + cap 0.10) + skip 매트릭스(candle 미수집·부족·timeout·error) + 옵션 영속 Flyway V28(screening_run + screening_match, env toggle MAGICJAR_SCREENER_PERSIST=true) + REST 3 endpoint Swagger 4단(POST evaluate + GET runs list + GET runs detail) + Strategy Builder `/strategy` 페이지 "이 전략으로 종목 찾기" 버튼 + ScreenerResultModal + Watchlist 일괄 등록 + universe·candle 영속 reuse → **KIS endpoint 호출 0 LOCK** + AI 호출 0 grep 검증 + mock 합성 0 | T-IM1·T-CA1·T-DSL·T-IND·T-PAT·T-BT·ADR 0047 sandbox | T-SIG·T-O·T-RECON·T-NOTIFY (모두 마감 후) | **W3 ★ (다섯 번째 트랙, v3.6.3 patch)** | M (Phase 1 strategy-engine domain+consumer + Phase 2 backend-core api+Redis+옵션 Flyway V28 + Phase 3 frontend 직렬) | strategy-engine + backend-core + frontend |
| T-SIG ★ | **Signal Engine (룰 매칭 only, AI 호출 0) + sandbox (ADR 0047) + boundary listen (ADR 0064) + 영속 + STOMP fanout — ADR 0066 plan 발행 (2026-05-12, 사용자 결정 대기)** | §C.11 | Watchlist × 활성 전략 → BUY/SELL/HOLD + 강도 + strategy-eval-pool 격리 + timeout 100ms·CPU budget 50ms·auto-disable + Kafka `market.candle.{interval}.closed.v1` 5 listener + signal 테이블 영속 + STOMP `/topic/signal.user.{userId}` | T-MS1·T-IND·T-PAT·T-DSL·T-BT·ADR 0064 | T-RISK·T-O·T-PERF | **W3 ★ (두 번째 트랙)** | L (Phase 1 strategy-engine + Phase 2 backend-core + Phase 3 frontend 직렬) | strategy-engine + backend-core + frontend |
| T-RISK ★ | **Risk & Position (Guard chain 6단) + Signal → Risk Gateway consumer → Order intent 의무 게이트 + risk_config·risk_event·position·signal.risk_status 4 도메인 영속 + auto-PAUSE 10회 + STOMP 알림 + risk.violation.v1 publish — ADR 0067 Accepted, 2026-06-02 `BUY_BLOCKED` + KillSwitch 계층 amend** | §C.13 | 6 룰(POSITION_LIMIT·DAILY_LOSS_LIMIT·MAX_CONCURRENT_POSITIONS·TRADE_FREQUENCY_MINUTE·TRADE_FREQUENCY_DAY·KILL_SWITCH_ON) deterministic 평가 short-circuit + Kafka `signal.generated.v1` listen → `order.intent.v1` publish 또는 `risk.violation.v1` publish + Redis sliding window ZSET + signal.risk_status 컬럼 verdict 갱신 + auto-PAUSE 동일 `strategy_id` 연속 BLOCKED 10회 시 해당 strategy.status=`BUY_BLOCKED` 전환(신규 BUY만 차단, 다른 strategy 영향 0, SELL·익절·손절 평가는 유지) + `KILL_SWITCH_ON`은 `user_id` 전체 하드 스톱으로 BUY·SELL 모두 차단하되 strategy.status 변경 0·AutoPause 누적 제외 + STOMP `/topic/risk.user.{userId}` BLOCKED + AUTO_PAUSED 2 event type + REST 7 endpoint(RiskConfig 3 + RiskEvent 2 + Position 2) | T-MS1·T-SIG·ADR 0066·ADR 0042·ADR 0043 | T-O·T-PERF·T-NOTIFY | **W3 ★ (세 번째 트랙)** | L (Phase 1 strategy-engine + Phase 2 backend-core + Phase 3 frontend 직렬) | strategy-engine + backend-core + frontend |
| T-O ★ | **Order Execution (Risk ALLOWED → KIS REST 매수·매도 + WS H0STCNI0/H0STCNI9 체결 추적 + 사용자 수동 정정/취소 + 3 테이블 영속 + STOMP 4 event + 멱등성 Redis SETNX + 실계좌 3중 게이트) + KillSwitch 재검증 (ADR 0024·0043) + idempotency (ADR 0045) — ADR 0068 본 트랙 마감 Accepted (2026-05-18, Phase 1·2·3 합류 종결 + dogfooding 케이스 5 GREEN + KIS WS H0STCNI9 MOCK subscribe emit=OK · PR #481·#485·#486)** | §C.12 | OrderRouterKafkaListener(order.intent.v1 listen) → OrderIdempotencyGuard(Redis SETNX 60s) → TradingModeGate(env × 2 + 사용자 동의 토글 3중) → KillSwitch 재검증 → order_request INSERT → KisOrderHttpAdapter(TTTC0012U/VTTC0012U 매수 · TTTC0011U/VTTC0011U 매도) → KIS 응답 ACK or REJECT → KisExecutionWsListener(H0STCNI0/H0STCNI9 체결통보) → order_execution INSERT(분할 체결 1:N) → PositionUpdatePort 적용 → STOMP `/topic/order.user.{userId}` 4 event(REQUESTED·FILLED·REJECTED·CANCELLED) + order_audit_event 시계열 + REST 4 endpoint(GET orders list·single·POST cancel + PUT consent) + SettingsModal 동의 토글 + KIS-first raw 인용 4건(TTTC0012U·TTTC0011U·TTTC0013U·H0STCNI0) | **T-RISK (ADR 0067 Accepted)** · T-W2 · T-SIG | T-RECON · T-NOTIFY · T-J · T-PERF | **W3 ★ (네 번째 트랙)** | L (Phase 1 strategy-engine consumer+domain + Phase 2 backend-core api+Flyway V20~V23 + Phase 3 frontend `/orders` 직렬) | strategy-engine + backend-core + frontend |
| **T-RECON** ★ | **Broker Reconciliation (ADR 0041 + 0070 묶음 spec) — v3.6.2 patch 사이클 (Accepted 2026-05-13)** | §C.12·§3-1 부수 | KIS 잔고 cron 폴링(시장 OPEN 5분 + CLOSED 1시간 + manual) + 페이징 흡수(50건/요청 실전·20건 모의) + diff 검출(수량 ≥ 1주·평균단가 ≥ 0.5%·종목 누락) + DB position upsert + Redis position:current SET + Kafka reconcile.drift.v1 + KillSwitchAutoToggler → kill_switch_state.enabled=true + killswitch.engaged.v1 publish + STOMP /topic/recon.user.{userId} 4 event + REST 3 endpoint(manual trigger + runs list + run detail) Swagger 4단 + KIS-first raw 4건(TTTC8434R·VTTC8434R + CTRP6548R + H0STCNI0 cross-ref) | T-O (ADR 0068 Accepted) | T-NOTIFY | **W3 ★ (다섯 번째 트랙, v3.6.2 patch)** | M (Phase 1 strategy-engine + Phase 2 backend-core 직렬) | strategy-engine + backend-core |
| **T-NOTIFY** ★ | **Notification System (ADR 0044 + 0070 묶음 spec) — v3.6.2 patch 사이클 (Accepted 2026-05-13)** | §3-1 부수 | NotificationDispatcher (consumer 7 @KafkaListener — order.filled/rejected/cancelled.v1 + risk.violation.v1 + reconcile.drift.v1 + killswitch.engaged.v1 + kis.ws.disconnected.v1 graceful skip) + SlackWebhookSender(JDK HttpClient + Resilience4j retry 3회 1s·2s·4s) + NotificationFormatter(한국어 5필드) + NotificationRateLimiter(Redis ZSET 60분 P1 슬라이딩 윈도) + NotificationOutboxRetryWorker(@Scheduled 5분 PENDING 재시도) + Flyway V27(notification_outbox + system_config.notification_slack_webhook BYTEA ALTER) + REST 0 LOCK + frontend 0 LOCK | T-RISK·T-O·T-RECON Phase 2 머지 후 | - | **W3 ★ (여섯 번째 트랙, v3.6.2 patch)** | M (Phase 1 backend-core 단독) | backend-core |
| **T-J** ★ | **Trading Journal (ADR 0072 묶음 spec — W3 마지막 사이클, Accepted 2026-05-14)** | §D.16 | trading_journal 영속 (Flyway V29 IF NOT EXISTS + 멱등성 UQ source_table+source_id+event_type) + journal_note 1:N (사용자 메모) + JournalEventConsumer 5 Kafka listen 자동 INSERT (order.filled.v1+signal.generated.v1+risk.violation.v1+reconcile.drift.v1+position.updated.v1) + JournalSummaryComposer 한국어 합성 + REST 3 endpoint Swagger 4단(GET /journal/entries 페이징+필터 · GET /journal/entries/{id} · POST /journal/notes) + KIS endpoint 호출 0 LOCK + AI 호출 0 + mock 합성 0 | T-SIG·T-RISK·T-O·T-RECON (모두 영속 baseline) | T-PERF · W4 AI 합류 | **W3 ★ (마지막 사이클, v3.6.4 patch)** | M (Phase 1 backend-core 직렬) | backend-core+frontend |
| **T-PERF** ★ | **Performance Loop (ADR 0072 묶음 spec — W3 마지막 사이클, Accepted 2026-05-14)** | §C.14 | 5 지표 fixed(총 P&L · 일별 P&L · MDD · 승률 · 평균 손익비) + Flyway V30(performance_summary + pnl_daily IF NOT EXISTS) + batch DailyPerformanceJob 매일 00:30 KST cron (T-2 영업일 일괄 집계 + 5 period UPSERT DAY·WEEK·MONTH·YEAR·ALL) + inline aggregate fallback (today period 한정) + Redis cache TTL 1h(perf:summary·perf:equity) + PerformanceCalculator pure math MDD 알고리즘 NUMERIC(8,4) + REST 2 endpoint Swagger 4단(GET /performance/summary · GET /performance/equity-curve) + Sharpe·Sortino·CAGR backlog §61(post-MVP) + AI 호출 0 LOCK | T-J·T-SIG·T-RISK·T-O (모두 영속 baseline) | W4 AI 합류 | **W3 ★ (마지막 사이클, v3.6.4 patch)** | M (Phase 2 backend-core 직렬) | backend-core+frontend |
| **T-NEWS** ★ | **뉴스·공시 실시간 인입 (ADR 0075 Accepted 2026-05-15 · 사용자 결정 8 의제 채택 · ADR 0073 supersede)** | §A.4 · §B-3 · §C.13 | DART 공시 실시간 폴링 빈도 강화 1분(현 5분 → 1분 의제 2-1) + Flyway V32 `disclosure` 영속 (멱등성 UQ ux_disclosure_rcept_no) + `is_correction` 분류 (`[기재정정]` prefix or rm `정` contains) + Naver Open API search/news.json 어댑터 + Flyway V33 `news_article` 영속 (멱등성 UQ ux_news_article_source_stock_url_hash · `<b>` 태그 strip · pubDate RFC 822 파싱 · SHA-256 url_hash) + Kafka 신규 2(disclosure.new.v1·news.collected.v1 retention 7d) + Redis dedup + rate limit (DART 60/분 + Naver 17/분 ZSET 슬라이딩 hard limit) + REST 2 endpoint Swagger 4단(GET /api/symbols/{code}/news + /disclosures 페이징 + 기간 필터 + onlyCorrection) + 종목 상세 sidebar NewsPanel + DisclosurePanel polling 60s (의제 6-1 sidebar 채택 · STOMP 0 LOCK) + Watchlist 우선 cron + .env NAVER_OPEN_API_CLIENT_ID·SECRET 추가 (의제 3-1 Phase 2 진입 시 .env 단독, SettingsModal UI 0건 1차) + KillSwitch auto-ON 트리거 0(의제 4-2 Slack 알림만 ADR 0044 reuse) + 감성 분석 T-AI 흡수 backlog §62(의제 5-1) + KIS endpoint 호출 0(ADR 0057 시세 단일 + 뉴스 보조 OK 정합) + AI 호출 0 grep 검증 + mock 합성 0("데이터 없음" 한국어 + Naver 키 미등록 안내) + DART list.json + Naver search/news.json 풀 raw fetch 인용(헌법 §3 5단계 절차) | **W3 마감 (T-J·T-PERF 머지)** · ADR 0073 supersede + 0075 Accepted | T-AI (prompt input reuse · news.collected.v1 listen + sentiment UPDATE backlog §62) | **W3.5 v3.6.7 patch (Accepted · 사용자 메모리 release_policy_patch_first 정합 — v3.7.0=W4 LOCK)** | M (Phase 1 market-data + Phase 2 backend-core + Phase 3 frontend 직렬 + Phase 4 RSS 옵션 backlog §53 잔류 · ~25 산출 파일) | market-data + backend-core + frontend |
| **T-MAN** ★ | **사용자 수동 주문 발행 UI + endpoint (ADR 0082 Proposed 2026-05-18 · 사용자 결함 진단 위임 "주문을 하는 화면이 없어 --;; 잔액조회도" · "확장형으로 별도 페이지" · "가상계좌 잔고 등 통합테스트 하려면 이게 있어야해")** | §C.X (master §10 row 0082) | `POST /api/orders/manual` 신규 endpoint (Swagger 4단 + Bean Validation `@Min(1)`·`@Pattern("\\d{6}")` · request `{userId, code, side, priceType, quantity, limitPrice?}` + response 202 Accepted `{orderId:null, status:"ACCEPTED", idempotencyKey, message}`) + Kafka `order.intent.v1` publish(payload `source="MANUAL"` 필드 add-only + sentinel signalId=0L·strategyId=0L + idempotencyKey="manual:${userId}:${epochSec}") + `OrderRouterKafkaListener` 12 step 100% 재사용(자본 path 단일 LOCK) + 3중 게이트 동일 적용(env MAGICJAR_MODE × env MAGICJAR_ALLOW_REAL × user_account.real_trading_consent_at) + KillSwitch step 4 재검증 + T-RISK Guard chain 우회(사용자=최고 관리자 비전 정합) + `/orders/new` 페이지(신규) + `OrderPreviewModal` 이중 confirm + 60s cooldown(LocalStorage) + 8 UI layer 안전망(종목 6자리·수량≥1·LIMIT 가격>0·3중 게이트 표시·KillSwitch LOCK·미리보기 confirm·cooldown·REAL 강조) + ADR 0068 §14-5 cross-ref(TR_ID TTTC0012U/VTTC0012U/TTTC0011U/VTTC0011U raw `examples_llm/domestic_stock/order_cash/order_cash.py` LOCK) + KIS endpoint 신규 호출 0 + AI 호출 0 LOCK + mock 합성 0(시장가 "체결 후 확정" 명시) + `OrderRouterKafkaListener.deserialize()` source 필드 add-only(default "SIGNAL") | **T-O Accepted (ADR 0068)** · T-ONBOARD · 사용자 결함 진단 dogfooding 2026-05-18 KST | T-ACCT(잔고 변화 evidence path 합류) · 향후 backlog §66(position 사전 검증 + 호가·현재가 표시) | **W3 보강 v3.6.15 patch (T-MAN 신규 트랙 — 가상계좌 통합테스트 dogfooding + 사용자 최고 관리자 통제 · `feedback_release_policy_patch_first` 정합)** | M (Phase 1 backend-core api 단일 PR ~250 LOC + Phase 2 frontend 단일 PR ~350 LOC 병렬 가능 · Phase 3 consumer source 필드 add-only ~30 LOC 직렬) | backend-core + frontend |
| **T-ACCT** ★ | **계좌 잔고·예수금·평가금 사용자 노출 endpoint + UI (ADR 0081 Proposed 2026-05-18 · 사용자 결함 진단 위임)** | §C.X (master §10 row 0081) | 3 endpoint(`GET /api/account/balance·positions·summary` Swagger 4단) + 신규 port `KisBalanceQueryPort` + 신규 adapter `ApiKisBalanceQueryAdapter`(api 직접 KIS REST 호출 · consumer 0 touch 관심사 분리) + KIS `inquire-balance` output2 11 필드 LOCK(`dnca_tot_amt`·`nxdy_excc_amt`·`prvs_rcdl_excc_amt`·`cma_evlu_amt`·`tot_evlu_amt`·`nass_amt`·`pchs_amt_smtl_amt`·`evlu_amt_smtl_amt`·`evlu_pfls_smtl_amt`·`asst_icdc_amt`·`asst_icdc_erng_rt`) + Redis cache `account:balance:{userId}` TTL 30s + DTO 3건(`AccountBalanceResponse`·`AccountHoldingResponse`·`AccountSummaryResponse`) + frontend Dashboard 격상(`DashboardPlaceholder → DashboardPage` + `AccountSummaryCard` + `HoldingsTable` + TanStack Query 30s auto-refresh) + `KisCapitalSnapshotProviderAdapter` 실제 어댑터 교체(ADR 0067 §7-1 `@ConditionalOnMissingBean` cross-ref · POSITION_LIMIT 룰 활성화) + KIS endpoint 호출 0 추가(ADR 0037 정합 — `inquire-balance` 1건 재사용) + AI 호출 0 LOCK(룰 5) + mock 합성 0 LOCK + KIS-first raw 4건 inline KDoc 의무(`inquire_balance.py` + `chk_inquire_balance.py` raw URL + 영문 key + 한글 의미 + TR_ID `TTTC8434R`/`VTTC8434R` 분기) | **W3 마감 (T-O·T-RECON·T-NOTIFY·T-J·T-PERF·T-NEWS)** · 사용자 결함 진단 dogfooding evidence 2026-05-18 14:50 KST | T-RISK §7-1 결합(CapitalSnapshotProvider 활성화 → POSITION_LIMIT 룰 실효) · W4 T-AI 후보(잔고 변동 분석 코멘트 backlog) | **W3 보강 v3.6.14 patch (T-ACCT 신규 트랙 — 자동매매 fitness gap 신규 · `feedback_release_policy_patch_first` 정합)** | M (Phase 1·2 backend-core 단일 PR · Phase 3 consumer/risk · Phase 4 frontend 직렬 · Phase 5 옵션) | backend-core + frontend |
| **T-MARKET** ★ | **시장 지수·섹터 추세 + 시장 캘린더 (ADR 0100 + 0104 Accepted)** | §A.2 · master §10 row 0100/0104 | KOSPI·KOSDAQ·NASDAQ·S&P500 지수와 KRX 섹터 추세를 KIS-first로 수집·계산·표시하고, KRX 휴장일 게이트와 웹 시장 캘린더(`/market/calendar`)에서 IPO 등 확장 가능한 중요 일정을 CRUD로 관리. 휴장일에는 국내 수집을 skip하되 기존 snapshot/candle 기반 마지막 추세는 표시해야 하며, `dataInsufficient=true`는 mock 합성이 아니라 초기 지수 데이터 부재를 명시. | T-MS1 · ADR 0013 · ADR 0100 | T-AI · T-MCP · T-LEARN | **W4 ★ (v3.9.0 이후 보강)** | M | market-data + backend-core + frontend + qa |
| T-LWC | LWC 확장 + 자체 도형/주석/패턴 | §D.17 | TradingView Advanced 의존 0 + 자체 overlay layer | T-PAT·T-SIG | - | W5 | M | frontend |
| T-AI | AI Option Console (W4) | §D.18 | 사용자 트리거 LLM 호출 — 종목 코멘트·기업 분석·차트 해석·지표 의미 + 전략 리서치 AI 종목발굴(Claude CLI bridge) | W3 완료 | T-MCP | W4 | L | ai-engineer+frontend |
| T-MCP | MCP Server expose (W4) | §D.19 | magicJar MCP server + tool 5종 | T-AI | - | W4 | M | architect+backend-core |
| T-LEARN | 사용자 학습 + 데이터 정합성 검증 (W5) | §운영 | 사용자와 데이터 흐름·아키 의도·표시 의미 검증 | W4 완료 | - | W5 | M | architect+사용자 |

---

## §3. 트랙별 outline (8 슬롯: 마스터 위치 + 목표 + 선행/후행 + KIS 흡수 + ADR + 데이터 + 산출물 + 비범위)

### T-W2 — Watchlist Ingestion Worker (W1 ★ — 본 PR-9에서 진입)

- **마스터 §위치**: §A.2 · §3-1 [3]
- **목표**: T-W1에서 사용자가 등록한 Watchlist 종목들을 magicJar consumer가 자동 시세 수집하되, watchlist를 실시간 수요의 유일한 단위로 보지 않는다. KIS WebSocket(`H0UNCNT0` KRX+NXT 통합 체결가 단일 채널 — ADR 0124) 실시간 tick + 끊김 시 KIS REST(`FHKST01010100` `inquire-price`) 5초 polling 자동 전환. SymbolDetail은 `DETAIL`, Screener/Strategy 후보는 `STRATEGY` owner로 watchlist 자동 등록 없이 KIS WS 수요를 낸다. 41 stream slot 한도(ADR 0048) 차면 owner priority/TTL 기준으로 watchlist polling demotion 또는 queued 처리. `watchlist.changed.v1`·`symbol.detail.command.v1`·`market.realtime.demand.v1` Kafka 즉시 반응.
- **선행/후행**: T-W1·T-ONBOARD / T-S1·T-MS1·W3 모든 트랙의 시세 인입 전제
- **오프라인 동작**: 필수 (정적 MarketSessionResolver stub 1차 + WS 끊김 시 polling fallback + Yahoo cold-start 실패 시 graceful)
- **KIS 흡수**: KIS open-trading-api repo `examples_llm/domestic-stock/.../websocket_*.py`(WS 메시지 + PIPE 구분자) + `inquire_price.py`·`inquire_daily_itemchartprice.py`(REST 어댑터) 출처 의무 — `feedback_kis_first_policy.md` 정합
- **ADR**: **0048 (본 트랙 진입 시 발행 — 1급 권위)** + 0028 (1~5초 polling — 5초 LOCK) + 0029 (당일 진행 중 캔들 Redis) + **0105 (boot/re-entry/scheduled candle reconciliation + 1m 기본 6개월/최대 1년 retention + data readiness gate)** + 0014 (T1 live·T2 cache) + 0019 (Yahoo 매트릭스) + 0037 (KIS Rate Limit) + 0038 (KIS OAuth + approval_key 분산락) + 0042 STAGE 0-4 (`H0STCNI0` 1 slot reserve) + 0045 (Graceful Shutdown SIGTERM)
- **데이터**:
  - DB — `candle` (V1 기존, 본 트랙 영속 본격 시작) — KIS 1m 기본 6개월/최대 1년 + 합산 interval 재생성. legacy Yahoo 7 interval 정책은 ADR 0057 이후 폐기.
  - Redis — `quote:last:{code}` (TTL 7일) · `candle:current:{code}:{interval}`(1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y) · `candle:today:{code}:1d` legacy · `watchlist:source:{userId}` (HASH 24h) · `kis:approval:{userId}` (TTL 23h)
  - Kafka — `market.tick.v1` · `market.candle.minute.closed.v1` · `market.candle.{interval}.closed.v1` · `watchlist.changed.v1` · `symbol.detail.command.v1` · `market.realtime.demand.v1`
- **산출물 핵심** (PR-9a backend-core + PR-9b market-data 병렬 — `_workspace/specs/pr-9-dispatch-spec.md` 정합):
  - **PR-9a backend-core**: `consumer/.../kafka/WatchlistChangedKafkaListener.kt` + `consumer/.../ingestion/{SubscriptionRouter,WatchlistIngestionWorker,WatchlistPollingWorker,ColdStartExecutor}.kt` + `consumer/.../infrastructure/{yahoo/YahooCandleFetcher,kis/KisRestCandleAdapter,cache/RedisQuoteCacheAdapter,cache/RedisSourceTrackerAdapter,session/SimpleMarketSessionResolver}.kt` + `api/.../persistence/Candle{JpaEntity,Repository,Adapter}.kt` + domain port 4건(`SubscriptionPolicy`·`YahooCandleFetcher`·`SourceTracker`·`CandleRepository`)
  - **PR-9b market-data**: `consumer/.../ws/{KisWebSocketBridge,KisWebSocketSession,KisWsMessageParser}.kt` + `consumer/.../infrastructure/kis/{KisRestPriceAdapter,KisApprovalAdapter}.kt` + `consumer/.../market/TickToMinuteAggregator.kt` + `consumer/.../config/KafkaTopicConfig.kt` + domain `TickEvent` + `KisRestPort.fetchPrice` 확장
- **비범위** (T-S1·T-MS1·T-IM1·T-CA1·T-RECON·T-NOTIFY로 미룸):
  - KIS WS 호가 stream `H0STASP0` 본격 구독·체결통보 `H0STCNI0` listener (T-S1 / T-O)
  - KRX 영업일 캘린더 동기화·임시휴장 (T-MS1)
  - ADR 0014 T3 last-day-close fallback (T-MS1)
  - DailyCloseFinalizer 16:00 KST 1d 영속 (T-MS1)
  - STOMP fanout (T-S1)
  - 종목별 source UI 배지 (T-S1 frontend)
  - `kis.ws.disconnected.v1` Kafka publish + Slack 알림 (T-NOTIFY W3)
  - corp action adjusted 컬럼 (T-CA1)
  - 시간외 단일가 stream `H0STOAA0` (post-MVP)
- **분배 모드**: 병렬 권장 (PR-9 conflict matrix `_workspace/specs/pr-9-conflict-matrix.md` §8)

### T-MS1 — Market Session + Data Fallback (트랙2 spec/plan 그대로 살림, AI 가정 제거)

- **마스터 §위치**: §A.2·§3.2·§7
- **목표**: 장 상태 인지 FSM + 시세 fallback (live → cache → last-day-close) + REJECTED audit + EGW00202 hotfix + dev 환경 모의투자 정상 동작
- **선행/후행**: 트랙1 / W1·W2·W3 모든 트랙의 전제 (장 상태 인지 의무)
- **오프라인 동작**: 필수 (정적 캘린더 시드 + last-day-close fallback + MockTickGenerator)
- **KIS Strategy Builder 흡수**: 없음 (자체 도메인)
- **ADR**: 0013·0014·0015·0016 (4건 일괄)
- **데이터**: `market_calendar` V10·`order_reject_audit` V11. Redis `quote:last:*`·`session:cache:current`. Kafka `order.rejected.v1`·`market.session.v1`
- **산출물 핵심**: 룰 기반 세션 FSM + REJECTED Audit + last-day-close fallback (AI 의사결정 가정 배제)
- **비범위**: KRX 캘린더 자동 동기화 (정적 시드 1차), 분봉 backfill 강화 (별도 트랙), 알림 통합 (post-MVP)

### T-IM1 — Instrument Master

- **마스터 §위치**: §A.1
- **목표**: KOSPI·KOSDAQ·KONEX·ETF·ETN·우선주 universe + KIS·KRX·ISIN 매핑 + 검색 인덱스. 상장폐지 보존(생존자 편향 차단)
- **선행/후행**: 없음 / T-CA1·T-IND·T-DSL·T-BT·T-SC·T-SIG
- **오프라인 동작**: 필수 (정적 시드 fallback + KIS sync는 옵션)
- **KIS 흡수**: KIS `genfile_*.py` 종목 마스터 다운로더 로직 → Kotlin batch job으로 port
- **ADR**: 0017 · 0039 (Cron 갱신)
- **데이터**: `instrument` (V12) · `instrument_alias`. Redis `instrument:search:*`·`instrument:list:*`. Kafka `instrument.updated.v1`
- **산출물**:
  - `domain/.../instrument/` 도메인 + port
  - `batch/.../job/instrument/InstrumentSyncJob.kt` (cron 17:00)
  - `consumer/.../infrastructure/kis/KisInstrumentMasterAdapter.kt`
  - `api/.../controller/InstrumentController.kt` 보강 (검색 + universe 필터)
  - `frontend/src/components/InstrumentSearch.tsx` 자동완성
- **비범위**: 해외 종목·암호화폐, 뉴스·재무 통합 (post-MVP)

### T-CA1 — Corporate Action & Adjusted Price

- **마스터 §위치**: §A.3
- **목표**: 액면분할·증자·배당·합병 보정 + raw + adjusted 양 컬럼 + retroactive 재계산 (룩어헤드 차단의 전제)
- **선행/후행**: T-IM1 / T-BT·T-SC·T-SIG
- **오프라인 동작**: 필수 (일별 batch + 정적 시드)
- **KIS 흡수**: KIS 종목 정보 변경 + DART 공시 양쪽
- **ADR**: 0018
- **데이터**: `corp_action` (V13) · `candle.close_raw`·`close_adjusted` (V14). Kafka `corp_action.applied.v1`
- **산출물**:
  - `domain/.../instrument/CorpAction.kt`
  - `batch/.../job/corp_action/CorpActionSyncJob.kt`
  - `batch/.../job/corp_action/CandleAdjustmentJob.kt` (retroactive 재계산)
  - api 외부 어댑터 — DART 스크래핑 endpoint
- **비범위**: 외국인·기관 한도 변경 추적 (post-MVP)

### T-IND — Technical Indicators (80 일괄) + Source 추상화

- **마스터 §위치**: §B.5
- **목표**: 80지표 일괄 구현 + **`IndicatorSource` plug-in 인터페이스 의무** — KIS 외에서도 받을 수 있게
- **선행/후행**: T-MS1·T-IM1 / T-PAT·T-DSL·T-SIG
- **오프라인 동작**: 필수 (전부 in-process 계산)
- **KIS 흡수**: `strategy_builder/core/indicators.py` 80개 함수 명세 그대로 — Kotlin port. KisStrategyBuilderSource adapter로 등록
- **ADR**: 0021 (source 추상화 핵심) · 0020 (KIS Strategy Builder 흡수 명세 port)
- **데이터**: Redis `indicator:cache:{code}:{interval}:{ind}:{params}` TTL 5분(분봉)/1일(일봉). Kafka 영향 없음
- **산출물**:
  - `domain/.../indicator/IndicatorSource.kt` (port)
  - `domain/.../indicator/IndicatorEngine.kt` (registered source 라우팅)
  - `consumer/.../application/indicator/source/KisStrategyBuilderSource.kt` (1차 — 80개)
  - `consumer/.../application/indicator/source/NativeIndicatorSource.kt` (자체 — 차츰 추가)
  - `consumer/.../application/indicator/source/TaLibSource.kt` (선택, JNI 또는 HTTP — post-W2)
  - `api/.../controller/IndicatorController.kt` `GET /api/indicators?code=...&type=...&params=...&source=...`
  - 정확성 검증: 단위 테스트 fixture 기반 — 표준 입력에 대한 expected output 비교. cross-check 옵션은 트랙 진입 시 별도 검토.
- **비범위**: 사용자 정의 지표 (post-MVP)

### T-PAT — Candlestick Patterns (57)

- **마스터 §위치**: §B.6
- **목표**: 57개 패턴 인식 엔진 + 차트 마커
- **선행/후행**: T-IND / T-DSL·T-SIG (DSL이 패턴을 조건으로 사용, T-LWC가 시각화 강화)
- **오프라인 동작**: 필수 (in-process 룰)
- **KIS 흡수**: 57개 패턴 명세만 (KIS doc 기반)
- **ADR**: T-PAT 진입 시 발행 (0048 후보 — 57 캔들 패턴 표준, README §12 정합)
- **데이터**: 영속 안 함 (실시간 인식 + 차트 마커만). Redis 캐시 선택
- **산출물**:
  - `domain/.../pattern/` (port·spec·match)
  - `consumer/.../application/pattern/JvmPatternRecognizer.kt`
  - `api/.../controller/PatternController.kt` `GET /api/patterns?code=...&from=...&to=...`
  - `frontend/src/components/chart/PatternMarker.tsx` (T-LWC에서 본격 시각화)
- **비범위**: 사용자 정의 패턴 (post-MVP)

### T-DSL — Strategy DSL

- **마스터 §위치**: §B.7
- **목표**: `.kis.yaml` 호환 + magicJar 확장(다중 TF·시간 필터·종목 그룹·패턴 조건)
- **선행/후행**: T-IND·T-PAT / T-SB·T-BT·T-SIG
- **오프라인 동작**: 필수 (in-process 평가)
- **KIS 흡수**:
  - `.kis.yaml` 포맷 그대로
  - `strategy_core/dsl/parser.py` 11개 클래스 (ConditionType·Operator·LogicalOperator·ArithmeticOperator·Indicator·Value·ArithmeticExpr·Condition·CompositeCondition·StrategyDSLParser·StrategyDefinition) → Kotlin sealed class
  - `strategy_core/registry.py` `@register` → Kotlin annotation + `StrategyRegistry`
  - 10개 프리셋 (strategy_NN_*.py) → Kotlin `PresetStrategies` 등록
- **ADR**: 0022 (Strategy DSL = .kis.yaml superset) · **0047 (DSL Evaluation Sandbox — static 검증 + complexity 응답)**
- **데이터**: `strategy` 테이블 본격(V15) · `strategy_dsl_yaml`(raw YAML) · `strategy.disabled_reason`·`last_disabled_at`·`eval_metric_summary` 컬럼 추가(V26 ADR 0047)
- **산출물**:
  - `domain/.../strategy/` 도메인 + AST + port + `StrategyComplexity` data class + `StrategyDisabledReason` enum + `DslDepthExceededException`·`DslNodeCountExceededException`·`UnknownDslSymbolException` (ADR 0047)
  - `consumer/.../application/strategy/KisYamlDslParser.kt` (parse + static 검증 — AST depth ≤ 32·노드 ≤ 10000·indicator 호출 ≤ 50·키워드 화이트리스트)
  - `consumer/.../application/strategy/JvmStrategyEvaluator.kt`
  - `consumer/.../application/strategy/preset/PresetStrategies.kt` (10개)
  - `api/.../controller/StrategyController.kt` CRUD + `POST /api/strategy/validate` + `POST /api/strategy/import?format=kis-yaml` + complexity 응답
- **비범위**: 자연어 → DSL 번역 (W4 후속 옵션) · custom indicator 등록 (post-MVP — 0091 후보)

### T-SB — Strategy Builder UI (GUI 블록 빌더 1차)

- **마스터 §위치**: §B.8
- **목표**: **사용자 명시 — GUI 블록 빌더 1차** (지표 5종 + 진입/청산 1줄 골격) + yaml export/import 동시
- **선행/후행**: T-DSL / T-BT
- **오프라인 동작**: 필수 (frontend in-browser)
- **KIS 흡수**: KIS `frontend/` (Next.js) 흡수 안 함. UX 컨셉(블록·드래그·미리보기) 참고만
- **ADR**: 0009 (frontend SPA) · 0040 (관심종목 우선 UI 흐름)
- **데이터**: 없음 (read·write는 T-DSL의 `strategy` 테이블)
- **산출물**:
  - `frontend/src/pages/Strategy/Builder.tsx` — **GUI 블록 빌더 1차** (드래그·드롭 + 지표 picker + 연산자 + 논리 조합 + 파라미터)
  - `frontend/src/components/strategy/{ConditionEditor,IndicatorPicker,LogicComposer,YamlExportImport}.tsx`
  - `frontend/src/components/strategy/BacktestPreview.tsx` (T-BT와 결합)
  - **YAML export/import 동시 지원** — GUI에서 만든 전략을 .kis.yaml 다운로드 + .kis.yaml 업로드 → GUI 블록 자동 재구성
  - 2차 (post-W2): 풀 GUI (모든 80지표 picker · 패턴 picker · 다중 TF · 시간 필터 GUI)
- **비범위**: 다중 사용자 공유 (post-MVP)

### T-BT — Backtest Engine 강화

- **마스터 §위치**: §B.9
- **목표**: 룩어헤드 차단·세금·수수료·슬리피지 모델 + 성과 카드 (Sharpe·Sortino·MDD·CAGR·승률·평균 수익/손실·turnover)
- **선행/후행**: T-CA1·T-DSL / T-SIG·T-PERF
- **오프라인 동작**: 필수 (영속 candle만 있으면)
- **KIS 흡수**: KIS는 백테스트 엔진 미공개. magicJar 자체 (현행 BacktestRunJob 확장)
- **ADR**: **0047 (DSL Evaluation Sandbox — 백테스트 timeout 1000ms·abort 정책)** + T-BT 진입 시 추가 발행 (0049 후보 — Backtest 표준, README §12 정합)
- **데이터**: `backtest_run` 현행 + `backtest_summary` 보강 + `strategy_eval_metric` (V27 ADR 0047 — 백테스트 평가 metric 적재)
- **산출물**:
  - `batch/.../job/backtest/BacktestRunJob.kt` v2 (T-CORP-ACTION adjusted 사용 + ADR 0047 timeout 1000ms·abort 누적 50회)
  - `domain/.../perf/PerformanceMetrics.kt` + port
  - `api/.../controller/BacktestController.kt` 보강 + abort 결과 페이지
  - `frontend/src/pages/Backtest/index.tsx` 차트 + 통계 + abort 사유 표시
- **비범위**: monte carlo simulation (post-MVP)

### T-SC — Screener (W3 다섯 번째 트랙 — ADR 0071 Accepted 2026-05-14, v3.6.3 patch)

- **마스터 §위치**: §C.10 (ADR 0071 정밀 명세 정합)
- **목표**: universe(instrument 영속) × DSL 조건식 → 후보 + multi-criteria 4 기준 랭킹 fixed weights(signal 0.40 + volume 0.30 + atr 0.20 + cap 0.10) + Redis cache 30m + 옵션 영속 Flyway V28(env toggle) + Strategy Builder "전략으로 종목 찾기" 합류
- **선행/후행**: T-IM1·T-CA1·T-DSL·T-IND·T-PAT·T-BT·ADR 0047 sandbox / T-SIG·T-O·T-RECON·T-NOTIFY (모두 마감 후) — 본 사이클이 W3 마지막 발굴 자동화
- **오프라인 동작**: 필수 (영속 candle + instrument + 룰만, **KIS endpoint 호출 0 LOCK** + AI 호출 0)
- **KIS 흡수**: 자체 — KIS는 screener endpoint 부재 + universe·candle 영속 reuse로 KIS endpoint 호출 0 LOCK
- **ADR**: **0071 Accepted 2026-05-14** (8 의제 A-1~H-1 LOCK) + 0047(DSL Sandbox strategy-eval-pool reuse) + 0017(Instrument Master) + 0019(Watchlist 캔들) + 0022(Strategy DSL) + 0055(Strategy Builder UI 합류점) + 0056(Backtest Engine — DSL eval 패턴 reuse)
- **데이터**:
  - DB — Flyway V28 옵션(`screening_run` + `screening_match` IF NOT EXISTS, env toggle `MAGICJAR_SCREENER_PERSIST=true` 시 ON)
  - Redis — `screener:cache:{dslHash}:{filterHash}` TTL 30m · `screener:universe:{filterHash}` TTL 24h · `screener:lock:{userId}:{strategyId}` TTL 1m (분산락)
  - Kafka — **신규 0 LOCK** (1차 동기 inline 평가, 비동기 모드 `screener.result.v1`은 post-MVP backlog §52)
  - STOMP — **신규 0 LOCK** (REST body 즉시 반환)
- **산출물 핵심** (ADR 0071 §9 Phase 분리 LOCK 정합 — `_workspace/specs/pr-393-t-sc-spec.md`):
  - **Phase 1 strategy-engine (domain + consumer)**: `domain/.../screener/{ScreenerQuery,ScreenerResult,ScreenerMatch,ScreenerFilter,ScreenerStatus,SkipReason,Universe}.kt` + port 4건(`ScreenerEngine`·`InstrumentLookupPort`·`ScreenerCachePort`·`ScreenerRankerPort`) + `consumer/.../screener/{ScreenerEngineImpl,ScreenerRankerImpl,ScreenerStrategyEvalAdapter}.kt` + 단위 테스트 ~12건
  - **Phase 2 backend-core (api + Redis + 옵션 V28)**: `api/.../infrastructure/cache/RedisScreenerCacheAdapter.kt` + `api/db/migration/V28__screening_run.sql` (옵션) + JPA Entity·Adapter (옵션 toggle) + `api/.../service/screener/ScreenerService.kt` + `api/.../controller/screener/ScreenerController.kt` (REST 3 endpoint Swagger 4단) + DTO 6건 + 단위 테스트 ~12건
  - **Phase 3 frontend**: `frontend/src/pages/StrategyBuilderPage.tsx` 수정 + `frontend/src/features/screener/{components/{ScreenerResultModal,ScreenerMatchTable,ScreenerScoreBreakdown,ScreenerSkipReasonsBadge},hooks/{useScreenerEvaluate,useBulkAddWatchlist},api/screenerApi}.tsx` + 단위 테스트 ~7건
- **비범위** (post-MVP backlog로 미룸):
  - universe 5000+ 비동기 모드 (`screener.completed.v1` Kafka publish + STOMP fanout — backlog §52)
  - `/screener` 단독 페이지 (전략 비선택 진입, backlog §53)
  - 사용자 custom 랭킹 가중치 (DSL meta 또는 env override, backlog §54)
  - DailyScreenerJob cron (다음 영업일 09:00 + 후보 Slack push, backlog §55)
  - Screener 결과 AI 해석 (W4 AiCommentProvider 합류, backlog §56)
  - Watchlist bulk POST endpoint 정합 (단건 multi 호출 → bulk, backlog §57)
  - screening_match retention 정책 (영속 ON 시 30일 cron 정리, backlog §58)
  - 사용자 정의 universe (1차는 KOSPI+KOSDAQ active STOCK+ETF default, 사용자 토글은 marketCap·avgVolume 2 필터만 1차 LOCK)

### T-SIG — Signal Engine (룰 매칭 only, AI 호출 0)

- **마스터 §위치**: §C.11
- **목표**: watch list × 활성 전략 → BUY/SELL/HOLD + 강도 + reason. **AI 호출 0**. 시그널 → 주문은 룰 평가 결과 직접
- **선행/후행**: T-MS1·T-IND·T-PAT·T-DSL·T-SC / T-RISK·T-PERF
- **오프라인 동작**: 필수 (in-process 룰)
- **KIS 흡수**: KIS `core/signal.py` Action·Signal·strength → Kotlin
- **ADR**: 0023 (룰 매칭 only — AI 호출 0 명시) · **0047 (DSL Evaluation Sandbox — strategy-eval-pool 격리 + timeout 100ms + auto-disable)**
- **데이터**: `signal` 테이블 (V17). Kafka `signal.generated.v1`. Redis `signal:active:{code}` TTL 5분 · `strategy_eval_metric` (V27 ADR 0047 — 평가 metric 적재) · `risk.violation.v1` payload type 확장 (`STRATEGY_TIMEOUT`·`STRATEGY_CPU_BUDGET_EXCEEDED`·`EVAL_POOL_OVERLOAD`)
- **산출물**:
  - `domain/.../signal/` + `StrategyTimeoutException` (ADR 0047)
  - `consumer/.../application/signal/RealtimeSignalGenerator.kt` (tick listen → 활성 전략 평가, strategy-eval-pool 경유)
  - `consumer/.../application/signal/BatchSignalGenerator.kt` (T-SC screener 결과 → 후속 전략)
  - `consumer/.../config/StrategyEvalPoolConfig.kt` (ADR 0047 — fixed 4 threads, queue 100, CallerRunsPolicy)
  - `api/.../controller/SignalController.kt`
  - `frontend/src/pages/Dashboard/SignalsPanel.tsx`
  - `frontend/src/pages/Strategy/StrategyMetricPanel.tsx` (ADR 0047 — metric·timeout 누적·[다시 활성화] 버튼)
- **비범위**: AI 컨설트 (W4 옵션 분리). 사용자 알림 (W5). per-thread heap allocation tracking (post-MVP — ADR 0047 §2-2-C 0090 후보)

### T-RISK — Risk & Position (Guard chain 6단)

- **마스터 §위치**: §C.13
- **목표**: PositionLimit·DrawdownStop·Position projection. Guard chain 6단 합류
- **선행/후행**: T-MS1·T-SIG / T-PERF·T-RECON
- **오프라인 동작**: 필수 (in-process 정책)
- **KIS 흡수**: KIS `core/position_manager.py` 참조
- **ADR**: 0024 (Risk Guard chain 6단) · **0043 (KillSwitch 행동 정의)**
- **데이터**: `position`·`pnl_daily`·`risk_event` (V18). Kafka `risk.event.v1`
- **산출물**:
  - `domain/.../position/`·`risk/`
  - `consumer/.../application/guard/PositionLimitGuard.kt` (`@Order(250)`)
  - `consumer/.../application/guard/DrawdownStopGuard.kt` (`@Order(280)`)
  - `consumer/.../application/risk/JvmRiskCalculator.kt`
  - `consumer/.../messaging/listener/PositionUpdateListener.kt` (KIS 체결 → position 갱신)
  - `batch/.../job/risk/DailyDrawdownCheckJob.kt`
  - `api/.../controller/{PositionController,RiskController}.kt`
  - `frontend/src/pages/{Positions,Risk}/index.tsx`
- **비범위**: VaR·CVaR 정밀 (post-MVP)

### T-RECON — Broker Reconciliation (ADR 0041 자동매매 fitness 갭 1번 + ADR 0070 묶음 spec)

- **마스터 §위치**: §C.12·§3-1 부수 흐름
- **목표**: KIS 잔고 cron 폴링(시장 OPEN 5분/CLOSED 1시간) + 내부 position diff → drift 시 DB upsert + Redis 갱신 + KillSwitch auto-ON + Kafka publish
- **선행/후행**: T-O / T-PERF·T-NOTIFY
- **오프라인 동작**: 부분 (KIS REST 의존 — 끊겨도 본체 매매 영향 0, drift 검출만 잠시 부재)
- **KIS 흡수**: `inquire_balance.py` 정합 (TR_ID `TTTC8434R`/`VTTC8434R`) + `inquire_account_balance.py` 보조 (TR_ID `CTRP6548R` — 계좌 단위 fast check)
- **ADR**: **0041 (Broker Reconciliation Job) + 0070 (묶음 spec/plan 정밀화)** + ADR 0037 (Rate Limit) + ADR 0038 (OAuth 분산락) + ADR 0043 (KillSwitch 행동)
- **데이터 (ADR 0070 §2-3 정밀화)**: `reconcile_run` 신규(**V25**) · `risk_event` 도메인 enum 보강(V26) · Kafka `reconcile.run.v1` (7d audit) / `reconcile.drift.v1` (30d, ADR 0041 `position.drift.v1` 명명 갱신) / `killswitch.engaged.v1` (30d 신규 publish)
- **산출물 (ADR 0070 §2-2·§2-3 LOCK)**:
  - **Phase 1 strategy-engine** — `domain/.../reconcile/{ReconcileRun,ReconcileResult,HoldingDiff,ReconcileStatus,BalanceReconciler}.kt` + port `{KisBalancePort,ReconcileRunRepository,PositionUpsertPort,KillSwitchTogglePort,ReconcileEventPublisher}.kt` (헌법 §6 abstract LOCK) + `consumer/.../reconcile/{BalanceReconcileJob,KisBalanceAdapter,KisHolding,KillSwitchAutoToggler,ReconcileEventKafkaPublisher}.kt` + `consumer/.../config/ReconcileTopicConfig.kt` + 단위 테스트 ~15건
  - **Phase 2 backend-core** — `api/db/migration/V25__reconcile_run.sql` (IF NOT EXISTS) + `V26__risk_event_position_drift_type.sql` (DDL 변경 0 placeholder) + JPA 1쌍(ReconcileRun) + KillSwitch·Position 어댑터 + `api/.../service/reconcile/{ReconcileQueryService,ReconcileManualTriggerService}.kt` + `api/.../controller/reconcile/ReconcileController.kt` (REST 3 endpoint Swagger 4단 — POST manual + GET runs list + GET runs/{id}) + `api/.../messaging/StompReconcileNotifier.kt` (3 Kafka listen → STOMP `/topic/recon.user.{userId}` 단일 토픽 4 event discriminator) + 단위 테스트 ~10건
- **KIS-first raw 인용 4건 (헌법 §3 의무 — ADR 0070 §8 inline KDoc LOCK)**: TTTC8434R (실전 잔고) + VTTC8434R (모의) + CTRP6548R (계좌 자산 — `inquire-account-balance` 보조 fast check) + H0STCNI0/9 cross-ref (T-O baseline)
- **비범위**: 글로벌 reconcile (1차는 user별만 — backlog §47), drift 임계치 사용자 설정화 (backlog §47), MarketSessionFsm cron skip 통합 (backlog §48), Risk 페이지 reconcile timeline UI (backlog §46 — T-RECON Phase 2 마감 후 frontend 단발 디스패치)

### T-ONBOARD — Onboarding Setup (ADR 0046 운영·UX fitness 갭 3번 — W1 ★★ 최우선)

- **마스터 §위치**: §3-1 부수 흐름 (Onboarding Setup)
- **목표**: 첫 부팅 시 KIS 키 등록 마법사 7-step + 테이블 3종 (system_config·user_account·user_kis_credential) + AES-256-GCM 암호화 + 마스터 키 단일 예외 (.env 1개만). T-W1·T-W2·T-S1보다 먼저 진입 의무.
- **선행/후행**: - / T-W1
- **오프라인 동작**: 부분 (KIS REST `/oauth2/tokenP` 검증 의존 — 인터넷 끊기면 셋업 불가, 단 셋업 후 운영은 캐시·fallback으로 정상)
- **KIS 흡수**: OAuth 토큰 발급(`/oauth2/tokenP`) — `kis_devlp.yaml` 정합 (실전 host `openapi.koreainvestment.com:9443` / 모의 host `openapivts.koreainvestment.com:29443`)
- **ADR**: **0046 (Onboarding & Credential Registration)** + **0069 (SettingsModal vs SetupWizard 책임 분리 + 공통 컴포넌트 추출 + 미래 확장 5 step 표준 절차 — 2026-05-13 dogfooding 결함 응답)** + **0074 (SettingsModal 풀세트 — KIS 키 변경 + KillSwitch 수동 토글 + AI 키 카테고리 사전 정합 · backlog §44+§45 묶음 흡수 · v3.6.5 patch 재발행 2026-05-14)** + ADR 0010 (Multi-tenant 정합) + ADR 0044 (system_config Slack webhook 통합) + ADR 0038 (OAuth 토큰 캐시) + ADR 0068 §16-4 (RealTradingConsentToggle 이중 confirm UI)
- **데이터**: `system_config`·`user_account`·`user_kis_credential` 신규 (V25) · `user_id`=KIS 실전계좌 8자리 · `account_type` 복합키 (REAL/MOCK)
- **산출물**:
  - `domain/.../account/UserAccount.kt` · `UserKisCredential.kt` · `SystemConfig.kt`
  - `domain/.../security/CredentialPort.kt` (interface) · `AesGcmCipherPort.kt`
  - `api/.../controller/OnboardingController.kt` (5 endpoint — status·kis/validate·kis/mock·system-config·complete)
  - `api/.../infrastructure/kis/KisAuthAdapter.kt` (검증용 — `/oauth2/tokenP` 호출)
  - `consumer/.../infrastructure/kis/KisAuthAdapter.kt` (운영용 — 토큰 캐시 + 분산락 ADR 0038 정합)
  - `domain/.../security/AesGcmCipher.kt` (AES-256-GCM 구현)
  - `frontend/src/pages/SetupWizard.tsx` (Step 1~5 화면 — 첫 셋업 베이스, ADR 0069 §1 책임 분리)
  - `frontend/src/components/settings/SettingsModal.tsx` (운영 변경 모달, ADR 0069 §3 1차 scope — RealTradingConsentSection + KisCredentialSection + SystemConfigSection 3 section · **ADR 0074 풀세트로 확장 — Section 2 submit 활성화 + Section 4 KillSwitchSection 신설 + Section 5 AiCredentialSection 슬롯 사전 정합**)
  - `frontend/src/components/settings/forms/` (ADR 0069 §2 공통 입력 컴포넌트 6종 — KisCredentialForm·SecretKeyInput·SettingsSection·ConfirmDangerToggle·SystemConfigForm — SetupWizard·SettingsModal 양쪽 재사용 · **ConfirmDangerToggle 실구현 ADR 0074 Phase 2 — 사유 입력 minLength=5 maxLength=500 + 60초 cooldown**)
  - **ADR 0074 신규 산출물 (v3.6.5 patch 재발행)**:
    - `api/.../controller/SystemConfigController.kt` (신규 — KillSwitch GET·PUT 2 endpoint, Swagger 4단)
    - `api/.../controller/OnboardingController.kt` (3 메서드 추가 — PUT/GET/DELETE `/api/onboarding/kis/*` 변경 전용)
    - `api/.../adapter/redis/KisCacheInvalidator.kt` (신규 — access_token + kis:approval + kis:key-changed-lock 헬퍼)
    - `api/.../messaging/KisKeyChangedProducer.kt` (신규 — Kafka `system.kis-key-changed.v1` publish, retention 7d)
    - `consumer/.../messaging/KisKeyChangedListener.kt` (신규 — `@KafkaListener` graceful reconnect 호출)
    - `consumer/.../ws/KisWebSocketBridge.kt` (메서드 추가 — `reconnectGracefully()` public)
    - `frontend/src/components/settings/sections/KillSwitchSection.tsx` (신규 — Section 4 mount, ConfirmDangerToggle wrap)
    - `frontend/src/api/queries/` (5 hook 신규 — useKisCredentials·useUpdateKisCredential·useDeleteKisCredential·useKillSwitchState·useToggleKillSwitch)
  - Flyway `V25__onboarding_tables.sql` (3 테이블 + masked_account GENERATED 컬럼) · **ADR 0074 Flyway 0건 LOCK — V25 baseline reuse**
  - `.env.example` 갱신 — `MAGICJAR_SECRET_MASTER_KEY` 1개만 + KIS·DART 평문 deprecated 처리
- **비범위**: 외부 secret manager 통합 (post-MVP — 0090 후보), 마스터 키 자동 회전 (post-MVP — 0091 후보 + ADR 0069 backlog §C 정합), 사용자별 권한 분리 (post-MVP — 0092 후보), OpenAI/Anthropic 키 실구현 section (ADR 0074 §4 사전 정합 + T-AI 사이클 실구현 분리), Naver 뉴스 API 키 section (ADR 0069 §7 §F 후속 — T-NEWS 트랙 진입 시 backlog §45 §F), `account_prdt_cd` 변경 path (backlog §45 §H, 사용자 선물옵션 필요 시), `/settings` route 보존 (backlog §45 §J, 모바일 진입 시 모달 vs 페이지 재논의), KIS credential audit log (multi-user 진입 시 backlog §M)

### T-NOTIFY — Notification System (ADR 0044 운영·UX fitness 갭 1번 + ADR 0070 묶음 spec)

- **마스터 §위치**: §3-1 부수 흐름 (Notification 통지)
- **목표**: NotificationDispatcher (consumer) + SlackWebhookSender + notification_outbox 패턴 + 5분 retry worker + 7 Kafka topic 구독
- **선행/후행**: T-RISK·T-O·T-RECON / -
- **오프라인 동작**: 부분 (Slack 외부 의존 — Slack 끊겨도 outbox 보존 + 5분 retry. 본체 매매 영향 0)
- **KIS 흡수**: 없음 (Slack webhook은 KIS 무관)
- **ADR**: **0044 (Notification System) + 0070 (묶음 spec/plan 정밀화)** + ADR 0046 (system_config.notification_slack_webhook AES-256-GCM 정합)
- **데이터 (ADR 0070 §2-3·§2-4 정밀화)**: `notification_outbox` 신규(**V27**) + `system_config.notification_slack_webhook BYTEA` ALTER(V27 통합) · Kafka listen 7 토픽(`order.filled.v1`·`order.rejected.v1`·`order.cancelled.v1`·`risk.violation.v1`·`reconcile.drift.v1`·`killswitch.engaged.v1` + 선택 `kis.ws.disconnected.v1`)
- **산출물 (ADR 0070 §2-4 LOCK — backend-core Phase 1 단독)**:
  - **domain** — `NotificationPayload·NotificationPriority(P0/P1/P2 enum)·NotificationStatus(PENDING·SENT·FAILED)` + port `{NotificationPort,NotificationOutboxRepository,NotificationRateLimiterPort,SlackWebhookPort,SystemConfigSlackPort}.kt` (헌법 §6 abstract LOCK)
  - **consumer** — `NotificationDispatcher.kt` (7 @KafkaListener · `kis.ws.disconnected.v1` ObjectProvider graceful skip) + `SlackWebhookSender.kt` (JDK HttpClient + Resilience4j retry 3회 1s·2s·4s) + `NotificationFormatter.kt` (한국어 메시지 5필드 — 시각 KST + 이벤트 + 사용자 마스킹 + 사유 + 즉시 액션) + `NotificationRateLimiter.kt` (Redis ZSET 60분 sliding window — P1 누적) + `NotificationOutboxRetryWorker.kt` (`@Scheduled(fixedDelay=300000)` 5분 PENDING 재시도 + 24h 누적 `system.notification.failed.v1` publish)
  - **persistence** — `NotificationOutboxJpaEntity + Repository + Adapter` + `SystemConfigSlackAdapter` (AES-256-GCM decryption — ADR 0046 마스터 키 정합)
  - **Flyway** — `V27__notification_outbox.sql` (IF NOT EXISTS — notification_outbox 테이블 + UNIQUE(event_id, channel) + INDEX(status, created_at) + `ALTER TABLE system_config ADD COLUMN IF NOT EXISTS notification_slack_webhook BYTEA`)
  - **REST 0 LOCK** — Slack outbound only · system_config webhook 갱신은 SettingsModal SystemConfigSection (ADR 0069 §3) baseline reuse + 기존 `PUT /api/system-config` (ADR 0046 baseline)
  - **frontend 0 LOCK** — SettingsModal·KillSwitch baseline reuse
  - **단위 테스트** — ~18건 (Dispatcher 7 + Slack 5 + Formatter 7 + Retry 3 일부 중복 제외)
- **비범위**: 이메일·SMS·푸시 채널 (post-MVP — backlog §50), user별 webhook 분리 (post-MVP — backlog §49 + 후속 ADR 0072), 통지 정책 사용자 설정화 (post-MVP — 후속 ADR 0073), ADR 0045 system.startup.v1·system.shutdown.v1 본격 publish (backlog §51 — Graceful Shutdown 트랙 진입 시)

### T-J — Trading Journal (W3 마지막 사이클 — ADR 0072 Accepted 2026-05-14, v3.6.4 patch)

- **마스터 §위치**: §D.16 (ADR 0072 정밀 명세 정합)
- **목표**: order·signal·risk·reconcile·position 5 Kafka 이벤트 통합 시계열 영속 + 사용자 메모 합류 + REST 3 endpoint Swagger 4단
- **선행/후행**: T-SIG·T-RISK·T-O·T-RECON (모두 영속 baseline) / T-PERF · W4 AI 합류
- **오프라인 동작**: 필수 (영속 only, **KIS endpoint 호출 0 LOCK**, AI 호출 0 + mock 합성 0)
- **KIS 흡수**: 없음 — 모든 데이터 영속 reuse(order_request·order_execution·signal·risk_event·reconcile_run·position 5 baseline)
- **ADR**: **0072 Accepted 2026-05-14** (8 의제 A-1~H-1 LOCK) + 0012(매매일지 baseline) + 0066·0067·0068·0070(영속 reuse)
- **데이터**:
  - DB — Flyway V29 (`trading_journal` + `journal_note` IF NOT EXISTS + 멱등성 UQ `ux_trading_journal_source(source_table+source_id+event_type)`)
  - Kafka — listen 5 (order.filled.v1·signal.generated.v1·risk.violation.v1·reconcile.drift.v1·position.updated.v1) — publish 0 LOCK
  - Redis — 옵션 분산락 `journal:lock:{userId}:{eventKey}` (backend-core 자율, DB UQ로 충분 시 생략)
  - STOMP — 신규 0 LOCK (polling)
- **산출물 핵심** (ADR 0072 §9-2 Phase 1 LOCK — `_workspace/specs/pr-402-t-j-perf-spec.md`):
  - **Phase 1 backend-core (api 영역 단일)**: `domain/.../journal/{TradingJournalEntry,JournalNote,JournalEventType}.kt` + port 1(`TradingJournalRepository` 헌법 §6 abstract LOCK) + `api/db/migration/V29__trading_journal_and_note.sql` + JPA 어댑터 5건 + `api/.../messaging/JournalEventConsumer.kt`(5 @KafkaListener) + `JournalSummaryComposer.kt`(한국어 합성) + `TradingJournalService.kt` + `TradingJournalController.kt`(REST 3 endpoint Swagger 4단 한국어 + example) + DTO 3건 + 단위 테스트 ~15건
- **비범위** (post-MVP backlog로 미룸):
  - chart_snapshot 자동 영속 (LWC v4 export to image, backlog §60)
  - journal.recorded.v1 STOMP push (post-MVP, backlog §59)
  - AI 회고 코멘트 ("이 매매 복기해줘" — W4 AiCommentProvider, backlog §62)
  - journal entry soft delete 정책 (backlog §63)
  - 종목별 회고 view `/journal/{code}` (backlog §64)
  - 전략별 회고 view `/journal/strategy/{id}` (backlog §65)

### T-PERF — Performance Loop (W3 마지막 사이클 — ADR 0072 Accepted 2026-05-14, v3.6.4 patch)

- **마스터 §위치**: §C.14 (ADR 0072 정밀 명세 정합)
- **목표**: 5 지표 fixed(총 P&L · 일별 P&L · MDD · 승률 · 평균 손익비) + batch DailyPerformanceJob 매일 00:30 KST cron + inline aggregate fallback + Redis cache TTL 1h + REST 2 endpoint Swagger 4단. **AI 복기는 W4 옵션 분리 (backlog §62)**
- **선행/후행**: T-J·T-SIG·T-RISK·T-O (모두 영속 baseline) / W4 AI 합류
- **오프라인 동작**: 필수 (영속 데이터만, KIS endpoint 호출 0 LOCK, AI 호출 0)
- **KIS 흡수**: 없음 — pnl_daily aggregate + performance_summary pure math
- **ADR**: **0072 Accepted 2026-05-14** + 0012 baseline cross-ref
- **데이터**:
  - DB — Flyway V30 (`performance_summary` + `pnl_daily` IF NOT EXISTS + UQ user_id·trade_date)
  - Redis — `perf:summary:{userId}:{period}:{periodStart}:{periodEnd}` TTL 1h + `perf:equity:{userId}:{from}:{to}` TTL 1h + invalidation pattern (pnl_daily INSERT 시 SCAN+DEL)
  - Kafka — 신규 0 LOCK (publish + listen 모두 0)
  - STOMP — 신규 0 LOCK (polling)
- **산출물 핵심** (ADR 0072 §9-3 Phase 2 LOCK):
  - **Phase 2 backend-core (api + batch 영역)**: `domain/.../performance/{PerformanceSummary,EquityPoint,PnlDaily}.kt` + port 2(`PerformanceRepository`·`PerformanceCalculator` 헌법 §6 abstract LOCK) + `api/db/migration/V30__performance_summary_and_pnl_daily.sql` + JPA 어댑터 5건 + `RedisPerformanceCacheAdapter.kt`(TTL 1h + invalidation) + `PerformanceService.kt`(분산락 분리 0 + 캐시 lookup + inline fallback + 5 period default) + `PerformanceCalculatorImpl.kt`(pure math MDD 알고리즘 NUMERIC(8,4)) + `PerformanceController.kt`(REST 2 endpoint Swagger 4단) + DTO 3건 + `batch/.../job/performance/DailyPerformanceJob.kt`(매일 00:30 KST cron + T-2 영업일 일괄) + 단위 테스트 ~22건
  - **Phase 3 frontend**: `/performance` 페이지 + 5 카드 컴포넌트 + LWC v4 equity curve + daily P&L bar chart (T-J Phase 3와 동일 PR 동반 — frontend 단일 PR)
- **비범위** (post-MVP backlog로 미룸):
  - Sharpe·Sortino·CAGR·Calmar 정밀 지표 (backlog §61)
  - performance_summary 시계열 mini-chart (backlog §66)
  - RFR(Risk-Free Rate) 정책 결정 (Sharpe·Sortino 합산 prereq, backlog §67)
  - AI 복기·튠 제안 (W4 T-AI 옵션 — backlog §62)

### T-NEWS — 뉴스·공시 실시간 인입 (W3.5 v3.6.7 patch · ADR 0075 Accepted 2026-05-15 — 사용자 결정 8 의제 채택 + ADR 0073 supersede)

- **마스터 §위치**: §A.4 News & Disclosure · §B-3 NAVER 옵션 · §C.13 News 19 컴포넌트
- **목표**: DART 공시 실시간 폴링 강화(5분 → 1분 · 의제 2-1) + 정정공시 자동 분류(`is_correction`) + Naver Open API search/news.json 어댑터 + Watchlist 종목 우선 polling 분당 17건 hard limit + 종목 상세 sidebar 뉴스/공시 panel polling 60s (의제 6-1 sidebar 채택 · STOMP 0 LOCK)
- **선행/후행**: T-J·T-PERF 마감 (ADR 0072 v3.6.4 · 모든 데이터 영속 baseline) / T-AI prompt input reuse (news.collected.v1 listen + sentiment UPDATE backlog §62 · T-AI 사이클)
- **오프라인 동작**: 부분 (Naver/DART 미수집 시 본체 영향 0 + frontend "데이터 없음" 한국어 안내 · master §B-3 "끊겨도 본체 영향 0" 정합)
- **KIS 흡수**: 0 (의제 5 KIS-first §B-3 정합 — 시세 단일 KIS LOCK + 뉴스 외부 보조 OK · ADR 0057 정합)
- **외부 데이터 raw fetch (헌법 §3 5단계 절차)**:
  - DART list.json — `https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001` (query param `crtfc_key` + 20K건/일 hard limit + list[] 9 STRING 필드 — corp_cls·corp_name·corp_code·stock_code·report_nm·rcept_no·flr_nm·rcept_dt·rm)
  - Naver search/news.json — `https://developers.naver.com/docs/serviceapi/search/news/news.md` (HTTP header X-Naver-Client-Id + X-Naver-Client-Secret + 25K건/일 hard limit + items[] 5 필드 — title·originallink·link·description·pubDate RFC 822)
- **ADR**: **0075 (Accepted 2026-05-15)** + 0073 (Superseded by 0075 — analysis only baseline) + 0010 §5 (멀티테넌트 news 테이블 baseline) + 0018 (DART 폴링 baseline) + 0044 (T-NOTIFY Slack webhook reuse) + 0057 (KIS-only 시세 단일 + 뉴스 보조 OK) + 0069 §F (SettingsModal NaverApiSection 잔류 — T-AI 흡수)
- **데이터**:
  - DB — `disclosure` (Flyway V32 IF NOT EXISTS · 멱등성 UQ ux_disclosure_rcept_no · 인덱스 stock_code+ingested_at + rcept_dt) + `news_article` (Flyway V33 IF NOT EXISTS · 멱등성 UQ ux_news_article_source_stock_url_hash · 인덱스 stock_code+published_at)
  - Redis — `news:dedup:rcept:{rcept_no}` TTL 24h + `news:dedup:url:{url_hash}` TTL 24h + `news:ratelimit:dart` ZSET 분당 60건 + `news:ratelimit:naver` ZSET 분당 17건 hard limit (sliding window)
  - Kafka — `disclosure.new.v1` (신규 publish market-data Phase 1 · retention 7d) + `news.collected.v1` (신규 publish backend-core Phase 2 · retention 7d)
  - .env — `NAVER_OPEN_API_CLIENT_ID` + `NAVER_OPEN_API_CLIENT_SECRET` 신규 (Phase 2 진입 시 .env 단독 · 의제 3-1)
- **산출물 핵심** (Phase 분리 LOCK 3 PR 직렬 — `_workspace/specs/pr-434-t-news-spec.md` 정합 · ~25 산출 파일):
  - **Phase 1 market-data** (`agent/market-data/t-news-phase1-dart`) — `batch/.../db/migration/V32__t_news_disclosure_table.sql` + `batch/.../infrastructure/dart/DartDisclosureAdapter.kt`(cron 5분→1분 + is_correction 분류 + ADR 0075 §7-4 DART raw 인용 KDoc 의무) + `api/.../persistence/DisclosureJpaEntity.kt` + `api/.../persistence/DisclosureRepository.kt`(ADR 0051 default fun 회피) + `api/.../infrastructure/dart/DisclosureKafkaProducer.kt`(Redis SETNX 24h dedup) + `api/.../controller/SymbolDisclosureController.kt`(Swagger 4단 한국어 + example) + `api/.../service/SymbolDisclosureService.kt`. 단위 테스트 ~10건. KafkaTopicConfig disclosure.new.v1 add.
  - **Phase 2 backend-core** (`agent/backend-core/t-news-phase2-naver`) — `batch/.../db/migration/V33__t_news_article_table.sql`(V32 분리 LOCK §8-3) + `api/.../infrastructure/naver/NaverNewsAdapter.kt`(ADR 0075 §7-5 Naver raw 인용 KDoc 의무 + X-Naver-Client-Id·Secret 헤더 + `<b>` strip + pubDate RFC 822 파싱) + `api/.../infrastructure/naver/NaverNewsRateLimiter.kt`(Redis ZSET 분당 17건 분산) + `api/.../persistence/NewsArticleJpaEntity.kt` + `api/.../persistence/NewsArticleRepository.kt`(UPSERT ON CONFLICT source+stock_code+url_hash) + `api/.../infrastructure/naver/NewsKafkaProducer.kt`(SHA-256 url_hash) + `api/.../controller/SymbolNewsController.kt`(Swagger 4단) + `api/.../service/SymbolNewsService.kt` + `api/.../scheduler/WatchlistNewsScheduler.kt`(Watchlist 우선 cron — 분당 1 종목) + `.env.example` NAVER_OPEN_API_* 신규. 단위 테스트 ~12건. KafkaTopicConfig news.collected.v1 add.
  - **Phase 3 frontend** (`agent/frontend/t-news-phase3-ui`) — `frontend/src/components/symbol/sidebar/NewsPanel.tsx` + `DisclosurePanel.tsx`(정정공시 빨간 배지 강조) + `frontend/src/api/queries/useSymbolNews.ts` + `useSymbolDisclosures.ts`(refetchInterval 60s · queryClient cleanup unmount 시) + `frontend/src/api/dto/SymbolNewsDto.ts` + `SymbolDisclosureDto.ts` + `frontend/src/pages/SymbolDetailPage.tsx`(sidebar mount) + `frontend/src/i18n/ko.ts`(빈 상태 + Naver 키 미등록 안내). 단위 테스트 ~6건. STOMP import 0 + AI provider import 0 grep 검증.
- **비범위** (post-MVP):
  - Phase 4 RSS 한경·구글 어댑터 (Naver 한도 보완 · backlog §53 신규 잔류)
  - 뉴스 감성 분석 (sentiment UPDATE) — T-AI 흡수 backlog §62
  - 뉴스 본문 풀 텍스트 영속 (저작권 risk · 영구 비범위 후보 — title + bodySummary만)
  - 영어 뉴스 (Bloomberg·Reuters) — 한국 주식 본체 비전 한정
  - `/news` 단독 페이지 (의제 6-1 sidebar 채택 · 모바일 진입 시 재논의 backlog §53)
  - STOMP `/topic/news/symbol/{code}` fanout (의제 6-1 polling 채택 · 정정공시 시급 인지 의제 등장 시 backlog §53)
  - SettingsModal NaverApiSection (ADR 0069 §F 잔류 · T-AI 사이클 흡수 ADR 0094+ 가칭)
  - KillSwitch auto-ON 정정공시 자동 트리거 (의제 4-2 Slack 알림만 · false positive risk + 사용자 결정권 보존)
  - 외국인·기관 수급 정밀 (master §11 정합 post-MVP)

### T-ACCT — 계좌 잔고·예수금·평가금 사용자 노출 (W3 보강 fitness gap · ADR 0081 Proposed 2026-05-18 v3.6.14 patch)

- **마스터 §위치**: §10 인덱스 row 0081 (component map 신규 추가 의제 — `_workspace/specs/2026-05-18-account-balance-positions-spec.md` §17)
- **목표**: 사용자 노출 잔고 endpoint 3건 + Dashboard UI + CapitalSnapshotProvider 실제 어댑터 합류 → 자동매매 fitness gap(`feedback_v3_skepticism_no_regression`) 해소
- **선행/후행**: W3 마감 (T-O·T-RECON·T-NOTIFY·T-J·T-PERF·T-NEWS 모두 머지) · 사용자 결함 진단 dogfooding evidence 2026-05-18 14:50 KST / T-RISK §7-1 결합 (CapitalSnapshotProvider POSITION_LIMIT 룰 활성화) · W4 T-AI 후보(잔고 변동 분석 코멘트 backlog)
- **오프라인 동작**: 없음 (KIS REST 의존 — Redis cache 30s fallback OK · `inquire-balance` 호출 실패 시 502)
- **KIS 흡수**: `inquire-balance` (TR_ID `TTTC8434R` 실전 / `VTTC8434R` 모의 · endpoint `/uapi/domestic-stock/v1/trading/inquire-balance` · output1 보유 종목 + output2 11 필드 계좌 합계)
- **ADR**: 0081 Proposed (Account Balance & Positions User-Facing Exposure) · ADR 0042 baseline reuse (consumer KisBalanceAdapter 0 touch — 관심사 분리) · ADR 0067 §7-1 cross-ref 1줄 add-only · ADR 0070 baseline reuse (T-RECON 0 touch) · ADR 0037 정합 (KIS endpoint 호출 0 추가)
- **데이터**:
  - 신규 영속 0건 (Flyway 변경 0 · Redis cache only)
  - Redis cache `account:balance:{userId}` TTL 30s (JSON `AccountBalanceResponse`)
  - KIS output2 11 필드 LOCK (`dnca_tot_amt`·`nxdy_excc_amt`·`prvs_rcdl_excc_amt`·`cma_evlu_amt`·`tot_evlu_amt`·`nass_amt`·`pchs_amt_smtl_amt`·`evlu_amt_smtl_amt`·`evlu_pfls_smtl_amt`·`asst_icdc_amt`·`asst_icdc_erng_rt`)
- **산출물** (예상 ~10 파일):
  - **domain (신규 패키지 `domain/account/`)**:
    - `port/KisBalanceQueryPort.kt` — `fetchFullBalance(userId, accountType) → KisAccountBalance`
    - `KisAccountBalance.kt` — output1·output2 통합 domain class (Spring 의존 0)
  - **api**:
    - `controller/AccountController.kt` — 3 endpoint Swagger 4단 한국어
    - `controller/AccountDto.kt` — `AccountBalanceResponse`·`AccountHoldingResponse`·`AccountSummaryResponse` DTO
    - `service/AccountQueryService.kt` — Redis cache get/set + KIS adapter 호출
    - `infrastructure/kis/ApiKisBalanceQueryAdapter.kt` — KIS REST 직접 호출 + output1·output2 파싱 (raw URL 4건 inline KDoc 의무)
  - **consumer**:
    - `risk/KisCapitalSnapshotProviderAdapter.kt` — Redis cache 읽기 (`account:balance:{userId}` → `netAssetValue`) · `@ConditionalOnMissingBean` default no-op 자동 supersede
  - **frontend**:
    - `api/account.ts` — REST 클라이언트 3건
    - `api/hooks/useAccountSummary.ts` · `useAccountBalance.ts` — TanStack Query 30s auto-refresh
    - `pages/Dashboard/DashboardPage.tsx` — `DashboardPlaceholder` 격상
    - `pages/Dashboard/AccountSummaryCard.tsx` — 총자산 + 예수금 D+2 + 평가손익 + 수익률 4 셀
    - `pages/Dashboard/HoldingsTable.tsx` — 보유 종목 8 컬럼
    - `App.tsx` — DashboardPage import 1줄 갱신
  - **옵션 (Phase 5)**: AppHeader 배지 + PositionsPage BalanceCard
- **dispatch plan**: Phase 1·2 단일 PR (backend-core domain+api · add-only 영역 충돌 0) · Phase 3 backend-core (consumer/risk) · Phase 4 frontend (Phase 2 머지 후) · Phase 5 옵션 (Phase 4 머지 후)
- **release**: v3.6.14 patch cut 권장 (`feedback_release_policy_patch_first` 정합 — T-ACCT는 W3 보강 트랙)
- **비범위**:
  - `inquire-account-balance` (CTRP6548R) 보조 합류 — backlog §47 cross-ref (`TTTC8434R` output2가 충분 · 후속 의제)
  - Account history 영속 (시계열 P&L) — T-PERF 결합 backlog 후속
  - AI 잔고 변동 분석 코멘트 — W4 T-AI backlog
  - AppHeader 배지 + PositionsPage BalanceCard — Phase 5 옵션 (Phase 4 머지 후 결정)
- **dogfooding evidence**: 사용자 (최고 관리자, 2026-05-18 14:50 KST) "지금 잔액조회 예수금 조회 이런게 아예 없는데?" · orchestrator + architect 진단 매트릭스 5건 (KisBalanceAdapter output2 누락 + KisReconcileBalanceAdapter holdings only + PositionController DB-only + frontend 잔고 UI 0건 + CapitalSnapshotProvider Phase 2 미합류) · 사용자 결정 "신규 트랙으로 분리"

### T-LWC — LWC 확장 + 자체 도형/주석/패턴 시각화 (TradingView Advanced 폐기)

- **마스터 §위치**: §D.16
- **목표**: TradingView Advanced 의존 0 + LWC v4 공식 API 한정 + **자체 overlay layer**로 도형·주석·패턴 시각화
- **선행/후행**: T-PAT·T-SIG / -
- **오프라인 동작**: 필수 (frontend in-browser)
- **KIS 흡수**: 없음
- **ADR**: 0025 (LWC 확장 — TradingView Advanced 폐기 명시)
- **데이터**: 없음 (frontend 한정)
- **산출물**:
  - `frontend/src/components/chart/AdvancedChart.tsx` — LWC 확장 main
  - `frontend/src/components/chart/overlay/{ShapeOverlay,AnnotationOverlay,PatternOverlay}.tsx` — Canvas 또는 SVG layer over LWC chart
  - 다중 timeframe 동기 차트 (D + 60m)
  - 패턴 시각화 (T-PAT 통합)
  - 종목 비교 overlay
  - Replay (시점 거슬러 가며 시그널 재생)
- **비범위**: 사용자 정의 그리기 도구 풀세트 (post-MVP — 1차는 사전 정의 도형만)

### T-MARKET — 시장 지수·섹터 추세 + 시장 캘린더 (W4 ★)

- **마스터 §위치**: §A.2 · §10 row 0100/0104
- **목표**: 4대 시장 지수와 KRX 섹터 추세를 투자 참고 지표로 표면화하고, `market_calendar` KRX 휴장일 권위를 batch gate와 웹 캘린더가 공유한다. `/market/calendar`는 휴장일과 사용자/자동 중요 일정(IPO·FOMC·실적·상장폐지 등)을 같은 화면에서 확인·관리한다.
- **선행/후행**: T-MS1 · ADR 0013 / T-AI·T-MCP·T-LEARN
- **오프라인 동작**: 필수. 휴장일에는 국내 수집을 skip하고, 저장된 마지막 지수 snapshot/candle이 있으면 UI는 마지막 추세를 표시한다. 저장 데이터가 0건이면 `dataInsufficient=true`를 그대로 보여주며 mock 합성은 금지한다.
- **ADR**: 0100 (T-MARKET 트랙) · 0104 (시장 캘린더 이벤트 + KRX 휴장일 배치 게이트)
- **데이터**: `market_index_snapshot` V47 · `sector_index_snapshot` V48 · `user_preference` V51 · `market_calendar` V10 · `market_calendar_event` V55. Kafka/STOMP는 ADR 0100 baseline reuse, 일정 CRUD는 REST 동기 응답 우선.
- **산출물 핵심**:
  - batch `BusinessDayCalendar`로 국내 지수/종목 마스터 수집 휴장일 skip
  - API `/api/market/index/*` + `/api/market/calendar` + 일정 CRUD
  - frontend Dashboard/Market Trend + `/market/calendar`
  - 지수 초기 backfill evidence와 `dataInsufficient` 원인 분리
- **비범위**: 미국 휴장일 캘린더 자동 동기화, IPO/FOMC 자동 인입, 지수 초기 backfill 자동 복구는 후속 사이클.

### T-AI — AI Option Console (W4 — 사용자 트리거 LLM 호출)

- **마스터 §위치**: §D.18
- **목표**: **사용자 버튼 클릭 시** LLM 호출 — 종목 코멘트·기업 분석·차트 해석·지표 의미 설명 + 전략 리서치 AI 종목발굴. **매매 트리거 0**
- **선행/후행**: W3 완료 / T-MCP
- **오프라인 동작**: **필수 (AI 끊겨도 본체 흐름 영향 0)** — frontend는 "AI 응답 없음" 표시 + 본체 데이터는 그대로 표시
- **KIS 흡수**: 없음
- **ADR**: 0026 (AI 옵션 보조 — 사용자 트리거 한정) + **0112 (AI 종목발굴 Claude CLI bridge)**
- **데이터**: `ai_comment_log` (V20) · `prompt_template` (V20). Kafka `ai.comment.requested.v1`·`ai.comment.responded.v1`. Redis `ai:prompt:cache:{hash}` TTL 5분
- **산출물**:
  - `api/.../strategyresearch/StrategyResearchAiDiscoveryService.kt` — 자연어 조건 → 후보 JSON → instrument master 매핑
  - `api/.../strategyresearch/StrategyResearchAiTextClient.kt` — `claude -p` 직접 실행 또는 bridge 호출
  - `scripts/claude-discovery-bridge.mjs` — Docker dev용 host Claude CLI bridge
  - `frontend/src/pages/Strategy/StrategyResearchPage.tsx` — AI 종목발굴 패널 + matched 후보 선택
  - `domain/.../ai/AiCommentRequest·Response.kt` (코멘트만, 의사결정 X)
  - `domain/.../ai/port/AiCommentProvider.kt`
  - `consumer/.../application/ai/AnthropicCommentAdapter.kt`
  - `consumer/.../application/ai/GeminiCommentAdapter.kt`
  - `consumer/.../application/ai/OpenAiCommentAdapter.kt` (선택)
  - `consumer/.../application/ai/AiCommentDispatcher.kt` (5분 cache + provider toggle)
  - `consumer/.../application/ai/PromptBuilder.kt` (지표·패턴·뉴스 컨텍스트 조립)
  - `api/.../controller/AiCommentController.kt` `POST /api/ai/explain` + STOMP fanout
  - `frontend/src/components/AiCard.tsx` (사용자 클릭 → 응답 표시)
  - `frontend/src/pages/Symbol/SymbolDetail.tsx`에 [AI 분석 요청] 버튼 추가
- **비범위**:
  - **자동 호출** — 사용자 트리거만, 자동 X
  - **AI 의사결정** — 매매 트리거 절대 X
  - AI 후보 자동 전략 저장, Watchlist 자동 등록, KIS 주문 adapter 호출
  - 자연어 → DSL 번역 (post-W4)
  - RAG·fine-tune (post-MVP)

### T-MCP — MCP Server expose (W4 — magicJar가 server)

- **마스터 §위치**: §D.18·§1-5
- **목표**: magicJar의 도메인을 MCP protocol로 외부 AI 클라이언트(Claude·Cursor)에 노출. tool 5종
- **선행/후행**: T-AI / -
- **오프라인 동작**: 필수 (read 전용 — DB·캐시 직접 접근, AI provider 의존 0)
- **KIS 흡수**: 없음
- **ADR**: 0027 (MCP 서버 미래 노출)
- **데이터**: `mcp_audit` (V21) — 호출 로그
- **산출물**:
  - `domain/.../mcp/McpToolSpec.kt` · `port/McpToolHandler.kt`
  - `api/.../mcp/` 또는 별도 sidecar 컨테이너 (사용자 결정 §5):
    - `get_candles` tool
    - `run_screener` tool
    - `get_journal` tool
    - `explain_indicator` tool (지표·차트·패턴의 정의·계산식·해석을 reference로 반환 — LLM 호출 X, 정적 catalog)
    - `backtest_strategy` tool
  - tool schema (JSON Schema)
  - 인증: API key (1차) + OAuth (post-MVP)
  - 1차 localhost 한정
- **비범위**:
  - write tool (post-MVP — 안전성 검증 후)
  - 다중 사용자 권한 (post-MVP)

### T-LEARN — 사용자 학습 + 데이터 정합성 검증 (W5)

- **마스터 §위치**: §운영
- **목표**: 사용자와 함께 magicJar 데이터 흐름·아키 의도·표시 의미 검증. 교육 시점. 일원화
- **선행/후행**: W4 완료 / -
- **오프라인 동작**: 필수
- **산출물**:
  - `docs/user-guide/` — 사용자 학습 시나리오 (10개 시나리오)
  - `docs/data-flow-checklist.md` — 데이터 정합성 검증 체크리스트
  - `_workspace/sessions/` — 사용자와의 세션 기록
  - `frontend/src/pages/About/SystemTour.tsx` — 시스템 투어 (각 페이지에서 "이 데이터는 어디서 왔는가" 표시)
- **비범위**: 비디오 튜토리얼 (post-MVP)

---

## §4. 의존성 그래프

```
                      트랙1 (DONE — KIS Order)
                       │
                       ▼
                      T-MS1 (Market Session + Fallback) ★ W1
                       │
       ┌───────────────┼───────────────┐
       │               │               │
       ▼               ▼               ▼
      T-IM1 (Instrument) T-RISK (Risk·Position)  ──┐
       │               ▲                     │
       ▼               │                     │
      T-CA1 (Corp Action) │                     │
       │               │                     │
       ▼               │                     │
       │   ┌── T-IND (Indicators 80 + Source 추상화) ★ W2
       │   │           │
       │   │           ├── T-PAT (Patterns 57)
       │   │           │           │
       │   │           ▼           ▼
       │   │          T-DSL (DSL) ◄─────┘
       │   │           │
       │   │      ┌────┼────┐
       │   │      ▼    ▼    │
       │   │     T-SB   T-BT    │
       │   │  (Builder UI) (Backtest v2)
       │   │      │    │   │
       │   ▼      │    ▼   │
       │  T-SC (Screener) ──┘
       │           │
       └───────────┼─────► T-SIG (Signal Engine — 룰 only) ★ W3
                   │           │
                   │           ▼
                   │          T-RISK (Risk·Position 추가)
                   │           │
                   │           ▼
                   │          T-PERF (Perf Loop + Auto-screenshot)
                   │           │
                   │           ▼
                   │     ┌─ W4 ─┐
                   │     ▼      ▼
                   │   T-AI    T-MCP (MCP server)
                   │  (AI Option Console)
                   │     │
                   │     ▼
                   │    W5 ─ T-LWC (LWC 확장) + T-LEARN (사용자 학습)
```

W4는 W3 완료 후 진입. W5는 옵션 (T-LWC는 W3와 병렬 가능).

---

## §5. 권장 진행 순서 (Wave 그래프 + 병렬 가능 매트릭스)

### §5-1. Wave 진행

| Wave | 트랙 | 기간 추정 | 사용자 검증 게이트 |
|---|---|---|---|
| **W1 — 데이터 기반** | **T-ONBOARD ★★ (최우선)** → T-W1·T-W2·T-S1 → T-MS1 + T-IM1 (병렬) → T-CA1 | ~4주 | 셋업 마법사 7-step 완료 + 토요일 dogfooding 통과 + 종목 검색·universe 정상 + 차트 adjusted 정확 |
| **W2 — 분석 도구** | T-IND + T-PAT (병렬) → T-DSL → T-SB | ~5주 | 80지표 정확성·차트 overlay + GUI 블록 빌더 yaml export/import 동작 |
| **W3 — 자동 매매 본체** (룰 only) | T-BT + T-SC (병렬) → T-SIG → T-RISK → T-O → **T-RECON** → **T-NOTIFY** → T-PERF | ~6주 | 시그널 → 주문 e2e 1사이클 + reconcile drift 검출 1사이클 + KillSwitch ON 시 5대 행동 검증 + Slack 통지 7 trigger 검증 + 일별 perf 기록 + AI 호출 0 검증 |
| **W4 — AI 옵션 + MCP** | T-AI → T-MCP | ~3주 | 사용자 클릭 시 AI 응답 + AI 종목발굴 후보 매핑 + MCP tool 5종 외부 호출 가능 |
| **W5 — 사용자 학습 + LWC 확장** | T-LWC + T-LEARN (병렬) | ~3주 | 사용자 시스템 투어 완료 + 데이터 정합성 체크리스트 통과 |

총 추정: ~21주 (5.5개월) — T-ONBOARD·T-NOTIFY 합류 정합. 단일 인력 + 사용자 검증 시간 + 외부 자원 발급 시간 별도.

**현실 보정**:
- KIS API 호출 한도 도달 시 W1·W3 늘어남
- LLM 비용 부담 시 W4 1주 짧게 (cache 적극)
- 사용자 dogfooding 빈도에 따라 W3 길어질 가능성 (1주 dogfooding 단위 추가)

### §5-2. 병렬 가능 매트릭스

| 동시 가능 조합 | 충돌 위험 | 비고 |
|---|---|---|
| T-MS1 ⊕ T-IM1 | 낮음 | 모듈 영역 분리 (T-MS1=session·order audit, T-IM1=instrument table). Flyway 번호 협의 (V10·V11 vs V12) |
| T-CA1 ⊕ T-IND ⊕ T-PAT | 낮음 | T-CA1=batch·persistence, T-IND=consumer·application(80지표), T-PAT=consumer·application(패턴). 패키지 영역 다름 |
| T-SB ⊕ T-BT | 중간 | 둘 다 frontend·api 영향. T-DSL 산출물(API spec) 확정 후 진입 |
| T-SIG ⊕ T-RISK | 중간 | T-RISK가 T-SIG의 Guard chain에 합류. Order 값(@Order(250) 등)만 협의 |
| T-LWC ⊕ W4 | 낮음 | T-LWC는 frontend 단독, W4는 ai-engineer 단독 |
| T-AI ⊕ T-MCP | 높음 | T-MCP은 T-AI의 LLM provider 추상화 활용. 순차 권장 |

---

## §6. 사이클별 PR 사이즈 가이드 (라운드 분할)

본 마스터는 1번 트랙(20 task XL) 경험 반영해 각 트랙은 R1·R2·R3 라운드 분할 권장. **테스트는 R3 마지막 단계에서 일괄** (사용자 명시 — 코드 먼저 완성, 테스트 마지막).

| 트랙 | R1 (1차) | R2 (2차) | R3 (3차 + 일괄 테스트) |
|---|---|---|---|
| T-MS1 | EGW00202 hotfix + 도메인 + 캘린더 | Guard + audit + resolver | Tick fallback + REST + UI + **테스트 일괄** |
| T-IM1 | 도메인 + Flyway + Sync Job stub | KIS 어댑터 real + 검색 | UI 자동완성 + **테스트 일괄** |
| T-IND | 80지표 일괄 JVM 1차 + Source 추상화 인터페이스 | KisStrategyBuilderSource adapter 등록 + 정확성 cross-check | 캐시 + UI 통합 + **테스트 일괄** (정확성 + 성능 회귀) |
| T-DSL | 파서 + AST | 평가기 + 프리셋 10 | 사용자 import + 검증 + **테스트 일괄** |
| T-SB | GUI 블록 빌더 1차 골격 (지표 5종 + 진입/청산 1줄) | 지표 picker 80개 + 패턴 + 다중 TF | 풀 GUI + **테스트 일괄** |
| T-SIG | 룰 매칭 흐름 | watch list × 활성 전략 + Kafka | UI 패널 + **테스트 일괄** |
| T-AI | Claude CLI bridge 기반 AI 종목발굴 + instrument master 매핑 | Anthropic/Gemini/OpenAI 코멘트 provider + 5분 cache | AI Card UI + 종목발굴 회귀 + **테스트 일괄** |

---

## §7. 사용자 결정 필요 사항 (트랙 시작 전)

| # | 결정 | 영향 트랙 |
|---|---|---|
| 1 | T-IM1 진입 시 KIS 종목 마스터 다운로드 endpoint 사용 OK? (KIS 모의 키 보유 중) | T-IM1 |
| 2 | T-IND 1차 80지표 일괄 vs 30개 우선? (사용자 명시 = 80개 일괄) | T-IND |
| 3 | T-DSL 1차 .kis.yaml 호환 우선 vs magicJar 확장 동시? | T-DSL |
| 4 | T-SB 빌더 UI 1차 GUI 블록 빌더 골격 깊이 (지표 5종 vs 80지표 picker) | T-SB |
| 5 | T-AI AI provider 1차 (Anthropic / Gemini free / OpenAI 중) | T-AI |
| 6 | T-MCP MCP server 위치 (api 모듈 endpoint vs 별도 sidecar) | T-MCP |
| 7 | T-RISK PositionLimit·DrawdownStop default (예: 종목당 10%·일일 -3%) | T-RISK |
| 8 | T-LWC LWC 확장 깊이 (1차 사전 정의 도형 vs 풀 그리기 도구) | T-LWC |

---

## §8. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-06-06 (ADR 0112) | **T-AI 첫 구현 형태로 AI 종목발굴을 편입** — Strategy Research에서 자연어 조건 → Claude CLI bridge → instrument master 매핑 → 후보 카드 흐름을 Accepted로 기록. 자동매매 본체 AI 0건, 주문·전략 저장·watchlist 자동 등록 0 경계를 유지한다. |
| 2026-06-03 (ADR 0107) | **T-SIM Scenario Replay Sandbox 트랙 신설** — v3.11.0 이후 장중이 아니어도 전략 신호·리스크·가상 주문·가상 체결·오류 이벤트를 QA처럼 재현하기 위한 W3 보강 트랙. 실전과 같은 strategy/risk 시나리오를 사용하되 live 주문 Kafka, `OrderRouterKafkaListener`, `KisOrderHttpAdapter`, live 주문·리스크 테이블, Slack TRADE 알림과 분리한다. |
| 2026-06-02 (ADR 0043·0067·0098 amend) | **KillSwitch·`BUY_BLOCKED` 제어 계층 정합** — §2 T-RISK row를 갱신해 KillSwitch를 `user_id` 전체 하드 스톱, `BUY_BLOCKED`를 `strategy_id` 단위 신규 BUY 차단으로 분리했다. KillSwitch는 BUY·SELL 모두를 차단하지만 strategy.status를 바꾸지 않고, `KILL_SWITCH_ON` BLOCKED는 strategy AutoPause 누적에서 제외한다. |
| 2026-06-02 (ADR 0067 amend) | **T-RISK 리스크 자동정지 `BUY_BLOCKED` 정책 정합** — §2 T-RISK row를 갱신해 auto-PAUSE 반복 BLOCKED 10회가 strategy 전체 PAUSED/DISABLED가 아니라 `strategy_id` 단위 `BUY_BLOCKED` 신규 BUY 차단으로 작동하도록 명시. 다른 strategy는 각자의 상태대로 계속 동작하며 기존 포지션 SELL·익절·손절 평가는 유지한다. |
| 2026-05-25 (ADR 0104) | **T-MARKET 보강 — 시장 캘린더 이벤트 표면 + KRX 휴장일 배치 게이트 Accepted**. §1 W4 범위에 T-MARKET 추가, §2 트랙 목록표 23→24 트랙 갱신, §3 T-MARKET outline 신설. 2026-05-25 부처님오신날 대체휴무일 실측 기반으로 `market_calendar` KRX SoT와 batch skip, `/market/calendar` 일정 CRUD, `dataInsufficient=true` 원인(휴장일이 아니라 지수 초기 데이터 0건)을 분리 기록. |
| 2026-05-18 (ADR 0081) | **ADR 0081 T-ACCT 신규 트랙 spec/plan 발행 정합 (v3.6.14 patch — Proposed · 사용자 결함 진단 위임)** — §2 트랙 목록표 21→22 트랙 격상 + T-ACCT Proposed row 신규 add(★ W3 보강 v3.6.14 patch + ADR 0081 1:1 매핑 + 마스터 §10 row 0081 정합 + 3 endpoint + 신규 port KisBalanceQueryPort + 신규 adapter ApiKisBalanceQueryAdapter + KIS `inquire-balance` output2 11 필드 LOCK + Redis cache TTL 30s + DTO 3건 + frontend Dashboard 격상 + KisCapitalSnapshotProviderAdapter 실제 어댑터 교체 + KIS endpoint 호출 0 추가 + AI 호출 0 LOCK + mock 합성 0 LOCK + KIS-first raw 4건 inline KDoc 의무). §3 T-ACCT outline 신설 — 마스터 §10 row 0081 정합 + 목표(자동매매 fitness gap 해소) + 선행/후행(W3 마감 후 + T-RISK §7-1 결합 + W4 T-AI 후보) + 오프라인 동작(KIS REST 의존 + Redis 30s fallback) + KIS 흡수(`inquire-balance` TR_ID `TTTC8434R`/`VTTC8434R` output1+output2) + ADR 0081 Proposed + ADR 0042 baseline reuse(consumer KisBalanceAdapter 0 touch 관심사 분리) + ADR 0067 §7-1 cross-ref 1줄 add-only + ADR 0070 baseline reuse(T-RECON 0 touch) + ADR 0037 정합(KIS endpoint 호출 0 추가) + 데이터(신규 영속 0건 Flyway 변경 0 + Redis cache 만 + output2 11 필드 LOCK) + 산출물 ~10 파일 매트릭스(domain 신규 패키지 account/ 2 파일 + api 4 파일 + consumer 1 파일 + frontend 5+ 파일 + 옵션 Phase 5) + dispatch plan(Phase 1·2 backend-core 단일 PR · Phase 3 backend-core consumer/risk · Phase 4 frontend · Phase 5 옵션) + release(v3.6.14 patch · feedback_release_policy_patch_first 정합) + 비범위(`inquire-account-balance` CTRP6548R 보조 · Account history 시계열 · AI 잔고 분석 W4 · AppHeader 배지 옵션) + dogfooding evidence(사용자 최고 관리자 2026-05-18 14:50 KST "지금 잔액조회 예수금 조회 이런게 아예 없는데?" + orchestrator + architect 진단 매트릭스 5건 + 사용자 결정 "신규 트랙으로 분리"). 사용자 결함 진단 root cause 5건: (A) ADR 0042 Bootstrap용 `KisBalanceAdapter`는 output1 만 파싱(output2 11 필드 누락) (B) `KisReconcileBalanceAdapter`는 T-RECON 내부 cron 용도 holdings only (C) `api/controller/PositionController`는 DB position snapshot 조회만(실시간 KIS 0) (D) frontend Dashboard·AppHeader 잔고 UI 0건(`DashboardPlaceholder.tsx` placeholder만) (E) `CapitalSnapshotProviderAdapter` Phase 2 미합류(`getCapital→0` → POSITION_LIMIT 룰 무력). 신규 결정: spec `_workspace/specs/2026-05-18-account-balance-positions-spec.md` + ADR 0081 Proposed. 모듈 경계 정합(ADR 0004): domain Spring 의존 0 / api consumer 의존 0 / consumer api 의존 0 / Flyway 변경 0(Redis cache only). T-RISK §7-1 결합: `@ConditionalOnMissingBean` 정합 — 본 어댑터 등록 시 default no-op 자동 supersede + revert 시 자동 fallback(graceful). 트랙 신설 1건(T-ACCT Proposed → Phase 1·2 backend 머지 후 Accepted 격상 예정). v3.6.14 patch 사이클(W3 보강 트랙 · 사용자 메모리 release_policy_patch_first 정합 — v3.7=W4 LOCK + v3.8=W5 LOCK → patch only). 사용자 메모리 정합 9건(release_policy_patch_first + v3_skepticism_no_regression + kis_key_realonly_for_data + kis_first_policy + no_mock_data + orchestrator_active_parallel + project_system_vision + spec_plan_scope + 3tier_hierarchy). 사용자 자율 위임 정합("신규 트랙으로 분리 — architect spec/plan 발행" 명시 2026-05-18). 코드 변경 0 LOCK(spec 사이클). (architect, opus 4.7 1M) |
| 2026-05-15 (ADR 0075) | **ADR 0075 T-NEWS 트랙 진입 spec/plan 발행 정합 (v3.6.7 patch — 사용자 결정 8 의제 채택 · ADR 0073 supersede)** — §2 T-NEWS row Accepted 격상 (Proposed → Accepted ★ 마킹 + W3.5 v3.6.7 patch + ADR 0073 supersede + 0075 Accepted 정합 · 목표 한 줄 정밀화 — DART 1분 강화 + Flyway V32 disclosure + Flyway V33 news_article + Kafka 2 토픽 disclosure.new.v1·news.collected.v1 retention 7d + Redis dedup/rate limit + REST 2 endpoint + STOMP 0 LOCK + sidebar polling 60s + Watchlist 우선 + .env NAVER_OPEN_API_* + KillSwitch auto-ON X 의제 4-2 Slack 알림만 + 감성 분석 T-AI 흡수 backlog §62 + KIS 호출 0 + AI 호출 0 + mock 합성 0 + DART list.json + Naver search/news.json raw fetch 인용). §3 T-NEWS outline 신규 발행 (마스터 §A.4·§B-3·§C.13 정합 + 외부 데이터 raw fetch DART + Naver + ADR 0075·0073·0010·0018·0044·0057·0069 §F + 데이터 매트릭스 DB Flyway V32 disclosure + V33 news_article + Redis dedup + rate limit + Kafka 2 토픽 + .env NAVER_OPEN_API_* + 산출물 Phase 1 market-data ~7 파일 + Phase 2 backend-core ~10 파일 + Phase 3 frontend ~8 파일 = 총 ~25 파일 매트릭스 + 비범위 8건 — Phase 4 RSS·감성·풀 텍스트·영어·`/news` 페이지·STOMP·NaverApiSection·KillSwitch auto-ON). 사용자 결정 8 의제 LOCK 매트릭스(1=A 옵션·2-1 1분·3-1 Phase 2 .env·4-2 Slack 알림만·5-1 T-AI 흡수·6-1 sidebar·7-1 2 토픽·8 v3.6.7 patch). Phase 분리 LOCK 3 PR 직렬(market-data Phase 1 DART + Flyway V32 + Kafka publish + REST /disclosures → backend-core Phase 2 Naver + Flyway V33 + Kafka publish + REST /news + Watchlist 우선 cron + .env → frontend Phase 3 NewsPanel + DisclosurePanel + sidebar mount + 60s polling). Flyway V32/V33 분리 LOCK (§8-3 — disclosure Phase 1 단독 + news_article Phase 2 단독, revert 단위 분리). 멱등성 UQ 2건(ux_disclosure_rcept_no + ux_news_article_source_stock_url_hash IF NOT EXISTS). Redis ZSET 분산 sliding window rate limit (DART 60/분 + Naver 17/분 hard limit). 신규 ADR 0075 발행 (ADR 0073 supersede analysis only → 결정 채택 + 정밀 spec 위임). 트랙 신설 1건 (T-NEWS Accepted 격상). v3.6.7 patch 사이클 (W3.5 트랙 · 사용자 메모리 release_policy_patch_first 정합 — v3.7.0=W4 LOCK + v3.8.0=W5 LOCK → minor 인플레이션 회피 + patch 진입). 사용자 메모리 정합 6건(release_policy_patch_first + v3_skepticism_no_regression + 3tier_hierarchy + kis_first_policy + no_mock_data + secret_handling). 사용자 자율 위임 정합 ("ADR 0073 T-NEWS 트랙 진입" + "T-NEWS 트랙 진입 진행해" 2026-05-15). 직전 architect dispatch 529 Overloaded fail 후 재dispatch. (architect, opus 4.7 1M) |
| 2026-05-14 (ADR 0074 재발행) | **ADR 0074 SettingsModal 풀세트 spec/plan 발행 정합 (v3.6.5 patch — 재발행)** — §2 T-ONBOARD row 갱신 (ADR `0046 → 0046 + 0069 + 0074` 정합 + 목표 한 줄 정밀화 — "운영 변경 SettingsModal 풀세트 — KIS 키 변경 + KillSwitch 수동 토글 + AI 키 카테고리 사전 정합 · backlog §44+§45 묶음 흡수 · v3.6.5 patch 재발행"). §3 T-ONBOARD outline 갱신 (ADR 0074 prepend + 산출물 매트릭스 8건 신규 prepend — SystemConfigController·OnboardingController 3 메서드·KisCacheInvalidator·KisKeyChangedProducer·KisKeyChangedListener·KisWebSocketBridge reconnectGracefully·KillSwitchSection·5 hook) + 비범위 갱신 (OpenAI/Anthropic 키 실구현 T-AI 분리 + Naver 뉴스 키 T-NEWS 분리 + account_prdt_cd backlog §45 §H + `/settings` route backlog §45 §J + KIS audit log backlog §M). **재발행 사유**: 직전 architect dispatch 시 ADR 번호 충돌 — T-NEWS(PR #414 머지, ADR 0073 점유)와 본 사이클(PR #415 close) 둘 다 ADR 0073으로 발행되어 PR #415 close. 본 ADR은 0074로 재발행 + 결정 내용 변동 0(번호만 갱신). 12 § LOCK + Phase 분리 LOCK(Phase 1 backend-core + Phase 3 consumer add-only 병렬 가능 + Phase 2 frontend 직렬). REST 신규 5건 + Kafka 신규 1(system.kis-key-changed.v1 retention 7d) + killswitch.engaged.v1 schema 확장(engaged:boolean). Flyway 0건 LOCK(baseline reuse). AI 호출 0 grep 검증 의무. mock 합성 0(KillSwitch 초기 row 없을 때 "최초 토글 없음" + KIS 키 hasKey=false 시 "등록된 키 없음"). ADR 0069 §7 backlog 흡수(§A KIS MOCK DELETE + §D 키 변경 직후 주문 발행 잠금 + §G KillSwitchSection + §E AI 키 사전 정합 — §F·§H·§I·§J 잔류). 트랙 신설 0건 (기존 T-ONBOARD outline 정밀 명세 보강만). 사용자 자율 위임 정합 ("§44~§45부터 하자" 2026-05-14). (architect, opus 4.7 1M) |
| 2026-05-14 (ADR 0073) | **ADR 0073 T-NEWS 뉴스·공시 실시간 인입 트랙 feasibility 분석 발행 정합 (v3.6.5 analysis only — 사용자 의제 응답 "주요 뉴스는 시작해?")** — §2 트랙 목록표 21 트랙으로 격상 (T-NEWS Proposed row 신규 add — W3.5 또는 W4 prereq · ADR 0073 1:1 매핑 · 마스터 §A.4·§B-3·§C.13 정합 · DART 5분 → 1분 빈도 강화 + Naver Open API 어댑터 + Flyway V31 news·news_disclosure + Kafka 신규 2 + frontend NewsPanel·DisclosurePanel + KIS endpoint 호출 0 + AI 호출 0). §8 변경 이력 본 row prepend. 트랙 신설 status = Proposed (analysis only · 코드 변경 0 LOCK). 옵션 A·B·C·D 매트릭스 + 사용자 결정 의제 8건 ADR §6 위임. architect 권장 A-1+2-1+3-1+4-2+5-1+6-1+7-1+8-1. baseline 발견 매트릭스 6건(ADR 0010 §5 news·news_sentiment 테이블 명시 + ADR 0026 §38 prompt 컨텍스트 뉴스 명시 + ADR 0069 §F NaverApiSection 후보 + master §A.4 News & Disclosure 영역 + master §B-3 NAVER 옵션 + master §C.13 News 19 컴포넌트) → v1/v2 잔재 0 검증. KIS-first §B-3 정합 OK(시세만 KIS LOCK + 뉴스는 보조 OK). 자체 추론 risk 1건(Naver Open API 한도 25K/일 raw fetch 미수행) — 채택 시 ADR 0074 풀 spec raw fetch 의무 LOCK. backlog §52 등재(사용자 결정 후 별도 사이클 ADR 0074 신규 발행 + sub-agent 디스패치 market-data + backend-core × 2 + frontend 4 PR 직렬). (architect, opus 4.7 1M) |
| 2026-05-14 (ADR 0072) | **ADR 0072 T-J + T-PERF 묶음 spec/plan 발행 정합 (W3 마지막 사이클 — v3.6.4 patch)** — §2 T-J·T-PERF row 격상 (id ★ 마킹 + 목표 한 줄 정밀화 + 선행/후행 + Wave/사이즈/담당). §3 T-J outline 신설 (마스터 §D.16 ADR 0072 정합 + Flyway V29 trading_journal+journal_note + 멱등성 UQ + JournalEventConsumer 5 Kafka listen + JournalSummaryComposer 한국어 + REST 3 endpoint Swagger 4단 + KIS endpoint 호출 0 LOCK + AI 호출 0 + mock 합성 0). §3 T-PERF outline 풀 보강 (마스터 §C.14 ADR 0072 정합 + 5 지표 fixed + Flyway V30 performance_summary+pnl_daily + batch DailyPerformanceJob 매일 00:30 KST cron + inline aggregate fallback + Redis cache TTL 1h + PerformanceCalculator pure math MDD 알고리즘 NUMERIC(8,4) + REST 2 endpoint Swagger 4단 + Sharpe·Sortino·CAGR backlog §61). Phase 분리 LOCK 3 PR 직렬(backend-core T-J Phase 1: domain+Flyway V29+JournalEventConsumer 5 Kafka listen+REST 3 + 단위 테스트 ~15건 → backend-core T-PERF Phase 2: domain+Flyway V30+batch DailyPerformanceJob+REST 2+Redis cache+PerformanceCalculator + 단위 테스트 ~22건 → frontend Phase 3: `/journal`+`/performance` 2 페이지+AppHeader 메뉴 2 추가+LWC v4 equity curve+daily P&L bar chart + 단위 테스트 ~8건). 비범위 9건 backlog §59~§67 명시(STOMP push·chart_snapshot 영속 강화·Sharpe·Sortino·CAGR 풀세트·W4 AI 회고 코멘트·soft delete·종목별 view·전략별 view·summary 시계열 mini-chart·RFR 정책). 트랙 신설 0건 (기존 T-J·T-PERF 정밀 명세 보강만). v3.6.4 patch 사이클 (W3 마지막 사이클). 사용자 자율 최종권한 위임 정합 (2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율"). (architect, opus 4.7 1M) |
| 2026-05-14 (ADR 0071) | **ADR 0071 T-SC Screener spec/plan 발행 정합** — §2 T-SC row 갱신 (id ★ 마킹 + 목표 한 줄 정밀화 + 선행/후행 + Wave/사이즈/담당). §3 T-SC outline 풀 보강 (마스터 §C.10 ADR 0071 정합 + Redis cache 30m + 옵션 영속 Flyway V28(env toggle) + multi-criteria 4 기준 fixed weights + skip 매트릭스 + Strategy Builder 합류 + KIS endpoint 호출 0 LOCK + AI 호출 0 + mock 합성 0). Phase 분리 LOCK 3 PR 직렬(strategy-engine Phase 1 domain+consumer 단위 테스트 ~12건 + backend-core Phase 2 api+Redis+옵션 Flyway V28 + DTO 6건 + REST 3 endpoint Swagger 4단 + 단위 테스트 ~12건 + frontend Phase 3 Strategy Builder 합류 + 모달 + 단위 테스트 ~7건). 비범위 7건 backlog §52~§58 명시(비동기 모드·단독 페이지·사용자 custom 랭킹·DailyScreenerJob cron·AI 해석·Watchlist bulk endpoint·screening_match retention). 트랙 신설 0건 (기존 T-SC 정밀 명세 보강만). v3.6.3 patch 사이클 (W3 다섯 번째 트랙). 사용자 자율 최종권한 위임 정합 (2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율"). (architect, opus 4.7 1M) |
| 2026-05-13 (ADR 0070) | **ADR 0070 T-RECON + T-NOTIFY 묶음 spec/plan 발행 정합** — §3 T-RECON outline 갱신 (ADR 0041 → 0041 + 0070 cross-ref · Flyway V22 → V25·V26 시프트 · 산출물 매트릭스 Phase 1 strategy-engine + Phase 2 backend-core 분리 LOCK · KIS-first raw 4건 명시 TTTC8434R·VTTC8434R + CTRP6548R + H0STCNI0 cross-ref). §3 T-NOTIFY outline 갱신 (ADR 0044 → 0044 + 0070 cross-ref · Flyway V24 → V27 시프트 + system_config.notification_slack_webhook BYTEA ALTER 통합 · 산출물 매트릭스 Phase 1 backend-core 단독 · domain 5 port·consumer 5 component·persistence 2 + REST 0 LOCK · frontend 0 LOCK · 단위 테스트 ~18건). 트랙 신설 0건 (기존 T-RECON·T-NOTIFY 정밀 명세 보강만). v3.6.2 patch 사이클 (W3 마무리 prereq). 사용자 자율 최종권한 위임 정합 (2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율"). (architect, opus 4.7 1M) |
| 2026-05-13 (ADR 0069) | **SettingsModal vs SetupWizard 책임 분리 ADR 0069 발행 정합** — §3 T-ONBOARD outline ADR 갱신 (0046 → 0046 + 0069 + 0068 §16-4 cross-ref 추가) + 산출물 매트릭스 보강 (SetupWizard.tsx 책임 분리 명시 + SettingsModal.tsx 1차 scope 3 section 명시 + forms/ 6 공통 컴포넌트 prepend — KisCredentialForm·SecretKeyInput·SettingsSection·ConfirmDangerToggle·SystemConfigForm) + 비범위 보강 (ADR 0069 backlog §A·§C·§E·§F 후속 명시 — KIS MOCK DELETE·마스터 키 회전·OpenAI/Naver 키). dogfooding 결함 응답 (RealTradingConsentToggle UI 접근 가능 path 확보 → T-O 자본 보호 검증 케이스 1·2·3 unblock). 트랙 신설 0건, T-ONBOARD outline 보강만. (architect, opus 4.7 1M) |
| 2026-05-03 (v1) | 13 트랙 + 5 Wave (architect, opus 4.7 1M) — "AI Decision Layer T-RISK" 가정. 사용자 비전 정정으로 폐기. |
| 2026-05-03 (v2) | **풀 재작성** — 14 트랙 + 5 Wave. AI 트랙(W4 T-AI·T-MCP) 분리. T-LWC LWC 확장(TradingView Advanced 폐기). T-LEARN 사용자 학습 신설. (architect, opus 4.7 1M) |
| 2026-05-07 (PR-2) | §3 outline ADR 매핑 정합 — 옛 0032·0033·0034·0035·0036·0037·0038·0039·0040·0041·0042·0044·0045·0046 → 현행 0013·0014·0015·0016·0017·0018·0020·0021·0022·0023·0024·0025·0026·0027 재매핑. T-PAT/T-BT/T-SC/T-PERF 후속 후보 0048~0051 → 0041~0044 (README §12 정합). (architect) |
| 2026-05-07 (PR-3a) | 자동매매 fitness 갭 ADR 0041·0042·0043 발행 정합 — §2 T-W1·T-O ADR cross-ref + 신규 트랙 **T-RECON (W3)** 신설, §3 T-RECON outline 추가, §3 T-RISK ADR 0043 추가, §5 W3 진행 순서에 T-RECON 합류, T-PAT/T-BT/T-SC/T-PERF 후속 후보 0044~0047 시프트 (3 슬롯). (architect) |
| 2026-05-07 (PR-3b) | 운영·UX fitness 갭 ADR 0044·0045·0046 발행 정합 — §1 W1·W3 트랙 ID 갱신 (**T-ONBOARD**·**T-NOTIFY** 신설), §2 트랙 목록표 18→20 트랙 (T-ONBOARD W1 ★★ 최우선·T-NOTIFY W3), §2 T-RISK·T-O cross-ref ADR 0044/0045 추가, §3 T-ONBOARD·T-NOTIFY outline 신규, §5 W1 진행 순서에 T-ONBOARD prepend (~4주)·W3에 T-NOTIFY 합류 (~6주), 총 추정 21주로 시프트, T-PAT/T-BT/T-SC/T-PERF 후속 후보 0047~0050 시프트 (3 슬롯). (architect) |
| 2026-05-07 (PR-3c) | DSL 안전성 갭 ADR 0047(DSL Evaluation Sandbox) 발행 정합 — §2 T-DSL·T-BT·T-SIG cross-ref ADR 0047 추가 (sandbox 명세 — static 검증·strategy-eval-pool 격리·timeout 100ms/1000ms·auto-disable·complexity 응답), §3 T-DSL outline ADR 0047 추가 + 산출물 보강(StrategyComplexity·DslDepthExceededException·키워드 화이트리스트 검증·V26 컬럼 3건 V27 strategy_eval_metric)·§3 T-BT outline ADR 0047 백테스트 timeout/abort 추가·§3 T-SIG outline ADR 0047 strategy-eval-pool config 추가, T-PAT/T-SC/T-PERF 후속 후보 0048/0050/0051 시프트 (1 슬롯). (architect) |
| 2026-05-12 (PR-50) | **W3 T-BT R2/R3 잔여 plan** — §2 T-BT row 갱신 (R1 stateless 머지 + R2 영속 + R3 frontend 잔여 격상). backlog §37·§38·§39 신규 등록 의제 발행. 사용자 결정 의제 6건 (A 영속 시점·B 영속 매트릭스·C REST endpoint·D frontend·E 모델 확장·F 디스패치). architect 권장 A-1+B-1+C-1+D-1+E-1+F-1. 채택 시 ADR 0061 신규 발행 + ADR 0056 §3-4 supersede §1. (architect, opus 4.7 1M) |
| 2026-05-12 (T-RISK plan) | **W3 T-RISK plan 발행 — ADR 0067 신규 (Proposed)** — §2 T-RISK row 격상 (★ 세 번째 트랙 + ADR 0067 1:1 매핑 인용 + 6 룰·Kafka consumer·4 도메인 영속·auto-PAUSE 매트릭스 추가). §5 W3 진행 순서 본 ADR 합류 의무(T-SIG → **T-RISK** → T-O). backlog §41 신규 등록(결정 의제 8건 사용자 위임). 사용자 결정 의제 8건(A 포지션 한도·B 일일 손실·C 동시 종목·D 거래 빈도·E 게이트 위치·F 위반 처리·G 영속 매트릭스·H 설정 source). architect 권장 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1. 채택 시 strategy-engine(Phase 1) → backend-core(Phase 2) → frontend(Phase 3) 직렬 디스패치 + Flyway V16·V17·V18·V19. (architect, opus 4.7 1M) |
