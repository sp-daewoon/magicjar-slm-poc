package kr.spdaewoon.magicjar.api.persistence.performance

import kr.spdaewoon.magicjar.domain.performance.PerformanceSummary
import kr.spdaewoon.magicjar.domain.performance.PnlDaily
import kr.spdaewoon.magicjar.domain.performance.port.PerformanceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * [PerformanceRepository] port JPA 구현 어댑터 — Flyway V30 정합 (ADR 0072 §9-3).
 *
 * 헌법 §6 (ADR 0051) 준수: helper 메서드는 이 클래스 private fun 위치.
 *
 * ### UPSERT 처리 (ADR 0072 §3-2 UQ 정합)
 * - pnl_daily: UQ (user_id, trade_date) — 기존 row 존재 시 DELETE + INSERT (멱등성)
 * - performance_summary: UQ (user_id, period, period_start, period_end) — 동일
 *
 * userId: String — user_account.user_id VARCHAR(40) 정합 (V1 baseline).
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Component
class PerformanceRepositoryAdapter(
    private val summaryJpaRepository: PerformanceSummaryJpaRepository,
    private val pnlDailyJpaRepository: PnlDailyJpaRepository,
) : PerformanceRepository {
    private val log = LoggerFactory.getLogger(PerformanceRepositoryAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findSummary(
        userId: String,
        period: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): PerformanceSummary? =
        summaryJpaRepository
            .findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(userId, period, periodStart, periodEnd)
            ?.let { summaryToDomain(it) }

    @Transactional
    override fun saveSummary(summary: PerformanceSummary): PerformanceSummary {
        val existing =
            summaryJpaRepository.findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(
                summary.userId,
                summary.period,
                summary.periodStart,
                summary.periodEnd,
            )
        if (existing != null) {
            summaryJpaRepository.delete(existing)
            summaryJpaRepository.flush()
        }
        val entity = summaryToEntity(summary)
        val saved = summaryJpaRepository.save(entity)
        log.debug(
            "performance_summary UPSERT: userId={} period={} [{},{}] totalPnl={}",
            saved.userId,
            saved.period,
            saved.periodStart,
            saved.periodEnd,
            saved.totalPnl,
        )
        return summaryToDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun findPnlDailyRange(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<PnlDaily> =
        pnlDailyJpaRepository
            .findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(userId, from, to)
            .map { pnlToDomain(it) }

    @Transactional(readOnly = true)
    override fun findPnlDaily(
        userId: String,
        tradeDate: LocalDate,
    ): PnlDaily? = pnlDailyJpaRepository.findByUserIdAndTradeDate(userId, tradeDate)?.let { pnlToDomain(it) }

    @Transactional
    override fun savePnlDaily(pnlDaily: PnlDaily): PnlDaily {
        val existing = pnlDailyJpaRepository.findByUserIdAndTradeDate(pnlDaily.userId, pnlDaily.tradeDate)
        if (existing != null) {
            pnlDailyJpaRepository.delete(existing)
            pnlDailyJpaRepository.flush()
        }
        val entity = pnlToEntity(pnlDaily)
        val saved = pnlDailyJpaRepository.save(entity)
        log.debug(
            "pnl_daily UPSERT: userId={} tradeDate={} realizedPnl={} tradeCount={}",
            saved.userId,
            saved.tradeDate,
            saved.realizedPnl,
            saved.tradeCount,
        )
        return pnlToDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun findEarliestTradeDate(userId: String): LocalDate? = pnlDailyJpaRepository.findEarliestTradeDateByUserId(userId)

    // --- private helpers (헌법 §6 — Repository interface 내 body 금지 → 여기에 위치) ---

    private fun summaryToEntity(summary: PerformanceSummary): PerformanceSummaryJpaEntity =
        PerformanceSummaryJpaEntity(
            id = summary.id,
            userId = summary.userId,
            period = summary.period,
            periodStart = summary.periodStart,
            periodEnd = summary.periodEnd,
            totalPnl = summary.totalPnl,
            mddPct = summary.mddPct,
            winRatePct = summary.winRatePct,
            profitLossRatio = summary.profitLossRatio,
            tradeCount = summary.tradeCount,
            metrics = summary.metrics,
            computedAt = Instant.now(),
        )

    private fun summaryToDomain(entity: PerformanceSummaryJpaEntity): PerformanceSummary =
        PerformanceSummary(
            id = entity.id,
            userId = entity.userId,
            period = entity.period,
            periodStart = entity.periodStart,
            periodEnd = entity.periodEnd,
            totalPnl = entity.totalPnl,
            mddPct = entity.mddPct,
            winRatePct = entity.winRatePct,
            profitLossRatio = entity.profitLossRatio,
            tradeCount = entity.tradeCount,
            metrics = entity.metrics,
            computedAt = entity.computedAt,
        )

    private fun pnlToEntity(pnlDaily: PnlDaily): PnlDailyJpaEntity =
        PnlDailyJpaEntity(
            id = pnlDaily.id,
            userId = pnlDaily.userId,
            tradeDate = pnlDaily.tradeDate,
            realizedPnl = pnlDaily.realizedPnl,
            winCount = pnlDaily.winCount,
            lossCount = pnlDaily.lossCount,
            tradeCount = pnlDaily.tradeCount,
            avgProfit = pnlDaily.avgProfit,
            avgLoss = pnlDaily.avgLoss,
            equityAtClose = pnlDaily.equityAtClose,
            createdAt = Instant.now(),
        )

    private fun pnlToDomain(entity: PnlDailyJpaEntity): PnlDaily =
        PnlDaily(
            id = entity.id,
            userId = entity.userId,
            tradeDate = entity.tradeDate,
            realizedPnl = entity.realizedPnl,
            winCount = entity.winCount,
            lossCount = entity.lossCount,
            tradeCount = entity.tradeCount,
            avgProfit = entity.avgProfit,
            avgLoss = entity.avgLoss,
            equityAtClose = entity.equityAtClose,
            createdAt = entity.createdAt,
        )
}
