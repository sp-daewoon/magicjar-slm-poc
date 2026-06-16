package kr.spdaewoon.magicjar.domain.screener

/**
 * Screener 전체 평가 결과 (ADR 0071 §2-1·§2-7 + spec §2-2).
 *
 * REST `POST /api/screener/evaluate` Response 도메인 모델.
 *
 * 통계 매트릭스:
 * - universeSize: 필터 후 universe 사이즈 (instrument table row 수)
 * - evaluatedCount: 평가 시도 + 완료 종목 수 (skipped 제외, HOLD 포함)
 * - matchedCount: BUY 시그널 매칭 (랭킹 진입 종목)
 * - skippedCount: skip 매트릭스 합계 (캔들 미수집·부족·timeout·error)
 * - skippedReasons: skip 사유별 카운트 — Map<label, count> (사용자 한국어 노출)
 * - matches: 랭킹된 매칭 결과 (limit 적용 — descending score)
 * - elapsedMs: 전체 평가 elapsed
 * - cacheHit: Phase 2 backend-core가 Redis cache HIT 시 true 세팅 (Phase 1 engine 출력은 항상 false)
 * - cacheKey: 캐시 키 (debug용, Phase 2 채움)
 *
 * Phase 1 strategy-engine은 cacheKey="" / cacheHit=false 으로 반환 — Phase 2 backend-core가 set + 캐시 wrap.
 */
data class ScreenerRunResult(
    val universeSize: Int,
    val evaluatedCount: Int,
    val matchedCount: Int,
    val skippedCount: Int,
    val skippedReasons: Map<String, Int>,
    val matches: List<ScreenerMatch>,
    val elapsedMs: Long,
    val cacheHit: Boolean = false,
    val cacheKey: String = "",
)
