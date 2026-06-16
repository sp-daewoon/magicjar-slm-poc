package kr.spdaewoon.magicjar.strategy.integrity

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 전략별 strategy_position 을 order_execution(append-only 진실원)에서 **결정론적으로 재계산**하는 순수 함수 — ADR 0115 P2 amend / spec §3-3 대안 B (2026-06-10).
 *
 * ## 배경 — 2026-06-10 incident (부분체결 유실 · spec §2-B-1)
 * `strategy_position` 은 `order_execution` 로그를 **증분 적용한 mutable projection** 인데, 자신이 어떤 execution 을
 * 이미 반영했는지 추적하지 않는다(execution-단위 멱등 키 부재). 그래서 `StrategyPositionExecutionReplayService` 가
 * "행 존재" 라는 거친 신호로 멱등을 흉내내고, 그 신호가 부분체결에서 깨진다:
 * 주문 #223(전략 8) 15주 매수가 KIS 에서 **부분체결 6건(1+3+5+1+2+3주)** 으로 분할 체결됐는데,
 * 첫 체결로 행이 생긴 뒤 나머지 체결의 replay 가 "행 기존재 → skip" 되어 strategy_position **11주 vs KIS 16주 = 5주(2건) 유실**.
 *
 * 본 함수는 spec §3-3 권고 대안 B 의 핵심이다: order_execution(진실원) 목록을 **절대목표 set 으로 재계산**해
 * 어떤 경로로 누락돼도(replay-skip·backfill 우회·취소-체결 race) 다음 rebuild 가 흡수한다 — 누락 자체가 영구화 불가.
 * 증분 경로([ConsumerExecutionStrategyPositionAdapter.applyBuy]/[applySell])와 **결정론적으로 동일한 평단·realized 수식**을
 * 1:1 정합(아래 "수식 LOCK") 하므로, 정상 흐름에서는 증분과 rebuild 결과가 항상 일치한다.
 *
 * ## 수식 LOCK ([ConsumerExecutionStrategyPositionAdapter] applyBuy/applySell 1:1 — consumer 코드 read-only 정합)
 * 입력 execution 을 **시간순(eventId distinct 후)** 으로 fold 하며 다음을 적용한다:
 * - **BUY**: `nextQty = qty + fillQty`,
 *   `avgPrice = (avgPrice*qty + fillPrice*fillQty) / nextQty` (scale 4, [RoundingMode.HALF_UP]),
 *   `filledLots += 1`.
 * - **SELL**: `sellQty = min(fillQty, qty)` (KIS 권위 우선 clamp — over-sell 시 보유까지만),
 *   `realizedPnl += (fillPrice - avgPrice) * sellQty`,
 *   `nextQty = max(qty - sellQty, 0)`.
 *   `nextQty == 0` → `avgPrice`·`filledLots`·`executedExitStages` reset(전량청산 — 행 유지·realized 보존).
 *   `partialExitStage` 가 명시되면(체결의 시그널 reason `PARTIAL_TAKE_PROFIT#k` 를 consumer 가 해석해 주입) `executedExitStages += k`.
 *
 * ## 멱등 (LOCK)
 * - **같은 입력 → 같은 출력**. fold 는 순수 누적이고 IO·Clock·난수 0.
 * - **eventId 중복 입력 방어**: 같은 물리 체결이 WS+backfill 이중 경로로 두 eventId namespace 로 들어올 수 있다(spec §2-C1).
 *   본 함수는 `eventId` distinct(첫 등장 유지) 후 fold 하여 **이중 계상(over-count) 구조적 0**. 같은 eventId 재유입은 1회만 반영.
 *
 * ## 검산 (LOCK — spec 불변식 3)
 * - 산출 quantity 는 음수 불가(SELL clamp 로 0 하한). 위반 신호([RebuiltStrategyPosition.clampedNegativeSell])로 반환.
 * - 전략별 산출 quantity 합([RebuildResult.totalQuantity])이 KIS 권위 position 과 일치하는지는 **호출 측(consumer) 검산**.
 *   본 함수는 진실원 목록을 그대로 재계산할 뿐 authority 를 입력으로 받지 않는다(순수성 — 단일 책임).
 *
 * ## 순수성 (LOCK — [PositionDriftCorrection]·[LedgerAttributedDriftCorrection] 선례 1:1)
 * Spring·JPA·Clock·IO·KIS 의존 0. 입력([RebuildInput]) → 출력([RebuildResult]) 결정론.
 * execution 조회(order_execution⨝order_request)·DB write·event_id namespace 정규화는 R2 어댑터(consumer)가 배선한다.
 *
 * @see ConsumerExecutionStrategyPositionAdapter consumer 증분 경로(수식 정합 원본 — read-only)
 * @see LedgerAttributedDriftCorrection 부족 방향 귀속 보정(rebuild 의 net 집계와 동일 진실원)
 */
object StrategyPositionRebuild {
    /**
     * 전략 귀속 execution 목록에서 strategy_position 전량 재계산 — 단일 진입점.
     *
     * @return 전략별 [RebuiltStrategyPosition] (quantity·avgPrice·filledLots·realizedPnl·executedExitStages) + 검산 메타.
     *   입력이 비면 [RebuildResult.positions] 빈 맵.
     */
    fun rebuild(input: RebuildInput): RebuildResult {
        // 1) eventId 중복 방어 — 같은 물리 체결이 WS+backfill 이중 경로로 두 번 들어와도 1회만 반영(첫 등장 유지).
        val distinct =
            input.executions
                .distinctBy { it.eventId }

        // 2) 시간순 정렬 — fold 결정론(동일 시각은 eventId tie-break 로 안정 정렬).
        val ordered =
            distinct.sortedWith(
                compareBy<RebuildExecution> { it.executedAtEpochMillis }
                    .thenBy { it.eventId },
            )

        // 3) 전략별 누적 fold — BUY/SELL 수식은 consumer applyBuy/applySell 와 1:1.
        val acc = mutableMapOf<Long, MutableLot>()
        for (ex in ordered) {
            val fillQty = ex.quantity.coerceAtLeast(0)
            if (fillQty == 0) continue
            // strategyId 0(MANUAL sentinel) 은 전략 귀속 대상 아님 — consumer applyExecution skip 정합.
            if (ex.strategyId == 0L) continue
            val lot = acc.getOrPut(ex.strategyId) { MutableLot(ex.strategyId) }
            when (ex.side) {
                RebuildSide.BUY -> applyBuy(lot, ex, fillQty)
                RebuildSide.SELL -> applySell(lot, ex, fillQty)
            }
        }

        val positions =
            acc.values.associate { lot ->
                lot.strategyId to
                    RebuiltStrategyPosition(
                        strategyId = lot.strategyId,
                        quantity = lot.quantity,
                        avgPrice = lot.avgPrice,
                        filledLots = lot.filledLots,
                        realizedPnl = lot.realizedPnl,
                        executedExitStages = lot.executedExitStages.toSortedSet(),
                        clampedNegativeSell = lot.clampedNegativeSell,
                    )
            }

        return RebuildResult(positions = positions)
    }

    /** BUY 적용 — [ConsumerExecutionStrategyPositionAdapter.applyBuy] 1:1 (가중평균 평단, scale 4 HALF_UP). */
    private fun applyBuy(
        lot: MutableLot,
        ex: RebuildExecution,
        fillQty: Int,
    ) {
        val currentQty = lot.quantity
        val nextQty = currentQty + fillQty
        val currentNotional = lot.avgPrice.multiply(BigDecimal(currentQty))
        val fillNotional = ex.price.multiply(BigDecimal(fillQty))
        lot.avgPrice =
            currentNotional
                .add(fillNotional)
                .divide(BigDecimal(nextQty), 4, RoundingMode.HALF_UP)
        lot.quantity = nextQty
        lot.filledLots += 1
    }

    /** SELL 적용 — [ConsumerExecutionStrategyPositionAdapter.applySell] 1:1 (clamp·realized 누적·전량청산 reset). */
    private fun applySell(
        lot: MutableLot,
        ex: RebuildExecution,
        fillQty: Int,
    ) {
        val currentQty = lot.quantity
        val currentAvg = lot.avgPrice
        val sellQty = fillQty.coerceAtMost(currentQty)
        if (fillQty > currentQty) {
            // KIS 권위 우선 — 보유보다 많이 팔 수 없다(over-sell clamp). 위반 신호 보존(검산 메타).
            lot.clampedNegativeSell = true
        }
        val realizedDelta =
            ex.price
                .subtract(currentAvg)
                .multiply(BigDecimal(sellQty))
        lot.realizedPnl = lot.realizedPnl.add(realizedDelta)
        val nextQty = (currentQty - sellQty).coerceAtLeast(0)

        if (nextQty == 0) {
            // 전량 청산 — avgPrice·filledLots·executedExitStages reset(행 유지·realized 보존).
            lot.quantity = 0
            lot.avgPrice = BigDecimal.ZERO
            lot.filledLots = 0
            lot.executedExitStages.clear()
        } else {
            lot.quantity = nextQty
            // partialExitStage 마킹(부분 청산만 — 전량청산은 위에서 reset). consumer markPartialExitStage 정합.
            ex.partialExitStage?.let { lot.executedExitStages.add(it) }
        }
    }

    /** fold 누적기 — 전략 1개의 진행 중 lot 상태(가변 — 내부 전용). */
    private class MutableLot(
        val strategyId: Long,
        var quantity: Int = 0,
        var avgPrice: BigDecimal = BigDecimal.ZERO,
        var filledLots: Int = 0,
        var realizedPnl: BigDecimal = BigDecimal.ZERO,
        val executedExitStages: MutableSet<Int> = mutableSetOf(),
        var clampedNegativeSell: Boolean = false,
    )
}

/**
 * [StrategyPositionRebuild.rebuild] 입력 — 전략 귀속 execution 목록(consumer 가 order_execution⨝order_request 로 매핑 주입).
 *
 * 순수성 LOCK — domain([kr.spdaewoon.magicjar.domain.order.execution.OrderExecution]·OrderRequest) 의존 0.
 * consumer 가 자신의 entity 를 본 DTO 로 매핑해 호출한다(strategy-engine 은 consumer·domain 비의존).
 *
 * @param executions 전략 귀속 체결 목록(같은 user 의 임의 종목·임의 전략 혼재 가능 — strategyId 로 분리 집계).
 *   호출 측은 보통 (user, code) 한 종목 단위로 모아 호출하나, 본 함수는 strategyId 만으로 분리하므로 입력 범위 무관.
 */
data class RebuildInput(
    val executions: List<RebuildExecution>,
)

/**
 * 재계산 입력 체결 1건 — order_execution(진실원) 1행의 최소 투영.
 *
 * @param eventId 체결 멱등 키(`order_execution.event_id`). WS+backfill 이중 경로 dedup 단위(distinct by 이 값).
 * @param orderId 원주문 식별(audit — 산정 미사용, 추적 메타).
 * @param strategyId 귀속 전략(`order_request.strategy_id`). 0(MANUAL sentinel) 은 fold 에서 skip.
 * @param side 체결 방향.
 * @param quantity 체결 수량(이 체결 1건의 분할 단위 — cumulative 아님). ≤0 이면 skip.
 * @param price 체결 단가.
 * @param partialExitStage SELL 체결의 부분익절 단계 k(시그널 reason `PARTIAL_TAKE_PROFIT#k` 를 consumer 가 해석해 주입).
 *   null 이면 단계 마킹 없음(일반 청산·BUY). 전량청산 시 무시(reset).
 * @param executedAtEpochMillis 체결 시각(fold 정렬 기준 — Clock 의존 0 위해 epochMillis 주입). 동일 시각은 eventId tie-break.
 */
data class RebuildExecution(
    val eventId: String,
    val orderId: Long,
    val strategyId: Long,
    val side: RebuildSide,
    val quantity: Int,
    val price: BigDecimal,
    val partialExitStage: Int? = null,
    val executedAtEpochMillis: Long,
)

/** 체결 방향 — domain [kr.spdaewoon.magicjar.domain.order.execution.OrderSide] 와 격리한 순수 enum. */
enum class RebuildSide {
    BUY,
    SELL,
}

/**
 * [StrategyPositionRebuild.rebuild] 출력 — 전략별 재계산 결과 + 검산 메타.
 *
 * @param positions strategyId → [RebuiltStrategyPosition]. 입력에 등장한(순매수 > 0 이거나 거래 이력 있는) 전략만 포함.
 *   전량청산된 전략은 quantity=0 으로 포함된다(행 유지 의미 — consumer 가 reset row 로 upsert).
 */
data class RebuildResult(
    val positions: Map<Long, RebuiltStrategyPosition>,
) {
    /** 전 전략 산출 quantity 합 — consumer 가 KIS 권위 position 과 교차검산(spec 불변식 3). */
    val totalQuantity: Int get() = positions.values.sumOf { it.quantity }

    /** 어느 전략이든 over-sell clamp(보유 초과 매도) 가 발생했는가 — 진실원 자체의 정합 의심 신호. */
    val anyClampedNegativeSell: Boolean get() = positions.values.any { it.clampedNegativeSell }
}

/**
 * 전략 1개의 재계산된 lot 상태 — consumer 가 strategy_position upsert 에 사용(증분 결과와 동일 형상).
 *
 * @param strategyId 귀속 전략.
 * @param quantity 재계산 보유 수량(≥0 — SELL clamp 하한).
 * @param avgPrice 가중평균 평단(scale 4 — BUY 누적, 전량청산 시 0).
 * @param filledLots BUY 체결 횟수 누적(전량청산 시 0 reset).
 * @param realizedPnl 누적 실현손익(SELL `(price-avg)*sellQty` 누적 — 전량청산에도 보존).
 * @param executedExitStages 마킹된 부분익절 단계(전량청산 시 빈 집합 reset).
 * @param clampedNegativeSell 이 전략에서 over-sell clamp 가 발생했는지(진실원 정합 의심 — 보통 false).
 */
data class RebuiltStrategyPosition(
    val strategyId: Long,
    val quantity: Int,
    val avgPrice: BigDecimal,
    val filledLots: Int,
    val realizedPnl: BigDecimal,
    val executedExitStages: Set<Int>,
    val clampedNegativeSell: Boolean,
)
