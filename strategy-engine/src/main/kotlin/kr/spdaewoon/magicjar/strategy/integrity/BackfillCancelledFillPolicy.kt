package kr.spdaewoon.magicjar.strategy.integrity

/**
 * CANCELLED 주문의 KIS 실체결 누락 대조 판정 — 순수 함수 (ADR 0115 §2-P2-② / spec §2-B-1 "취소-체결 race" · 2026-06-10).
 *
 * ## 배경 — 2026-06-10 G5 incident (취소-체결 race · 11주 영구 미기록)
 * 매도 주문 #229~#232(합 14주, 005930)가 시스템상 `CANCELLED`·order_execution 0건인데 KIS 실보유는 16→5주
 * (11주가 실제 체결됨). 흐름: 우리 시스템이 진입 timeout·resting 청산으로 취소를 발사(상태 CANCELLED 마킹)했으나,
 * **KIS 는 그 직전 부분체결을 이미 확정**한 race. `KisExecutionBackfillService.syncOrderStatusAfterBackfill` 의
 * `isTerminalForBackfill()`(CANCELLED terminal skip)이 status 재마킹뿐 아니라 체결 흡수 경로 자체를 막아
 * order_execution(진실원)에 체결 row 가 INSERT 되지 못했다 → 역방향 사슬 소실(어떤 join 으로도 복원 불가).
 *
 * ## 결정 (spec §4-② amend)
 * **execution INSERT 는 order_request.status 와 독립**이어야 한다. terminal skip 은 status 재마킹 방지용
 * (CANCELLED → PARTIALLY_FILLED 덮어쓰기·completed_at 갱신 회피 — backlog §82-1)이었으나, 그 의도는
 * **status 보존**에 한정되며 **체결 기록 누락**까지 정당화하지 않는다. 따라서:
 * - CANCELLED(또는 임의 terminal) 주문이라도 KIS 일별체결 `totCcldQty` 가 **이미 반영된 체결량(currentExecutedQty)을
 *   초과**하면(`delta > 0`) 그 delta 를 order_execution 에 **기록**한다(+ position·strategy_position 반영).
 * - 단 **status 는 그대로 유지**한다(CANCELLED 면 CANCELLED). 체결 흡수와 status 전이를 분리.
 *
 * 본 함수는 그 "기록할 것인가 / status 를 건드릴 것인가" 결정만 내리는 순수 판정이다. 실제 INSERT·audit·position
 * 반영은 R2 어댑터([kr.spdaewoon.magicjar.consumer] KisExecutionBackfillService)가 본 판정에 따라 배선한다.
 *
 * ## 보수성 (자본 경로 LOCK)
 * - `delta = totCcldQty - currentExecutedQty`. delta ≤ 0 이면 [Decision.Skip](이미 반영 — over-count 0).
 * - terminal·non-terminal 무관하게 delta > 0 이면 [Decision.Record]. terminal 이면 `preserveStatus = true`
 *   (status·completed_at 불변), non-terminal 이면 `preserveStatus = false`(기존 status sync 정상 경로 통과).
 * - eventId 멱등(`existsByEventId`)·DB lock 은 R2 어댑터 책임 — 본 함수는 수량 차원 판정만(순수성 — IO 0).
 *
 * ## 순수성 (LOCK — [PositionDriftCorrection]·[LedgerAttributedDriftCorrection] 선례 1:1)
 * Spring·JPA·Clock·IO·KIS 의존 0. 입력([CancelledFillInput]) → 출력([Decision]) 결정론.
 *
 * @see StrategyPositionRebuild 진실원 완전성(본 함수가 채운 체결을 rebuild 가 흡수)
 */
object BackfillCancelledFillPolicy {
    /**
     * 단일 주문의 backfill 체결 대조 판정.
     *
     * @return
     *  - 토글 off → [Decision.Skip] (현행 동작 — terminal skip 그대로)
     *  - delta ≤ 0 → [Decision.Skip] (이미 반영 — over-count 0)
     *  - delta > 0 → [Decision.Record] (`preserveStatus` = terminal 여부)
     */
    fun decide(input: CancelledFillInput): Decision {
        if (!input.enabled) {
            return Decision.Skip
        }
        val delta = input.kisTotalCcldQty - input.currentExecutedQty
        if (delta <= 0) {
            return Decision.Skip
        }
        return Decision.Record(
            deltaQuantity = delta,
            preserveStatus = input.terminal,
        )
    }

    /** backfill 체결 대조 판정 결과 — R2 어댑터가 소비. */
    sealed interface Decision {
        /** delta ≤ 0 또는 토글 off — 흡수 안 함(현행 동작 그대로). */
        data object Skip : Decision

        /**
         * delta > 0 — order_execution 에 delta 체결 기록.
         *
         * @param deltaQuantity 기록할 추가 체결 수량(`totCcldQty - currentExecutedQty`).
         * @param preserveStatus true 면 status·completed_at 불변(terminal — 특히 CANCELLED 보존), false 면 기존 status sync 통과.
         */
        data class Record(
            val deltaQuantity: Int,
            val preserveStatus: Boolean,
        ) : Decision
    }
}

/**
 * [BackfillCancelledFillPolicy.decide] 입력 — 단일 주문의 체결 대조에 필요한 최소 상태(순수성 LOCK).
 *
 * @param kisTotalCcldQty KIS 일별체결조회의 `tot_ccld_qty`(cumulative — 그 주문의 총 체결량 권위).
 * @param currentExecutedQty 우리 order_execution 에 이미 기록된 그 주문의 체결 합(`sum(executedQty)`).
 * @param terminal 주문 status 가 terminal(FILLED·CANCELLED·REJECTED·EXPIRED)인가 — true 면 Record 시 status 보존.
 * @param enabled 체결 대조 활성 토글(ENV `magicjar.order.cancelled-fill-reconcile.enabled` 주입). false 면 현행 terminal skip 동작.
 */
data class CancelledFillInput(
    val kisTotalCcldQty: Int,
    val currentExecutedQty: Int,
    val terminal: Boolean,
    val enabled: Boolean = true,
)
