package kr.spdaewoon.magicjar.domain.performance

import kr.spdaewoon.magicjar.domain.order.OrderSide
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * 전략 왕복(round-trip) 집계 순수 계산기 — spec `2026-06-16-strategy-daily-performance.md` §4-1 정합.
 *
 * ## 책임
 * 체결 로그([ExecutionRecord]) 스트림을 (strategyId, code) 별로 **시간순 평균단가 replay** 하여,
 * 포지션 수량이 0 으로 복귀하는 순간을 왕복 1건으로 마감하고, 왕복들을 청산일(KST) 로 그룹핑해
 * 전략×일자 성과([StrategyDailyPerformance]) 를 산출한다.
 *
 * ## 기존 패턴 계승 — DailyPerformanceJob 평균단가 replay
 * 기존 user 단위 `DailyPerformanceJob.replayRealizedPnls` 의 평균단가 replay 를 (strategyId, code) 축으로
 * 확장한 것이다. **다만 v1 산식은 수수료·세금을 분모/손익에서 분리**(spec §4-1: 진입금액=평균단가×수량,
 * 실현손익=(매도가−평균단가)×수량). 기존 잡이 fee/tax 를 cost·pnl 에 반영하는 것과는 의도적으로 다르다
 * (spec 결재 단위가 "왕복 cost basis = 진입금액" 이므로). 후속 라운드가 수수료 반영을 원하면 spec 갱신 후 확장.
 *
 * ## 결정론·순수성
 * 외부 I/O 0, 난수 0. 동일 입력(같은 체결 리스트·zone)에 동일 출력. 입력 순서가 뒤섞여 와도 executedAt
 * 으로 안정 정렬한다(동시각 동률은 입력 순서 보존 — Kotlin sortedBy 안정 정렬).
 *
 * // AI 호출 0 — 순수 계산 도메인 (spec §4-1)
 * // KIS 호출 0 — 체결 로그만 입력
 */
object StrategyDailyPerformanceCalculator {
    /** 평균단가 정밀도 — 기존 DailyPerformanceJob(8자리 HALF_UP) 계승. */
    private const val AVG_PRICE_SCALE = 8

    /** 금액(손익·진입금액·수익률) 출력 scale. */
    private const val MONEY_SCALE = 4
    private const val RETURN_PCT_SCALE = 4

    private val HUNDRED = BigDecimal("100")

    /**
     * 체결 스트림 → 전략×일자 성과 집계.
     *
     * @param executions  체결 1건들(전 전략·전 종목 혼재 가능). executedAt 기준 안정 정렬 후 replay.
     * @param zone        영업일 귀속 시간대 (KST 기본). 청산 체결의 executedAt 을 이 zone 의 LocalDate 로 변환.
     * @return (strategyId, tradeDate) 별 1행. tradeDate 오름차순, 동률 시 strategyId 오름차순 정렬.
     */
    fun aggregate(
        executions: List<ExecutionRecord>,
        zone: ZoneId = KST,
    ): List<StrategyDailyPerformance> {
        val roundTrips = extractRoundTrips(executions, zone)
        return aggregateDaily(roundTrips)
    }

    /**
     * 체결 스트림 → 마감된 왕복 리스트 (일자 그룹핑 전 중간 산출 — 배치/검증이 직접 쓸 수 있게 공개).
     *
     * (strategyId, code) 별 독립 replay. 매수는 누적·평균단가 갱신, 매도는 보유분만큼 청산하며 수량 0
     * 복귀 시 그 사이 누적된 실현손익·진입금액을 closedDate(청산 체결일) 에 귀속한 왕복 1건으로 마감한다.
     */
    fun extractRoundTrips(
        executions: List<ExecutionRecord>,
        zone: ZoneId = KST,
    ): List<RoundTrip> {
        if (executions.isEmpty()) return emptyList()

        val sorted = executions.sortedBy { it.executedAt }
        val states = mutableMapOf<PositionKey, OpenRoundTrip>()
        val closed = mutableListOf<RoundTrip>()

        for (exec in sorted) {
            val key = PositionKey(exec.strategyId, exec.code)
            val state = states.getOrPut(key) { OpenRoundTrip() }
            when (exec.side) {
                OrderSide.BUY -> applyBuy(state, exec)
                OrderSide.SELL -> {
                    val finished = applySell(state, exec, zone)
                    if (finished != null) {
                        closed += finished
                        states[key] = OpenRoundTrip() // 다음 왕복은 새 진입부터
                    }
                }
            }
        }
        return closed
    }

    // --- private helpers (헌법 §6) ---

    private fun applyBuy(
        state: OpenRoundTrip,
        exec: ExecutionRecord,
    ) {
        if (exec.quantity <= 0L) return
        val addQty = BigDecimal(exec.quantity)
        val currentCost = state.qty.multiply(state.avgPrice)
        val addedCost = addQty.multiply(exec.price)
        val nextQty = state.qty.add(addQty)
        state.avgPrice =
            if (nextQty.signum() > 0) {
                currentCost.add(addedCost).divide(nextQty, AVG_PRICE_SCALE, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
        state.qty = nextQty
    }

    /**
     * 매도 적용. 보유분만큼만 청산(롱 온리 방어 — 음수 포지션 금지, spec §4-1).
     * 청산 후 수량 0 복귀 시 왕복 마감 → [RoundTrip] 반환, 아니면 null.
     */
    private fun applySell(
        state: OpenRoundTrip,
        exec: ExecutionRecord,
        zone: ZoneId,
    ): RoundTrip? {
        if (exec.quantity <= 0L || state.qty.signum() <= 0) {
            // 보유 없는 매도(공매도성 비정상 데이터) — 방어: 무시 (음수 포지션 금지).
            return null
        }
        val sellQty = BigDecimal(exec.quantity)
        val closedQty = sellQty.min(state.qty) // 보유 초과분은 버림(방어)
        val pnl =
            exec.price
                .subtract(state.avgPrice)
                .multiply(closedQty)
        val cost = state.avgPrice.multiply(closedQty)

        state.realizedPnl = state.realizedPnl.add(pnl)
        state.costBasis = state.costBasis.add(cost)
        state.qty = state.qty.subtract(closedQty)

        if (state.qty.signum() <= 0) {
            val closedDate = exec.executedAt.atZone(zone).toLocalDate()
            return RoundTrip(
                strategyId = exec.strategyId,
                code = exec.code,
                closedDate = closedDate,
                realizedPnl = state.realizedPnl.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                costBasis = state.costBasis.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            )
        }
        return null
    }

    private fun aggregateDaily(roundTrips: List<RoundTrip>): List<StrategyDailyPerformance> {
        if (roundTrips.isEmpty()) return emptyList()
        return roundTrips
            .groupBy { DailyKey(it.strategyId, it.closedDate) }
            .map { (key, trips) -> foldDaily(key, trips) }
            .sortedWith(compareBy({ it.tradeDate }, { it.strategyId }))
    }

    private fun foldDaily(
        key: DailyKey,
        trips: List<RoundTrip>,
    ): StrategyDailyPerformance {
        var realizedPnl = BigDecimal.ZERO
        var totalCostBasis = BigDecimal.ZERO
        var winCount = 0
        var lossCount = 0
        for (t in trips) {
            realizedPnl = realizedPnl.add(t.realizedPnl)
            totalCostBasis = totalCostBasis.add(t.costBasis)
            when (t.realizedPnl.signum()) {
                1 -> winCount++
                -1 -> lossCount++
                else -> Unit // break-even — round_trip_count 엔 포함, win/loss 제외
            }
        }
        val returnPct =
            if (totalCostBasis.signum() == 0) {
                null
            } else {
                realizedPnl
                    .multiply(HUNDRED)
                    .divide(totalCostBasis, RETURN_PCT_SCALE, RoundingMode.HALF_UP)
            }
        return StrategyDailyPerformance(
            strategyId = key.strategyId,
            tradeDate = key.tradeDate,
            roundTripCount = trips.size,
            winCount = winCount,
            lossCount = lossCount,
            realizedPnl = realizedPnl.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            totalCostBasis = totalCostBasis.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            returnPct = returnPct,
        )
    }

    private data class PositionKey(
        val strategyId: Long,
        val code: String,
    )

    private data class DailyKey(
        val strategyId: Long,
        val tradeDate: LocalDate,
    )

    /** replay 진행 중인 1 왕복의 가변 상태. */
    private class OpenRoundTrip(
        var qty: BigDecimal = BigDecimal.ZERO,
        var avgPrice: BigDecimal = BigDecimal.ZERO,
        var realizedPnl: BigDecimal = BigDecimal.ZERO,
        var costBasis: BigDecimal = BigDecimal.ZERO,
    )

    /** KST — 영업일 귀속 기본 시간대. */
    val KST: ZoneId = ZoneId.of("Asia/Seoul")
}
