package kr.spdaewoon.magicjar.domain.screener

import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval

/**
 * Screener 평가 요청 (ADR 0071 §2-1 A-1 + spec §2-2 + §6-1).
 *
 * REST `POST /api/screener/evaluate` Request DTO → 도메인 변환 결과.
 *
 * @param userId 사용자 ID (multi-tenant 격리)
 * @param strategyId Strategy ID (저장된 DSL 참조)
 * @param filter universe 필터 (default = KOSPI+KOSDAQ active STOCK+ETF)
 * @param interval 캔들 인터벌 (default 1d — 일봉 기반 universe screening)
 * @param limit 랭킹 최대 결과 수 (1~500, default 50)
 */
data class ScreenerCriteria(
    val userId: Long,
    val strategyId: Long,
    val filter: ScreenerFilter = ScreenerFilter.DEFAULT,
    val interval: CandleInterval = CandleInterval.ONE_DAY,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(userId > 0) { "userId must be positive: $userId" }
        require(strategyId > 0) { "strategyId must be positive: $strategyId" }
        require(limit in MIN_LIMIT..MAX_LIMIT) {
            "limit must be in [$MIN_LIMIT..$MAX_LIMIT]: $limit"
        }
    }

    companion object {
        const val MIN_LIMIT: Int = 1
        const val MAX_LIMIT: Int = 500
        const val DEFAULT_LIMIT: Int = 50
    }
}
