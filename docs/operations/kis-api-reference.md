# KIS OpenAPI 참조 — magicJar에서 사용하는 endpoint 정리

> 한국투자증권 OpenAPI(KIS) endpoint·TR_ID·모의투자 지원 여부 정리. 사용자 가이드(2026-05-04) + KIS 공식 GitHub raw 예제 + MCP 검증.
> **데이터 소스**: 사용자 직접 안내 + `mcp__kis-code-assistant__search_domestic_stock_api` MCP 검증 + `koreainvestment/open-trading-api/examples_llm/domestic_stock`.

상세 채용 우선순위와 MCP 변경 시각화는 [`kis-api-mcp-adoption-plan.md`](kis-api-mcp-adoption-plan.md)를 기준으로 한다.
현행/변경 플로우 HTML 비교판은 [`kis-api-mcp-flow-comparison.html`](kis-api-mcp-flow-comparison.html)에 둔다.

## 1. 운영 정책 (사용자 결정)

- **데이터 조회 = 항상 실전 키** (`KIS_APP_KEY`·`KIS_APP_SECRET` + `https://openapi.koreainvestment.com:9443`)
- **주문(매매) = 모의/실전 분리** — `trading_mode` (MOCK_KIS|REAL_KIS) 따라 키와 baseUrl 분기
- **IP 화이트리스트 등록 X** — token 발급(`/oauth2/tokenP`)만으로 인증
- 모의투자(VTS) 일부 endpoint **미지원** — 표 우측 컬럼 확인

## 1-1. 현재 사용 중인 endpoint 최상위 인덱스

| 우선 | 영역 | KIS API / TR_ID | magicJar 사용처 |
|---|---|---|---|
| A0 | 주문 | `order-cash` `TTTC0012U`/`VTTC0012U`/`TTTC0011U`/`VTTC0011U` | `KisOrderHttpAdapter.submit` |
| A0 | 취소 | `order-rvsecncl` `TTTC0013U`/`VTTC0013U` | `KisOrderHttpAdapter.cancel` |
| A0 | 체결 | `inquire-daily-ccld` `TTTC0081R`/`VTTC0081R` | `KisOrderInquireCcldHttpAdapter`, execution backfill |
| A0 | 잔고 | `inquire-balance` `TTTC8434R`/`VTTC8434R` | `KisBalanceAdapter`, `KisReconcileBalanceAdapter` |
| A0 | 현재가 | `inquire-price` `FHKST01010100` | 현재가, today candle seed, metrics |
| A0 | 호가 REST | `inquire-asking-price-exp-ccn` `FHKST01010200` | 진입 호가 fallback |
| A0 | 캔들 | `inquire-daily-itemchartprice` `FHKST03010100` | 일/주/월/년봉 |
| A0 | 분봉 | `inquire-time-itemchartprice` `FHKST03010200`, `inquire-time-dailychartprice` `FHKST03010230` | 당일/과거 1m backfill |
| A0 | 멀티시세 | `intstock-multprice` `FHKST11300006` | nightly snapshot, NXT 검증 |
| A0 | 휴장일 | `chk-holiday` `CTCA0903R` | 캘린더/수집 gate |
| A0 | 예탁원 | `HHKDB669100C0`/`101C0`/`102C0`/`104C0`/`105C0`/`106C0` | corp action batch |
| A0 | WS | `H0UNCNT0`, `H0UNASP0`, `H0UNMKO0`, `H0STOUP0`, `H0STCNI0`, `H0STCNI9` | integrated tick/orderbook/market-status VI/execution notice |

## 1-2. 다음 채용 후보 우선순위

| 순위 | API | TR_ID | 사용 목적 | 현재 결정 |
|---|---|---|---|---|
| P0 | `inquire-balance` availability snapshot | `TTTC8434R`/`VTTC8434R` | 예수금 + 종목별 주문가능수량 snapshot | 채용 |
| P1 | `inquire-investor` | `FHKST01010900` | 외국인/기관 수급 | 채용 |
| P1 | `inquire-ccnl` | `FHKST01010300` | 최근 체결 REST 보강 | 채용 |
| P1 | `inquire-balance-rlz-pl` | `TTTC8494R` | KIS 실현손익 reconcile | REAL read-only 채용 |
| A0 | `ccnl_total` | `H0UNCNT0` | KRX+NXT 통합 체결가 WS, slot 절감 | 사용 중 |
| A0 | `asking_price_total` | `H0UNASP0` | KRX+NXT 통합 호가 WS, 예상체결 필드 포함 | 사용 중 (`H0STASP0` 퇴역) |
| A0 | `market_status_total` | `H0UNMKO0` | 통합 장운영/VI 상태 WS, 전략별 VI 정책 관측/청산 | 사용 중 |
| P2 | `inquire-psbl-order` / `inquire-psbl-sell` | `TTTC8908R`/`VTTC8908R`, `TTTC8408R` | 주문조건별 가능수량 정밀 진단 | hot path 보류 |
| P2 | finance ratios | `FHKST66430300` 등 | screener fundamental | 후순위 |
| P2 | estimate/opinion | `HHKST668300C0`, `FHKST663300C0`, `FHKST663400C0` | AI/상세 보조 | 후순위 |

## 2. 종목정보 API ([국내주식] 종목정보)

| # | 명칭 | TR_ID | endpoint | 모의 | magicJar 사용 |
|---|---|---|---|---|---|
| 1 | 상품기본조회 | `CTPF1604R` | `/uapi/domestic-stock/v1/quotations/search-info` | ❌ 미지원 | (대안) |
| 2 | **주식기본조회** | **`CTPF1002R`** | **`/uapi/domestic-stock/v1/quotations/search-stock-info`** | ❌ 미지원 | ✅ **사용 중** (PR #126) — `KisInstrumentInfoAdapter.fetchInstrumentInfo` |
| 3 | 대차대조표 | `FHKST66430100` | `/uapi/domestic-stock/v1/finance/balance-sheet` | ❌ | (장차 — 자산·부채 표시 시) |
| 4 | 손익계산서 | `FHKST66430200` | `/uapi/domestic-stock/v1/finance/income-statement` | ❌ | (DART 대체 사용 중) |
| 5 | 재무비율 | `FHKST66430300` | `/uapi/domestic-stock/v1/finance/financial-ratio` | ❌ | (장차 — ROE·부채비율) |
| 6 | 수익성비율 | `FHKST66430400` | `/uapi/domestic-stock/v1/finance/profit-ratio` | ❌ | |
| 7 | 기타주요비율 | `FHKST66430500` | `/uapi/domestic-stock/v1/finance/other-major-ratios` | ❌ | |
| 8 | 안정성비율 | `FHKST66430600` | `/uapi/domestic-stock/v1/finance/stability-ratio` | ❌ | |
| 9 | 성장성비율 | `FHKST66430800` | `/uapi/domestic-stock/v1/finance/growth-ratio` | ❌ | |
| 10 | 신용가능종목 | `FHPST04770000` | `/uapi/domestic-stock/v1/quotations/credit-by-company` | ❌ | (신용 매매 시) |
| 11 | 배당일정 | `HHKDB669102C0` | `/uapi/domestic-stock/v1/ksdinfo/dividend` | ❌ | (장차 — 배당 알림) |
| 12~22 | 예탁원 정보 (주식매수청구·합병/분할·액면교체·자본감소·상장·공모주청약·실권주·의무예치·유상증자·무상증자·주주총회) | `HHKDB669xxxC0` | `/uapi/domestic-stock/v1/ksdinfo/...` | ❌ | (이벤트 알림 시) |
| 23 | 종목추정실적 | `HHKST668300C0` | `/uapi/domestic-stock/v1/quotations/estimate-perform` | ❌ | (애널리스트 컨센) |
| 24 | 대주가능 종목 | `CTSC2702R` | `/uapi/domestic-stock/v1/quotations/lendable-by-company` | ❌ | |
| 25 | 종목투자의견 | `FHKST663300C0` | `/uapi/domestic-stock/v1/quotations/invest-opinion` | ❌ | (애널리스트 의견) |
| 26 | 증권사별 투자의견 | `FHKST663400C0` | `/uapi/domestic-stock/v1/quotations/invest-opbysec` | ❌ | |

## 3. 기본시세 API ([국내주식] 기본시세)

| # | 명칭 | TR_ID | endpoint | 모의 | magicJar 사용 |
|---|---|---|---|---|---|
| 1 | **주식현재가 시세** | **`FHKST01010100`** | **`/uapi/domestic-stock/v1/quotations/inquire-price`** | ✅ 지원 | ✅ **사용 중** (PR #128) — `KisInstrumentInfoAdapter.fetchPriceMetrics` (PER·PBR·EPS·BPS·시총·업종한글명) |
| 2 | 주식현재가 시세2 | `FHPST01010000` | `/uapi/domestic-stock/v1/quotations/inquire-price-2` | ❌ | |
| 3 | 주식현재가 체결 | `FHKST01010300` | `/uapi/domestic-stock/v1/quotations/inquire-ccnl` | ✅ | (장차 — 실시간 체결 list) |
| 4 | 주식현재가 일자별 | `FHKST01010400` | `/uapi/domestic-stock/v1/quotations/inquire-daily-price` | ✅ | (D1 대체 가능) |
| 5 | **주식현재가 호가/예상체결** | **`FHKST01010200`** | **`/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn`** | ✅ | ✅ **사용 중** — entry quote REST fallback |
| 6 | **주식현재가 투자자** | **`FHKST01010900`** | **`/uapi/domestic-stock/v1/quotations/inquire-investor`** | ✅ | 🟢 **채용 P1** — 외국인·기관 동향 |
| 7 | 주식현재가 회원사 | `FHKST01010600` | `/uapi/domestic-stock/v1/quotations/inquire-member` | ✅ | (장차 — 매수/매도 회원사) |
| 8 | **국내주식 기간별 시세 (일/주/월/년)** | **`FHKST03010100`** | **`/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice`** | ✅ | ✅ **사용 중** — `RealKisRestAdapter.fetchCandles` (D1) |
| 9 | 주식 당일 분봉 조회 | `FHKST03010200` | `/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice` | ✅ | ⚠️ **당일 분봉 전용** (과거 backfill 불가) — Yahoo Finance 사용 (ADR 0019) |
| 10 | **주식 일별 분봉 조회 (과거)** | **`FHKST03010230`** | **`/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice`** | ❌ 미지원 | ✅ **사용 중** — 과거 1m backfill |
| 11 | **주식현재가 체결** | **`FHKST01010300`** | **`/uapi/domestic-stock/v1/quotations/inquire-ccnl`** | ✅ | 🟢 **채용 P1** — 최근 체결 REST 보강 |
| 12~13 | 시간외 일자별·시간별 체결 | `FHPST02320000`·`FHPST02310000` | `inquire-daily-overtimeprice`·`inquire-time-overtimeconclusion` | ✅ | |
| 14~16 | 시간외 현재가·호가·예상체결 | `FHPST02300000` 등 | `inquire-overtime-*` | ❌ | |
| 17 | **ETF/ETN 현재가** | `FHPST02400000` | `/uapi/etfetn/v1/quotations/inquire-price` | ❌ | (장차 — ETF 시세 분리) |
| 18~21 | ETF 구성종목·NAV | 다양 | `/uapi/etfetn/v1/quotations/...` | ❌ | |

## 4. 사용 중인 endpoint 매핑 표 (magicJar 코드 → KIS endpoint)

| magicJar 코드 | KIS endpoint | TR_ID | 비고 |
|---|---|---|---|
| `KisInstrumentInfoAdapter.fetchInstrumentInfo` | `search-stock-info` | `CTPF1002R` | 종목명·자본금·상장주식수·industry 코드 |
| `KisInstrumentInfoAdapter.fetchPriceMetrics` | `inquire-price` | `FHKST01010100` | PER·PBR·EPS·BPS·시총(억원)·업종 한글명 |
| `KisMarketIndexAdapter.fetchMarketIndex` | `inquire-index-price` | `FHPUP02100000` | KOSPI/KOSDAQ 지수 |
| `RealKisRestAdapter.fetchCandles` (D1) | `inquire-daily-itemchartprice` | `FHKST03010100` | 일봉/주봉/월봉/년봉 |
| `RealKisRestAdapter.fetchQuote` | `inquire-price` | `FHKST01010100` | 현재가·시가·고가·저가·거래량 |
| `KisRestAskingPriceAdapter` | `inquire-asking-price-exp-ccn` | `FHKST01010200` | entry quote REST fallback |
| `KisMarketMicrostructureAdapter.fetchInvestorFlow` | `inquire-investor` | `FHKST01010900` | 외국인/기관/개인 수급 read-only |
| `KisMarketMicrostructureAdapter.fetchRecentExecutions` | `inquire-ccnl` | `FHKST01010300` | 최근 체결 list read-only |
| `KisRealizedProfitAdapter.fetchRealizedProfit` | `inquire-balance-rlz-pl` | `TTTC8494R` | KIS 실현손익 REAL read-only |
| `KisRestCandleAdapter` | `inquire-time-itemchartprice` | `FHKST03010200` | 당일 분봉 |
| `KisRestCandleAdapter` | `inquire-time-dailychartprice` | `FHKST03010230` | 과거 1m range |
| `KisMultiPriceAdapter` | `intstock-multprice` | `FHKST11300006` | 최대 30종목 멀티시세 |
| `KisOrderHttpAdapter.submit` | `order-cash` | `TTTC0012U`/`VTTC0012U`/`TTTC0011U`/`VTTC0011U` | 현금 매수/매도 |
| `KisOrderHttpAdapter.cancel` | `order-rvsecncl` | `TTTC0013U`/`VTTC0013U` | 주문 취소 |
| `KisOrderInquireCcldHttpAdapter` | `inquire-daily-ccld` | `TTTC0081R`/`VTTC0081R` | 체결 backfill |
| `KisBalanceAdapter`, `KisReconcileBalanceAdapter` | `inquire-balance` | `TTTC8434R`/`VTTC8434R` | 잔고/position 권위 |
| `KisKsdInfoAdapter` | `ksdinfo/*` | `HHKDB669100C0`/`101C0`/`102C0`/`104C0`/`105C0`/`106C0` | 배당/증자/분할/감자 등 |

## 5. 후속 검토 endpoint (다음 사이클 후보)

### A. 분봉 backfill 대안 — `FHKST03010230` (주식 일별 분봉 조회 / 과거)
- 사용자 안내 list `#10`. **모의투자 미지원이지만 실전 키 사용 시 가능**
- Yahoo Finance(1m 7일 한계) 대체 중. KIS 실전이 더 긴 기간 가능하면 전환 검토
- 단점: KIS rate limit 있음 (Yahoo는 무제한)

### B. 계좌 availability snapshot — `inquire-balance`
- endpoint: `/uapi/domestic-stock/v1/trading/inquire-balance`, `TTTC8434R`/`VTTC8434R`.
- output2 `dnca_tot_amt`·`nxdy_excc_amt`·`prvs_rcdl_excc_amt`로 현금성 snapshot을 만든다.
- output1 `hldg_qty`·`ord_psbl_qty`로 종목별 보유수량/매도가능수량 map을 만든다.
- Redis key: `account:availability:{mode}:{userId}`. 주문 path는 이 key만 읽고 KIS REST를 호출하지 않는다.
- refresh: 5분 periodic + `account.balance.changed.v1` 이벤트 기반, `userId+mode` debounce/coalesce, BACKFILL priority.

### B-2. 주문조건별 가능수량 정밀 진단 — `inquire-psbl-order` + `inquire-psbl-sell`
- BUY: `/uapi/domestic-stock/v1/trading/inquire-psbl-order`, `TTTC8908R`/`VTTC8908R`.
- SELL: `/uapi/domestic-stock/v1/trading/inquire-psbl-sell`, `TTTC8408R`.
- 주문조건(`PDNO`, `ORD_UNPR`, `ORD_DVSN`) 의존성이 있어 전략 burst 시 API matrix가 커진다.
- 운영 기본 path 채용은 보류한다. 수동 진단/사후 분석용 후보로만 유지한다.

### C. 호가창·체결 list — `inquire-asking-price-exp-ccn` + `inquire-ccnl`
- `inquire-asking-price-exp-ccn`은 이미 entry quote fallback으로 사용 중.
- `inquire-ccnl`은 최근 체결 list 표시와 WS stale 보강에 사용한다.

### D. 외국인·기관 동향 — `inquire-investor`
- 종목 상세 메타에 외국인·기관 매수·매도 추세 표시
- `InstrumentDetail.foreignRatio`·`institutionRatio` 정확화

### E. 계좌 실현손익 — `inquire-balance-rlz-pl`
- `/uapi/domestic-stock/v1/trading/inquire-balance-rlz-pl`, `TTTC8494R`.
- 공식 예제 기준 모의 TR_ID가 없어 REAL read-only adapter로 둔다.
- 내부 journal/performance 손익과 KIS 계좌 손익 diff를 산출한다. 1차는 read-only.

### F. 통합 체결가 WS — `H0UNCNT0`
- 공식 예제 `ccnl_total` 기준 통합 실시간체결가. KRX+NXT 체결을 단일 구독으로 통합 수신하며 스트림 내 `NEW_MKOP_CLS_CODE`(신 장운영 구분 코드)로 세션을 구분한다.
- `KisWebSocketBridge`는 종목당 체결가를 `H0UNCNT0` 단일 채널로만 구독한다 (종목당 1채널 — ADR 0124). 옛 분리 채널 이중 구독 경로와 토글 flag는 2026-06-13 완전 제거됐다(롤백은 CHANGELOG v3.13.2 코드 참조).

### G. 종목 추정 실적 — `HHKST668300C0`
- 애널리스트 컨센서스 (매출·영업이익 추정)
- 실제 재무(DART) 대비 갭 표시

### H. 종목 투자의견 — `FHKST663300C0`·`FHKST663400C0`
- 애널리스트 의견·증권사별 의견 표시
- 종목 상세 페이지 사이드 패널 후보

## 6. KIS 종목 마스터파일 다운로드 (참고)

KIS 공식 GitHub repo 파이썬 스크립트:
- KOSPI: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kospi_code_mst.py
- KOSDAQ: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kosdaq_code_mst.py

**구조**: HTTP에서 `kospi_code.mst.zip`·`kosdaq_code.mst.zip` 다운로드 → 고정 폭 텍스트 파일 파싱 → DataFrame.

**magicJar 사용 위치**: T3 R1(PR #71) `instrument` 테이블 적재. 우리 코드는 자체 다운로드·파싱 구현. 향후 정합 검증 시 KIS 공식 스크립트 참조.

**적재 빈도**: 마스터파일은 월 1회 정도 갱신. 사용자 SettingsModal "종목 마스터파일 수집" 버튼 또는 batch scheduled job.

## 7. MCP 검증 의무

새 KIS endpoint 사용 시 자체 추측 금지. 다음 도구로 검증:

```
mcp__kis-code-assistant__search_domestic_stock_api(query="...")
```

검증 항목:
- 정확한 endpoint path
- TR_ID
- query parameter / request body 필드
- response schema 필드명·단위(억원 vs 원, 백분율 vs 소수)
- 모의투자 지원 여부

**MCP 결과가 사용자 안내와 일치하는지 교차 검증**. 사용자 안내가 가장 정확한 source (사용자 KIS 공식 가이드 직접 참조).

## 변경 이력

| 날짜 | 변경 | PR |
|---|---|---|
| 2026-06-12 | v3.13.1 KIS API/MCP 채용 우선순위 갱신 — 현재 사용 중 API 최상단 인덱스 + P0/P1 후보 TR 확정 | TBD |
| 2026-05-04 | 본 reference docs 신설 + 사용 endpoint 표·후속 검토 list | TBD |
| 2026-05-04 | `inquire-price` (FHKST01010100) 통합 — PER·PBR·EPS·BPS·시총·업종 한글명 | #128 |
| 2026-05-04 | `search-stock-info` endpoint 정정 (`inquire-stock-info` 잘못된 사용) | #126 |
