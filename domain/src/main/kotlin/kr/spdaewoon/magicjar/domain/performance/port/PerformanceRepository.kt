package kr.spdaewoon.magicjar.domain.performance.port

import kr.spdaewoon.magicjar.domain.performance.PerformanceSummary
import kr.spdaewoon.magicjar.domain.performance.PnlDaily
import java.time.LocalDate

/**
 * Performance 영속 port — 헌법 §6 (ADR 0051) abstract fun only LOCK.
 *
 * body 가진 fun 0건 — Spring Data Repository interface body fun 함정 방지.
 * 구현 어댑터: PerformanceRepositoryAdapter (api 모듈).
 *
 * userId: String — user_account.user_id VARCHAR(40) 정합 (V1 baseline).
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface PerformanceRepository {
    /** Performance Summary 조회 (userId + period + 기간 매칭). */
    fun findSummary(
        userId: String,
        period: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): PerformanceSummary?

    /** Performance Summary UPSERT (UQ user_id+period+period_start+period_end 기반). */
    fun saveSummary(summary: PerformanceSummary): PerformanceSummary

    /** 기간 내 pnl_daily 목록 조회 (날짜 오름차순). */
    fun findPnlDailyRange(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<PnlDaily>

    /** pnl_daily 특정 날짜 조회 (단건). */
    fun findPnlDaily(
        userId: String,
        tradeDate: LocalDate,
    ): PnlDaily?

    /** pnl_daily UPSERT (UQ user_id+trade_date 기반). */
    fun savePnlDaily(pnlDaily: PnlDaily): PnlDaily

    /** 해당 userId의 최초 거래일 조회 (ALL 기간 계산용). null = 거래 0건. */
    fun findEarliestTradeDate(userId: String): LocalDate?
}
