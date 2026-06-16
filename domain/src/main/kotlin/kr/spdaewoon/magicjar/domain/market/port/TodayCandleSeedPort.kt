package kr.spdaewoon.magicjar.domain.market.port

/**
 * 당일 1d live candle Hash 시드 포트 — 구독 수립(ingestion lifecycle) 시점에 호출.
 *
 * ### 왜 구독 수립 시점인가 (root cause)
 * Redis `candle:today:{code}:1d` Hash는 owner(DETAIL·HOLDING·WATCHLIST·STRATEGY·PENDING_BUY) 무관하게
 * **모든 구독 종목의 모든 KIS WS 틱**으로 채워진다([KisWsPort] 구독 → 체결 틱 누적). 그런데 WS 틱만으로는
 * open = "본 첫 틱", high/low = "관측 max/min", volume = "본 CNTG_VOL 누적"이라
 * 장중 **늦은/간헐 구독** 시 9시 진짜 시가·자리 비운 사이 고저·놓친 거래량이 전부 틀어진다.
 *
 * 이 Hash를 화면(CandleQueryService)·전략(LiveCandleMerger) 둘 다 권위로 읽으므로 표시 오류 +
 * 스크리너/전략 오판(자본흐름 인접)으로 직결된다.
 *
 * UI 액션(종목상세 ENTER)이 아니라 **구독이 새로 수립/재개되는 지점**에서 시드해야 owner 무관 균일 적용된다.
 * (detail-ENTER 시드는 DETAIL owner만 cover → watchlist만 보는 종목·STRATEGY 후보가 여전히 깨진 값을 읽음.)
 *
 * ### 시드 정책
 * 공식 KIS 당일값(시가·고가·저가·현재가·누적거래량)으로 baseline 시드/재보정한다.
 * 이후 WS 틱은 live close 최신화 + (공식 고/저 초과 시) 고/저 확장만 담당하고,
 * volume은 공식 누적거래량(ACML_VOL)을 권위로 유지해 WS 체결량(CNTG_VOL) 이중계상을 막는다.
 *
 * 공식값을 못 구하면 시드 no-op(graceful) — 1d Hash는 만들지 않고 merger가 DB-only fallback.
 * mock 합성 금지 (헌법 가드 — feedback_no_mock_data).
 */
interface TodayCandleSeedPort {
    /**
     * 해당 종목의 `candle:today:{code}:1d`(+`candle:current:{code}:1d`) Hash를
     * 공식 KIS 당일 OHLCV로 시드/재보정. idempotent — 이미 정확하면 사실상 no-op.
     */
    fun seedTodayCandle(code: String)
}
