package kr.spdaewoon.magicjar.domain.instrument

/**
 * 종목 별칭 (ADR 0017 §2-2 — alias_source KRX·NAVER·FNGUIDE).
 * 1차 MVP: KRX source만 사용. NAVER·FNGUIDE는 post-MVP.
 */
data class InstrumentAlias(
    val code: String,
    val alias: String,
    val source: AliasSource,
)

/**
 * 별칭 출처 (ADR 0017 §2-2 정합).
 */
enum class AliasSource {
    KRX,
    NAVER,
    FNGUIDE,
}
