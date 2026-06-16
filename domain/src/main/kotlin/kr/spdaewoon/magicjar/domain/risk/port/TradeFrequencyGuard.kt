package kr.spdaewoon.magicjar.domain.risk.port

import kotlinx.datetime.Instant

/**
 * 거래 빈도 가드 port (ADR 0067 §5-5 + D-1 채택). 헌법 §6 abstract만.
 *
 * Redis ZSET sliding window — Phase 1 [kr.spdaewoon.magicjar.consumer.risk.TradeFrequencyRedisGuard] 구현.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface TradeFrequencyGuard {
    fun countRecentMinute(
        strategyId: Long,
        userId: String,
        now: Instant,
    ): Int

    fun countRecentDay(
        strategyId: Long,
        userId: String,
        now: Instant,
    ): Int

    fun record(
        strategyId: Long,
        userId: String,
        signalId: Long,
        now: Instant,
    )

    fun removeRecord(
        strategyId: Long,
        userId: String,
        signalId: Long,
    )
}
