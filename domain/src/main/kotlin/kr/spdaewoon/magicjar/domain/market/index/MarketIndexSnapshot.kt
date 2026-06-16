package kr.spdaewoon.magicjar.domain.market.index

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 지수·섹터 1분 snapshot 도메인 모델 (ADR 0100 §2-7 V47 DDL 정합).
 *
 * 1분 cron(MarketIndexSyncJob) 발사 결과 영속 + Redis cache + Kafka publish 단일 진실원.
 * 해외 지수는 한국 시각 KST 기준 ts 사용.
 *
 * @property code KIS FID_INPUT_ISCD (국내) 또는 SYMB (해외) raw 값
 * @property category 분류 (DOMESTIC_INDEX·DOMESTIC_SECTOR·OVERSEAS_INDEX)
 * @property ts 1분 단위 snapshot 시각 (KST)
 * @property price 현재가 (BSTP_NMIX_PRPR · 해외 last)
 * @property changeRate 전일 대비 % (BSTP_NMIX_PRDY_CTRT · 해외 rate). nullable — KIS 응답 부재 시
 * @property volume 누적거래량 (ACML_VOL). nullable — 일부 지수 미제공
 * @property rawPayload KIS raw response JSON. KIS-first 정합 — 필드 변경 시 add-only 흡수
 */
data class MarketIndexSnapshot(
    val code: String,
    val category: IndexCategory,
    val ts: Instant,
    val price: BigDecimal,
    val changeRate: BigDecimal? = null,
    val volume: Long? = null,
    val rawPayload: String? = null,
) {
    init {
        require(code.isNotBlank()) { "지수 코드 빈 문자열 금지" }
        require(price >= BigDecimal.ZERO) { "지수 가격 음수 금지: code=$code price=$price" }
        volume?.let { require(it >= 0L) { "누적거래량 음수 금지: code=$code volume=$it" } }
    }
}

/**
 * 지수·섹터 일봉 도메인 모델 (ADR 0100 §2-4 일봉 backfill 결과).
 *
 * MarketIndexDailyCloseJob (16:30 KST + 06:00 KST) 발사 결과 영속.
 * MA 계산용 candle source — TrendCalculator(R1 backend-core) input.
 *
 * @property tradeDate 거래일 (KST 캘린더)
 * @property open · high · low · close OHLC
 * @property volume 일 누적거래량. nullable
 */
data class MarketIndexDaily(
    val code: String,
    val category: IndexCategory,
    val tradeDate: LocalDate,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long? = null,
    val rawPayload: String? = null,
) {
    init {
        require(code.isNotBlank()) { "지수 코드 빈 문자열 금지" }
        require(open >= BigDecimal.ZERO && close >= BigDecimal.ZERO) {
            "OHLC 음수 금지: code=$code open=$open close=$close"
        }
        require(high >= low) { "high < low 위반: code=$code high=$high low=$low" }
    }
}
