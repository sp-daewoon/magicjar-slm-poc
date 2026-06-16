package kr.spdaewoon.magicjar.domain.risk.port

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 일일 실현 P&L provider (ADR 0067 §5-5 + B-1 채택). 헌법 §6 abstract만.
 *
 * KST 자정 reset — Redis cache `daily_pnl:{userId}:{date}` TTL 자정까지.
 * Phase 1 [kr.spdaewoon.magicjar.consumer.risk.DailyPnlCalculator] 구현.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface DailyPnlProvider {
    fun getDailyPnl(
        userId: String,
        kstNow: Instant,
    ): BigDecimal
}
