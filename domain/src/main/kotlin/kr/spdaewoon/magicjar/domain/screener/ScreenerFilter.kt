package kr.spdaewoon.magicjar.domain.screener

import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType

/**
 * Screener universe 필터 (ADR 0071 §2-2 B-1 + spec §10).
 *
 * 1차 LOCK 필터 매트릭스:
 * - markets default: [KOSPI, KOSDAQ] (KONEX 등은 옵션)
 * - types default: [STOCK, ETF] (PREFERRED·WARRANT 등은 옵션)
 * - active=true + delistedAt IS NULL — instrument table column에서 강제 LOCK (생존자 편향 cover)
 * - marketCapMin 옵션 — null이면 미적용
 * - avgVolumeMin 옵션 (5일 평균 거래대금) — null이면 미적용
 *
 * `filterHash`: filter 4 속성의 SHA-256 (Redis cache 키 생성용 — Phase 2 backend-core 책임).
 *
 * @param markets 시장 리스트 (default KOSPI+KOSDAQ)
 * @param types 종목 타입 리스트 (default STOCK+ETF)
 * @param marketCapMin 시총 하한 (원) — null=미적용
 * @param avgVolumeMin 5일 평균 거래대금 하한 (원) — null=미적용
 */
data class ScreenerFilter(
    val markets: List<InstrumentMarket> = DEFAULT_MARKETS,
    val types: List<InstrumentType> = DEFAULT_TYPES,
    val marketCapMin: Long? = null,
    val avgVolumeMin: Long? = null,
) {
    companion object {
        val DEFAULT_MARKETS: List<InstrumentMarket> = listOf(InstrumentMarket.KOSPI, InstrumentMarket.KOSDAQ)
        val DEFAULT_TYPES: List<InstrumentType> = listOf(InstrumentType.STOCK, InstrumentType.ETF)

        /** default = KOSPI+KOSDAQ active STOCK+ETF (ADR 0071 §2-2 B-1 LOCK). */
        val DEFAULT: ScreenerFilter = ScreenerFilter()
    }
}
