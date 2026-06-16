package kr.spdaewoon.magicjar.api.service.backtest

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestPositionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestPositionJpaRepository
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaEntity
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestTradeJpaEntity
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestTradeJpaRepository
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestPosition
import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.BacktestTrade
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 백테스트 실행 결과 영속 컴포넌트 (ADR 0061 §4 — B-1 3 테이블 분리 채택).
 *
 * ### 책임
 * 1. BacktestResult → backtest_run + backtest_trade + backtest_position 3 테이블 트랜잭션 영속
 * 2. KPI 계산 결과를 backtest_run에 집계 저장
 * 3. 결과 0건(NO_CANDLE_DATA)이더라도 FAILED status로 그대로 저장 (feedback_no_mock_data.md 정합)
 *
 * ### 헌법 §6 정합
 * - Repository interface에 body 가진 fun 0건
 * - 도메인 → 엔티티 변환 helper는 본 class private fun 위치
 */
@Component
class BacktestPersister(
    private val runRepository: BacktestRunJpaRepository,
    private val tradeRepository: BacktestTradeJpaRepository,
    private val positionRepository: BacktestPositionJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(BacktestPersister::class.java)

    /**
     * 백테스트 실행 결과를 DB에 영속한다.
     *
     * @param userId 실행 사용자 ID
     * @param result BacktestEngine 실행 결과 (Success 또는 Aborted)
     * @return 저장된 backtest_run.id
     */
    @Transactional
    fun persist(
        userId: String,
        result: BacktestResult,
    ): Long {
        val request = extractRequest(result)
        val now = Instant.now()

        val runEntity =
            when (result) {
                is BacktestResult.Success -> persistSuccess(userId, request, result, now)
                is BacktestResult.Aborted -> persistAborted(userId, request, result, now)
            }

        val runId =
            requireNotNull(runEntity.id) {
                "저장 후 backtest_run.id가 null입니다 — DB auto-increment 이상"
            }

        log.info(
            "백테스트 결과 영속 완료: runId={}, userId={}, status={}, trades={}, positions={}",
            runId,
            userId,
            runEntity.status,
            runEntity.tradeCount ?: 0,
            runEntity.positionCount ?: 0,
        )

        return runId
    }

    // =====================================================================
    // private helpers — 헌법 §6 정합: caller class 내부 위치
    // =====================================================================

    private fun persistSuccess(
        userId: String,
        request: BacktestRequest,
        result: BacktestResult.Success,
        now: Instant,
    ): BacktestRunJpaEntity {
        val rangeFrom = toInstant(request.from.toString())
        val rangeTo = toInstant(request.to.toString())

        val runEntity =
            BacktestRunJpaEntity(
                userId = userId,
                strategyId = request.strategyId,
                code = request.universe.joinToString(","),
                interval = request.interval,
                ruleDsl = buildRuleDslSummary(request),
                rangeFrom = rangeFrom,
                rangeTo = rangeTo,
                status = "SUCCEEDED",
                startedAt = now.minusMillis(result.durationMs),
                completedAt = now,
                totalReturn = result.performance.totalReturn.setScale(4, RoundingMode.HALF_EVEN),
                winRate = result.performance.winRate.setScale(4, RoundingMode.HALF_EVEN),
                mdd = result.performance.maxDrawdown.setScale(4, RoundingMode.HALF_EVEN),
                sharpe = result.performance.sharpeRatio?.setScale(4, RoundingMode.HALF_EVEN),
                sortino = null, // post-MVP (ADR 0061 §6-2)
                tradeCount = result.trades.size,
                positionCount = result.positions.size,
                detailJson = buildDetailJson(result.performance),
                updatedAt = now,
            )

        val savedRun = runRepository.save(runEntity)
        val runId = requireNotNull(savedRun.id)

        // 매매 기록 영속
        val tradeEntities = result.trades.map { trade -> buildTradeEntity(savedRun, trade) }
        tradeRepository.saveAll(tradeEntities)

        // 포지션 기록 영속
        val positionEntities = result.positions.map { pos -> buildPositionEntity(savedRun, pos) }
        positionRepository.saveAll(positionEntities)

        log.debug(
            "backtest_run SUCCEEDED 저장: runId={}, trades={}, positions={}",
            runId,
            tradeEntities.size,
            positionEntities.size,
        )

        return savedRun
    }

    private fun persistAborted(
        userId: String,
        request: BacktestRequest,
        result: BacktestResult.Aborted,
        now: Instant,
    ): BacktestRunJpaEntity {
        val rangeFrom = toInstant(request.from.toString())
        val rangeTo = toInstant(request.to.toString())

        // NO_CANDLE_DATA이더라도 FAILED로 그대로 저장 (feedback_no_mock_data.md 정합)
        val runEntity =
            BacktestRunJpaEntity(
                userId = userId,
                strategyId = request.strategyId,
                code = request.universe.joinToString(","),
                interval = request.interval,
                ruleDsl = buildRuleDslSummary(request),
                rangeFrom = rangeFrom,
                rangeTo = rangeTo,
                status = "FAILED",
                startedAt = now.minusMillis(result.durationMs),
                completedAt = now,
                totalReturn = null,
                winRate = null,
                mdd = null,
                sharpe = null,
                sortino = null,
                tradeCount = result.partialTrades.size,
                positionCount = 0,
                detailJson = buildAbortDetailJson(result.reason, result.timeoutCount),
                updatedAt = now,
            )

        val savedRun = runRepository.save(runEntity)
        val runId = requireNotNull(savedRun.id)

        // 부분 매매 기록 영속 (중단 시점까지)
        if (result.partialTrades.isNotEmpty()) {
            val tradeEntities = result.partialTrades.map { trade -> buildTradeEntity(savedRun, trade) }
            tradeRepository.saveAll(tradeEntities)
        }

        log.debug(
            "backtest_run FAILED(Aborted) 저장: runId={}, reason={}, partialTrades={}",
            runId,
            result.reason,
            result.partialTrades.size,
        )

        return savedRun
    }

    private fun buildTradeEntity(
        run: BacktestRunJpaEntity,
        trade: BacktestTrade,
    ): BacktestTradeJpaEntity =
        BacktestTradeJpaEntity(
            run = run,
            ts = trade.executedAt.toJavaInstant(),
            side = trade.side.name,
            price = trade.executedPrice,
            quantity = trade.quantity,
            fee = trade.commission.add(trade.tax),
            ruleSignal = extractRuleSignal(trade.signalReason),
        )

    private fun buildPositionEntity(
        run: BacktestRunJpaEntity,
        pos: BacktestPosition,
    ): BacktestPositionJpaEntity {
        val returnPct =
            if (pos.closedAt != null && pos.avgEntryPrice.compareTo(BigDecimal.ZERO) != 0) {
                pos.realizedPnl
                    .divide(
                        pos.avgEntryPrice.multiply(BigDecimal(pos.quantity)),
                        4,
                        RoundingMode.HALF_EVEN,
                    )
            } else {
                null
            }

        return BacktestPositionJpaEntity(
            run = run,
            openTs = pos.openedAt.toJavaInstant(),
            closeTs = pos.closedAt?.toJavaInstant(),
            entryPrice = pos.avgEntryPrice,
            exitPrice = null, // 1차: 평균 매수가만 저장 (exit는 closeTs 시점 마지막 trade price에서 추론 가능)
            quantity = pos.quantity,
            pnl = pos.realizedPnl,
            returnPct = returnPct,
        )
    }

    private fun buildRuleDslSummary(request: BacktestRequest): String =
        "strategyId=${request.strategyId} universe=${request.universe} " +
            "tax=${request.taxModel} commission=${request.commissionModel} slippage=${request.slippageModel}"

    private fun buildDetailJson(perf: BacktestPerformance): String =
        objectMapper.writeValueAsString(
            mapOf(
                "totalReturn" to perf.totalReturn,
                "cagr" to perf.cagr,
                "maxDrawdown" to perf.maxDrawdown,
                "sharpeRatio" to perf.sharpeRatio,
                "winRate" to perf.winRate,
                "totalTrades" to perf.totalTrades,
                "winningTrades" to perf.winningTrades,
                "losingTrades" to perf.losingTrades,
                "avgWin" to perf.avgWin,
                "avgLoss" to perf.avgLoss,
                "profitFactor" to perf.profitFactor,
                "tradingFrequency" to perf.tradingFrequency,
            ),
        )

    private fun buildAbortDetailJson(
        reason: String,
        timeoutCount: Int,
    ): String =
        objectMapper.writeValueAsString(
            mapOf(
                "abortReason" to reason,
                "timeoutCount" to timeoutCount,
            ),
        )

    private fun extractRuleSignal(signalReason: Map<String, Any>): String? {
        val raw = signalReason["rule"] ?: signalReason["trigger"] ?: return null
        return raw.toString().take(80)
    }

    private fun extractRequest(result: BacktestResult): BacktestRequest =
        when (result) {
            is BacktestResult.Success -> result.request
            is BacktestResult.Aborted -> result.request
        }

    private fun toInstant(dateStr: String): Instant =
        java.time.LocalDate
            .parse(dateStr)
            .atStartOfDay(java.time.ZoneId.of("Asia/Seoul"))
            .toInstant()
}
