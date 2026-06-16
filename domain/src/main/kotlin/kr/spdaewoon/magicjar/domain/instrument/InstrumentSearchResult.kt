package kr.spdaewoon.magicjar.domain.instrument

/**
 * 종목 검색 결과 도메인 모델 (T-IM1 검색 인덱스 — PR-11a add-only).
 * score 기준 내림차순 정렬. backend-core InstrumentSearchService가 사용.
 */
data class InstrumentSearchResult(
    val code: String,
    val name: String,
    val market: InstrumentMarket,
    val sector: String?,
    /** 매칭 score (1.0 = 완전 일치, 0.5+ = 부분 일치) */
    val score: Double,
)
