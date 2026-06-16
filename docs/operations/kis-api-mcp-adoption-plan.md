# KIS API / MCP 채용 우선순위 카탈로그

> 작성일: 2026-06-12
> 기준: v3.13.0 main 코드 inspection + KIS 공식 `open-trading-api/examples_llm/domestic_stock` raw 예제 확인
> 원칙: 현재 사용 중인 KIS API와 MCP tool을 최상단에 두고, 새 채용 후보는 magicJar 운영 가치 순으로 정렬한다.
> HTML 비교판: [`kis-api-mcp-flow-comparison.html`](kis-api-mcp-flow-comparison.html)

## 0. 현재 사용 중인 KIS API

| 우선 | 구분 | API / TR_ID | 현재 사용처 | 상태 |
|---|---|---|---|---|
| A0 | 주문 | `order-cash` `TTTC0012U`/`VTTC0012U`/`TTTC0011U`/`VTTC0011U` | `KisOrderHttpAdapter` | 실 주문 주경로 |
| A0 | 취소 | `order-rvsecncl` `TTTC0013U`/`VTTC0013U` | `KisOrderHttpAdapter.cancel` | 수동/자동 취소 |
| A0 | 체결 backfill | `inquire-daily-ccld` `TTTC0081R`/`VTTC0081R` | `KisOrderInquireCcldHttpAdapter` | WS 유실 보정 |
| A0 | 잔고 | `inquire-balance` `TTTC8434R`/`VTTC8434R` | `KisBalanceAdapter`, `KisReconcileBalanceAdapter` | position 권위 |
| A0 | 현재가 | `inquire-price` `FHKST01010100` | 현재가/일봉 seed/지표 | 사용 중 |
| A0 | 호가 REST | `inquire-asking-price-exp-ccn` `FHKST01010200` | 진입 호가 fallback | 사용 중 |
| A0 | 일/주/月/년봉 | `inquire-daily-itemchartprice` `FHKST03010100` | candle backfill | 사용 중 |
| A0 | 당일/과거 분봉 | `FHKST03010200`, `FHKST03010230` | 1m backfill | 사용 중 |
| A0 | 멀티 현재가 | `intstock-multprice` `FHKST11300006` | nightly snapshot/NXT 검증 | 사용 중 |
| A0 | 휴장일 | `chk-holiday` `CTCA0903R` | 캘린더/수집 gate | 사용 중 |
| A0 | 예탁원 정보 | `HHKDB669100C0`/`101C0`/`102C0`/`104C0`/`105C0`/`106C0` | corp action batch | 사용 중 |
| A0 | WS 체결가 | `H0UNCNT0`, `H0STOUP0` | 실시간 tick | 사용 중 |
| A0 | WS 호가 | `H0UNASP0` | 상세/진입 통합 호가 + 예상체결 필드 | 사용 중 (`H0STASP0` 퇴역) |
| A0 | WS 장운영/VI | `H0UNMKO0` | 보유 포지션 VI 관측 + 정책별 알림/시장가 청산 | 사용 중 |
| A0 | WS 체결통보 | `H0STCNI0`, `H0STCNI9` | order execution | 사용 중 |

## 1. 현재 사용 중인 MCP tool

| 우선 | Tool | 현재 입력 | 현재 출력 | 변경 안정성 |
|---|---|---|---|---|
| A0 | `get_candles` | `code`, `interval`, `from`, `to`, `limit` | OHLCV 배열 | 유지 |
| A0 | `backtest_strategy` | `strategyId`, `universe`, `interval`, `from`, `to`, `initialCapital` | 성과 카드 + trades | 유지 |
| A0 | `run_screener` | `strategyId`, `universe`, option | 후보 ranking | 유지 |
| A0 | `get_journal` | 기간/종목/전략 필터 | 매매일지 | 유지 |
| A0 | `get_market_trend` | periods/preferences | 지수·섹터 추세 | 유지 |
| A0 | `explain_indicator` | indicator id | 정적 설명 | 유지 |

## 2. 다음 채용 우선순위

| 순위 | 후보 | 공식 예제 / TR_ID | 채용 이유 | 기본값 |
|---|---|---|---|---|
| P0 | 계좌 availability snapshot | 기존 `inquire-balance` `TTTC8434R`/`VTTC8434R` output1 `ord_psbl_qty` + output2 cash | 주문 path KIS 호출 0을 유지하면서 예수금/매도가능수량 stale 위험을 낮춘다. | refresh/guard flag OFF |
| P1 | 투자자/체결 REST 보강 | `inquire-investor` `FHKST01010900`, `inquire-ccnl` `FHKST01010300` | 종목 상세/스크리너/WS stale 보강에 쓰는 read-only 정보다. | read-only |
| P1 | 계좌 실현손익 reconcile | `inquire-balance-rlz-pl` `TTTC8494R` | 내부 저널 수익률과 KIS 계좌 손익을 대조한다. | REAL read-only |
| A0 | 통합 체결가 WS | `ccnl_total` `H0UNCNT0` | KRX+NXT 2슬롯을 1슬롯으로 줄인다. | 사용 중 |
| A0 | 통합 호가 WS | `asking_price_total` `H0UNASP0` | DETAIL/STRATEGY 호가를 KRX+NXT 통합 1채널로 공급한다. | 사용 중 |
| A0 | 통합 장운영/VI WS | `market_status_total` `H0UNMKO0` | VI 발동을 주문 정책과 분리해 ALERT_ONLY/EXIT_MARKET 보유분만 감시한다. | 사용 중 |
| P2 | 주문조건별 가능수량 정밀조회 | `inquire-psbl-order` `TTTC8908R`/`VTTC8908R`, `inquire-psbl-sell` `TTTC8408R` | 계좌 snapshot으로 부족한 예외 분석/수동 진단용. 주문 hot path 기본 사용 금지. | 보류 |
| P2 | 재무비율 | `FHKST66430300` 등 | 스크리너/fundamental 설명 강화. | batch only |
| P2 | 추정실적/투자의견 | `HHKST668300C0`, `FHKST663300C0`, `FHKST663400C0` | AI 설명/종목 상세 보조. | batch/cache |
| P3 | ETF/ETN NAV/구성 | `FHPST02400000` 등 | ETF 전략 활성화 시 가치. | 보류 |

## 3. MCP 확장 시각화

현재 MCP는 백테스트/스크리너/캔들처럼 "분석 실행" 중심이다. 다음 확장은 주문 실행이 아니라 KIS 운영 상태와 계좌 정합을 읽는 read-only 계층이다.

```mermaid
flowchart LR
    Client["Claude / Cursor / local client"] --> Mcp["magicJar MCP Controller"]
    Mcp --> Tools["현재 6 tool"]
    Tools --> Candles["get_candles"]
    Tools --> Backtest["backtest_strategy"]
    Tools --> Screener["run_screener"]
    Tools --> Journal["get_journal"]
    Tools --> Trend["get_market_trend"]
    Tools --> Indicator["explain_indicator"]

    Mcp -. "후보, read-only" .-> Ops["운영/정합 tool 후보"]
    Ops --> Runtime["get_kis_runtime_status"]
    Ops --> Catalog["get_kis_endpoint_catalog"]
    Ops --> Reconcile["get_account_reconcile_snapshot"]
    Ops --> Micro["get_symbol_microstructure"]
```

| Tool 후보 | 현재 대체 흐름 | 바뀌는 점 | 다운그레이드 방지 |
|---|---|---|---|
| `get_kis_runtime_status` | 운영자가 `/admin/realtime`, 로그, Prometheus를 따로 확인 | MCP 한 번으로 WS slot/stale/execution notice를 요약 | 기존 REST/화면 유지, read-only wrapper |
| `get_kis_endpoint_catalog` | `kis-api-reference.md`를 사람이 읽음 | 현재 사용/후보/보류 API를 tool로 조회 | 문서가 SoT, tool은 문서 snapshot만 반환 |
| `get_account_reconcile_snapshot` | 계좌/position/reconcile 화면을 따로 봄 | KIS 잔고, 내부 position, 최근 reconcile 상태를 한 번에 비교 | 주문 발행 없음, KillSwitch 변경 없음 |
| `get_symbol_microstructure` | 캔들, 호가, 체결, 투자자를 따로 봄 | 종목 상세용 미시구조 요약 | 기존 `get_candles` 유지, 추가 정보만 합류 |

## 4. 네 API의 현재 -> 변경 흐름

### 4-1. 주문 availability snapshot

#### 현재 주문 경로

```mermaid
flowchart TD
    S["SignalGeneratedKafkaListener"] --> Ctx["KIS balance fresh 조회<br/>currentCapital 산정"]
    Ctx --> Risk["RiskConfig / position limit / daily loss"]
    Risk --> Intent["order.intent.v1 발행"]
    Intent --> Router["OrderRouterKafkaListener"]
    Router --> Gate["TradingModeGate / KillSwitch / MarketHours / Canary"]
    Gate --> Persist["order_request REQUESTED 저장"]
    Persist --> Submit["KIS order-cash<br/>TTTC0012U/0011U"]
    Submit --> Ack["ACK"]
    Submit --> Reject["KIS reject"]
    Ack --> Ws["H0STCNI0/H0STCNI9 체결통보"]
    Ws --> Fill["order_execution 저장 + position update"]
    Fill --> BalanceEvt["account.balance.changed.v1"]
    Reject --> BalanceEvt
```

| 현재 판단 지점 | 실제 KIS 호출 | 위험 |
|---|---|---|
| 시그널 수량 산정에서 balance fresh 조회 | `inquire-balance` 1회 | 전략 burst 시 호출이 늘 수 있다. |
| 주문 라우터 | `order-cash` 본주문만 호출 | 주문 직전 가능수량 사전 검증은 없고, KIS reject가 최종 방어선이다. |
| 체결 이후 | WS 체결통보 + backfill | 체결 이벤트가 늦으면 UI/캐시 반영도 늦다. |

#### 변경 후 refresh 경로

```mermaid
flowchart TD
    Periodic["5분 periodic<br/>flag ON일 때만"] --> Queue["refresh queue<br/>key=userId+mode"]
    BalanceEvt["account.balance.changed.v1<br/>FILLED/CANCELLED/REJECTED"] --> Queue
    Manual["운영자 수동 refresh 후보"] --> Queue

    Queue --> Debounce["debounce 2s<br/>동일 userId+mode coalesce"]
    Debounce --> Rate["KIS rate limiter<br/>BACKFILL priority"]
    Rate --> Kis["inquire-balance<br/>TTTC8434R/VTTC8434R"]
    Kis --> Parse1["output1: hldg_qty + ord_psbl_qty<br/>holding/sellable map"]
    Kis --> Parse2["output2: dnca_tot_amt / nxdy_excc_amt / prvs_rcdl_excc_amt<br/>cash snapshot"]
    Parse1 --> Snapshot["AccountAvailabilitySnapshot"]
    Parse2 --> Snapshot
    Snapshot --> Lww["Redis putIfNewer<br/>versionMillis LWW"]
    Lww --> Redis["account:availability:{mode}:{userId}<br/>TTL 15m"]

    Rate -. "429/EGW rate limit" .-> Retry["retry 2회 / 500ms"]
    Retry -. "소진" .-> NoWrite["snapshot 미갱신<br/>기존 주문 실패 처리 유지"]
```

| 현재 | 변경 | 코멘트 |
|---|---|---|
| 가능수량은 본주문 결과 또는 reconcile 후에야 명확해진다. | `inquire-balance` 1회로 cash와 종목별 `ord_psbl_qty`를 같이 snapshot한다. | 새 API matrix를 만들지 않고 기존 잔고 API를 재사용한다. |
| 체결 이벤트는 UI invalidation 중심이다. | 체결/취소/거절 이벤트가 availability refresh queue도 깨운다. | 실시간성은 이벤트로 당기고, 5분 주기는 안전망이다. |
| rate limit 실패 시 주문 path가 직접 흔들릴 수 있다. | refresh 실패는 snapshot 미갱신으로 끝나며 주문 path는 KIS 본주문 reject를 최종 진실로 둔다. | 주문 앞 KIS 호출 0. |

#### 변경 후 주문 경로

```mermaid
flowchart TD
    Intent["order.intent.v1"] --> Router["OrderRouterKafkaListener"]
    Router --> Gate["TradingModeGate / KillSwitch / MarketHours / Canary"]
    Gate --> GuardFlag{"snapshot guard ON?"}
    GuardFlag -- "OFF" --> Persist["order_request REQUESTED"]
    GuardFlag -- "ON" --> Read["Redis read<br/>account:availability:{mode}:{userId}"]
    Read --> Fresh{"fresh <= 5m?"}
    Fresh -- "miss/stale" --> Pass["pass-through<br/>KIS 호출 없음"]
    Fresh -- "fresh" --> Side{"BUY or SELL"}
    Side -- "BUY" --> Cash["cashD2 >= price*qty ?"]
    Side -- "SELL" --> Sellable["sellableQtyByCode[code] >= qty ?"]
    Cash -- "부족 + reject flag ON" --> RejectLocal["local REJECTED<br/>KIS 호출 없음"]
    Sellable -- "부족 + reject flag ON" --> RejectLocal
    Cash -- "충분/불명확" --> Persist
    Sellable -- "충분/불명확" --> Persist
    Pass --> Persist
    Persist --> Submit["KIS order-cash"]
    Submit --> Final["ACK / KIS reject<br/>최종 진실"]
```

| 주문 path 규칙 | 결정 |
|---|---|
| 주문 라우터에서 KIS availability REST 직접 호출 | 금지 |
| Redis snapshot miss/stale | pass-through |
| BUY 판단 금액 | `cashD2(prvs_rcdl_excc_amt)` vs `limitPrice/priceAtSignal * qty` |
| SELL 판단 수량 | output1 `ord_psbl_qty` 기반 `sellableQtyByCode[code]` |
| 부족 확정 시 차단 | `reject-on-insufficient=true`일 때만 |
| 최종 진실 | KIS `order-cash` ACK/reject + 체결통보/reconcile |

#### 다음 단계: local reservation

```mermaid
flowchart TD
    Fresh["fresh account availability snapshot"] --> Reserve{"reservation enabled?"}
    Reserve -- "OFF, v3.13.1" --> GuardOnly["snapshot guard only"]
    Reserve -- "ON, next" --> Atomic["Redis atomic reserve<br/>cash or sellable qty"]
    Atomic -- "reserve ok" --> Submit["KIS order-cash"]
    Atomic -- "reserve fail" --> LocalReject["local REJECTED"]
    Submit -- "ACK" --> Hold["reservation 유지<br/>체결/취소 대기"]
    Submit -- "KIS reject" --> Release["reservation release"]
    Hold -- "FILLED" --> Reconcile["snapshot refresh/reconcile"]
    Hold -- "CANCELLED/EXPIRED" --> Release
```

| 항목 | v3.13.1 | 다음 단계 |
|---|---|---|
| snapshot 부족 확정 차단 | 가능, 기본 OFF | 유지 |
| 동시 전략 간 cash/qty 선점 | 미적용 | Redis atomic reservation |
| release 조건 | 해당 없음 | KIS reject/cancel/expire, fill 후 reconcile |
| 도입 조건 | 운영 snapshot 정확도 확인 | 체결 WS/REST backfill 안정 확인 후 |

#### rate-limit 예산

```mermaid
flowchart LR
    UserPath["USER_PATH<br/>order-cash/hashkey/주문 직결"] --> Reserve["최우선"]
    Refresh["availability refresh"] --> Backfill["BACKFILL priority"]
    Backfill --> Debounce["userId+mode debounce 2s"]
    Debounce --> Periodic["5분 주기"]
    Debounce --> Event["체결/취소/거절 이벤트"]
    Backfill -. "rate limit" .-> Retry["500ms x 2"]
    Retry -. "실패" .-> Skip["snapshot 미갱신"]
```

| 보호 장치 | 이유 |
|---|---|
| refresh 기본 OFF | 배포 직후 KIS 호출 증가 0 |
| 5분 periodic | 평시 계좌 그림 보정, 1초 8회 한도와 충돌 최소화 |
| event debounce | 체결 WS burst가 KIS burst로 번지지 않게 함 |
| BACKFILL priority | 본주문 USER_PATH와 경쟁하지 않게 함 |
| LWW 저장 | 늦게 끝난 과거 refresh가 최신 snapshot을 덮지 못하게 함 |

### 4-2. 투자자/체결 REST 보강

```mermaid
flowchart TD
    Detail["현재: Symbol Detail"] --> Candle["candles/current price/orderbook"]
    Detail --> Ws["WS tick/orderbook"]

    Detail2["변경: Symbol Detail/MCP"] --> Candle2["기존 candles/current price/orderbook"]
    Detail2 --> Investor["inquire-investor FHKST01010900"]
    Detail2 --> Ccnl["inquire-ccnl FHKST01010300"]
```

| 현재 | 변경 | 코멘트 |
|---|---|---|
| 상세/스크리너는 가격·캔들 중심이고 체결 리스트/투자자 수급은 비어 있다. | 외국인·기관 흐름과 최근 체결을 REST로 보강한다. | WS가 비어도 read-only 정보라 자본 path 영향이 작다. |

### 4-3. 계좌 손익 reconcile

```mermaid
flowchart TD
    A["현재: journal/performance"] --> B["내부 order_execution/trading_journal"]
    A --> C["KIS balance TTTC8434R"]

    A2["변경: journal/performance"] --> B2["내부 order_execution/trading_journal"]
    A2 --> C2["KIS balance TTTC8434R"]
    A2 --> Pnl["KIS realized P/L TTTC8494R<br/>REAL read-only"]
    Pnl --> Diff["내부 손익 vs KIS 손익 diff"]
```

| 현재 | 변경 | 코멘트 |
|---|---|---|
| KIS 잔고와 내부 position 수량/평단을 reconcile한다. | KIS 실현손익을 추가로 읽어 내부 저널 손익과 대조한다. | 수량 정합 다음 단계의 돈 정합이며, REAL read-only로 시작한다. |

### 4-4. `H0UNCNT0` 통합 체결가 (채택 완료 — ADR 0124)

```mermaid
flowchart TD
    Sub["종목 체결가 구독"] --> UN["H0UNCNT0 통합<br/>KRX+NXT 단일 채널"]
    UN --> Slot1["종목당 1 slot"]
    UN --> Sess["NEW_MKOP_CLS_CODE<br/>세션 구분"]
```

| 항목 | 현행 | 코멘트 |
|---|---|---|
| 정규+NXT 체결가 구독. | `H0UNCNT0` 단일 채널로 KRX+NXT 체결을 통합 수신한다(종목당 1 slot·`NEW_MKOP_CLS_CODE` 세션 구분). | ADR 0124 채택 완료. 옛 분리 채널 이중 구독 경로·토글 flag는 2026-06-13 완전 제거(롤백은 CHANGELOG v3.13.2 코드 참조). |

## 5. 구현 순서

1. 문서/레퍼런스 갱신: 현재 사용 중인 API와 후보 우선순위 고정.
2. 계좌 availability snapshot: `inquire-balance` ord_psbl_qty 파싱 + Redis LWW snapshot + 주문 path read-only guard.
3. 투자자/체결 REST: read-only adapter + cache/API/MCP 후보 연결.
4. 계좌 손익: `inquire-balance-rlz-pl` realized P/L adapter + reconcile snapshot 후보.
5. `H0UNCNT0`: feature flag + ledger 반영. 운영 채택은 canary에서 통합 tick 공백/필드 정합 확인 후 별도 결정.

## 6. 원천 링크

- KIS 공식 예제 root: https://github.com/koreainvestment/open-trading-api/tree/main/examples_llm/domestic_stock
- `inquire_psbl_order`: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_psbl_order/inquire_psbl_order.py
- `inquire_psbl_sell`: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_psbl_sell/inquire_psbl_sell.py
- `inquire_investor`: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_investor/inquire_investor.py
- `inquire_ccnl`: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_ccnl/inquire_ccnl.py
- `inquire_balance_rlz_pl`: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance_rlz_pl/inquire_balance_rlz_pl.py
- `ccnl_total`: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_total/ccnl_total.py
