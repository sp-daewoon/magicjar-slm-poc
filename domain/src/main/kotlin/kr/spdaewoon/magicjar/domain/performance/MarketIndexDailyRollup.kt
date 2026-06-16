package kr.spdaewoon.magicjar.domain.performance

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 지수×일자 OHLC 롤업 1행 — spec `2026-06-16-strategy-daily-performance.md` §3 (V86 `market_index_daily`) 의 순수 도메인 사영.
 *
 * `market_index_snapshot`(V47, 1분 단위)을 KST 영업일치로 집계한 결과다.
 * 전 전략 공유 벤치마크·α·레짐 source 이며, 배치(웨이브 B)가 [MarketIndexDailyRollupService] 의
 * 산출을 영속한 뒤 전략 일자행(`strategy_daily_performance`)의 benchmark·alpha·regime 을 이 값으로 채운다.
 *
 * ⚠️ 이름 주의: 도메인 `kr.spdaewoon.magicjar.domain.market.index.MarketIndexDaily` 는 ADR 0100 일봉 candle
 * (category·volume) 모델로 본 V86 롤업과 별개 개념이다. 충돌 회피 위해 본 타입은 `Rollup` 접미사를 둔다.
 *
 * 본 타입은 순수 도메인 값(POKO) — Spring·JPA·외부 I/O 의존 0.
 *
 * @param indexCode    KIS index code (0001=코스피 · 1001=코스닥, v1 2종 확장 가능).
 * @param tradeDate    KST 영업일.
 * @param open         그날 첫 snapshot 가격.
 * @param high         그날 max.
 * @param low          그날 min.
 * @param close        그날 마지막 snapshot 가격.
 * @param changePct    (close − prev_close) / prev_close × 100. 전일 행 없으면 (close − open) / open × 100.
 * @param rangePct     (high − low) / open × 100 — 당일 변동성. open=0 이면 null.
 * @param regime       본 지수 기준 룰 레짐 (spec §4-4 우선순위: HIGH_VOL > UPTREND > DOWNTREND > SIDEWAYS).
 */
data class MarketIndexDailyRollup(
    val indexCode: String,
    val tradeDate: LocalDate,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val changePct: BigDecimal?,
    val rangePct: BigDecimal?,
    val regime: MarketRegime?,
) {
    init {
        require(indexCode.isNotBlank()) { "지수 코드 빈 문자열 금지" }
        require(high >= low) { "high < low 위반: indexCode=$indexCode high=$high low=$low" }
    }
}
