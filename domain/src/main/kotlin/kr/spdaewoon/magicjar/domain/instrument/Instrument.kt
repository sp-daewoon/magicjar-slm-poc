package kr.spdaewoon.magicjar.domain.instrument

import kotlinx.datetime.LocalDate

/**
 * 종목 마스터 도메인 (ADR 0017).
 */
data class Instrument(
    val code: String,
    val isin: String?,
    val name: String,
    val nameEn: String?,
    val market: InstrumentMarket,
    val type: InstrumentType,
    val sector: String?,
    val listedAt: LocalDate?,
    val delistedAt: LocalDate?,
    val active: Boolean = true,
)

enum class InstrumentMarket { KOSPI, KOSDAQ, KONEX }

enum class InstrumentType {
    STOCK,
    ETF,
    ETN,
    PREFERRED,
    WARRANT,
    REIT,
    DR, // 주식예탁증서 — KIS scrt_grp_cls_code='DR' (PR-21a add-only)
    UNKNOWN,
}
