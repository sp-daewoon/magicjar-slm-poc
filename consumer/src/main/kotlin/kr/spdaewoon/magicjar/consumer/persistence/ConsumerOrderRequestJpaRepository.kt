package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * order_request Spring Data JPA Repository — consumer 모듈.
 *
 * 헌법 §6 (ADR 0051): Repository interface 내부 body 가진 fun 0건.
 * 헬퍼는 [ConsumerOrderRequestRepositoryAdapter] private fun 위치.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface ConsumerOrderRequestJpaRepository : JpaRepository<ConsumerOrderRequestJpaEntity, Long> {
    fun findByKisOrderNo(kisOrderNo: String): ConsumerOrderRequestJpaEntity?

    fun findAllByKisOrderNoOrderByIdAsc(kisOrderNo: String): List<ConsumerOrderRequestJpaEntity>

    fun findByIdempotencyKey(idempotencyKey: String): ConsumerOrderRequestJpaEntity?

    @Query(
        "SELECT e FROM ConsumerOrderRequestJpaEntity e WHERE e.userId = :userId " +
            "AND e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED', 'PENDING_RECONCILE')",
    )
    fun findOpenByUserId(
        @Param("userId") userId: String,
    ): List<ConsumerOrderRequestJpaEntity>

    @Query(
        "SELECT DISTINCT e.code FROM ConsumerOrderRequestJpaEntity e " +
            "WHERE e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED', 'PENDING_RECONCILE')",
    )
    fun findOpenCodes(): List<String>

    /**
     * ADR 0084 P0-2 — OrderReconciliationWorker cron 1분 lookup.
     *
     * V36 partial index `idx_order_request_pending_reconcile` (PENDING_RECONCILE 상태 한정) 활용.
     * Pageable로 limit 적용 — 한 cron tick에서 처리할 row 수 제한.
     */
    @Query(
        "SELECT e FROM ConsumerOrderRequestJpaEntity e " +
            "WHERE e.status = :status AND e.requestedAt < :cutoff " +
            "ORDER BY e.requestedAt ASC",
    )
    fun findByStatusAndRequestedAtBefore(
        @Param("status") status: OrderStatus,
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable,
    ): List<ConsumerOrderRequestJpaEntity>

    /**
     * ADR 0114 §3-2 — EntryTimeoutCancelWorker 미체결 진입 취소 lookup.
     *
     * BUY LIMIT 미종결 주문(REQUESTED·ACK·PARTIALLY_FILLED) 중 requestedAt < cutoff. oldest-first.
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT e FROM ConsumerOrderRequestJpaEntity e " +
            "WHERE e.side = kr.spdaewoon.magicjar.domain.order.execution.OrderSide.BUY " +
            "AND e.priceType = kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType.LIMIT " +
            "AND e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED') " +
            "AND e.requestedAt < :cutoff " +
            "ORDER BY e.requestedAt ASC",
    )
    fun findOpenBuyLimitBefore(
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable,
    ): List<ConsumerOrderRequestJpaEntity>

    /**
     * KillSwitch race-safe guard B (Grace Window) — userId의 마지막 FILLED 시각.
     *
     * `completedAt MAX WHERE status=FILLED`. 한 건도 없으면 null.
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT MAX(e.completedAt) FROM ConsumerOrderRequestJpaEntity e " +
            "WHERE e.userId = :userId AND e.status = kr.spdaewoon.magicjar.domain.order.execution.OrderStatus.FILLED",
    )
    fun findLastFilledAtByUserId(
        @Param("userId") userId: String,
    ): Instant?

    /**
     * ADR 0109 GAP-3 — canary 일일 REAL 주문 횟수 전역 cap 카운트.
     *
     * `mode = :mode` AND `source <> :excludedSource` AND `side = BUY` AND `status <> REJECTED`
     * AND `requestedAt ∈ [from, to)` 건수.
     * caller(adapter)가 REAL + EXTERNAL 제외 + KST 당일 UTC 경계 전달.
     *
     * ### EXTERNAL 제외 (2026-06-05 자본 path fix)
     * 외부(HTS·MTS) 직접 주문을 흡수한 EXTERNAL row 는 우리 자동매매 예산이 아니므로 canary cap 카운트에서 제외.
     * 사용자가 HTS 로 직접 매수해도 우리 자동매매 일일 cap 이 잠식되면 안 됨.
     *
     * ### BUY-only + REJECTED 제외 (2026-06-12 자본 보호 역전 hotfix · 최고 관리자 결재)
     * 최고 관리자 2026-06-12 직접 결재: "하루 주문 제한을 매수만 포함해야지 매도까지 세려고 만든 건 아니야.
     * 5개 제한이면 1개 종목을 사고팔면 1번 카운팅." → 왕복 1회 = 1카운트 의미로 `side = BUY` 만 집계.
     * SELL(익절·손절·DSL 청산)은 cap 무관 통과해야 보유 포지션 보호가 가능 — 그렇지 않으면 매수 cap 도달 후
     * 익절·손절 LIMIT 이 `CANARY_DAILY_ORDER_CAP` 으로 거부돼 자본 보호 역전(2026-06-12 라이브 incident).
     * REJECTED 제외: 게이트 거부 BUY 도 status=REJECTED row 로 INSERT 되므로(handleRejected), status 무관 집계 시
     * 거부된 주문이 cap 슬롯을 잠식해 동일 역전이 재발한다 → "체결 경로에 실제 들어간 BUY" 만 센다.
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT COUNT(e) FROM ConsumerOrderRequestJpaEntity e " +
            "WHERE e.mode = :mode AND e.source <> :excludedSource " +
            "AND e.side = kr.spdaewoon.magicjar.domain.order.execution.OrderSide.BUY " +
            "AND e.status <> kr.spdaewoon.magicjar.domain.order.execution.OrderStatus.REJECTED " +
            "AND e.requestedAt >= :fromInclusive AND e.requestedAt < :toExclusive",
    )
    fun countRealOrdersBetween(
        @Param("mode") mode: TradingMode,
        @Param("excludedSource") excludedSource: OrderSource,
        @Param("fromInclusive") fromInclusive: Instant,
        @Param("toExclusive") toExclusive: Instant,
    ): Long

    /**
     * ADR 0115 §P1-1 amend (2026-06-10) — strategy_position **부족 방향** drift 의 귀속 ledger 집계.
     *
     * 동일 user+code 의 **우리 자동매매 전략(strategyId≠0·source=AUTO)** 이 체결시킨 order_execution 을
     * 전략별·side 별로 묶어 `SUM(executedQty)` 를 반환한다. 외부(EXTERNAL)·MANUAL(strategyId=0) 체결은 제외 —
     * "없는 물량 인식" risk 0 의 귀속 근거([kr.spdaewoon.magicjar.strategy.integrity.LedgerAttributedDriftCorrection] §보수성 가드 1).
     *
     * hotfix 2026-06-11 (D1): `r.mode = :mode` 추가 — position 권위(UQ=(user,code,source))는 mode 별로 분리돼
     * REAL drift 정합은 REAL position 과 비교한다. 그런데 ledger 합산이 mode 무차원이면 MOCK+REAL 체결을 함께 더해
     * (예: rebuildTotal=10 vs REAL authority=1) 교차검산이 영영 깨져 drift 가 자기치유를 못 만난다. 권위와 동일
     * mode 의 체결만 집계해 비교축을 정합시킨다.
     *
     * order_execution 은 order_request 에 JPA 관계가 없으므로 `e.orderId = r.id` 명시 조인(implicit join + predicate).
     * caller([ConsumerExecutionStrategyPositionAdapter.reconcileDrift])가 (strategyId,side)→qty 로 net BUY-SELL 산정.
     *
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     *
     * @return (strategyId, side, qtySum) 행 목록. 체결 없으면 빈 목록.
     */
    @Query(
        "SELECT r.strategyId AS strategyId, r.side AS side, " +
            "COALESCE(SUM(e.executedQty), 0) AS qtySum, " +
            "COALESCE(SUM(e.executedQty * e.executedPrice), 0) AS notionalSum " +
            "FROM ConsumerOrderRequestJpaEntity r, ConsumerOrderExecutionJpaEntity e " +
            "WHERE e.orderId = r.id " +
            "AND r.userId = :userId AND r.code = :code " +
            "AND r.strategyId <> 0 " +
            "AND r.mode = :mode " +
            "AND r.source = kr.spdaewoon.magicjar.domain.order.execution.OrderSource.AUTO " +
            "GROUP BY r.strategyId, r.side",
    )
    fun aggregateFilledByStrategyAndSide(
        @Param("userId") userId: String,
        @Param("code") code: String,
        @Param("mode") mode: TradingMode,
    ): List<StrategyLedgerRow>

    /**
     * ADR 0115 §2-P2-③ R1 (2026-06-10) — 단일 전략 부족 완화 evidence 용 **주문 차원** FILLED 순매수량.
     *
     * `aggregateFilledByStrategyAndSide`(execution 차원 — 부분체결 execution row 일부 누락 시 under-count)와 달리,
     * **order_request 차원**으로 종결된 주문(FILLED·PARTIALLY_FILLED)의 그 주문에 귀속된 체결 합을 전략별·side 별로
     * 집계한다. execution-단위 누락이 있어도 order_request 가 KIS 권위로 종결됐다면(FILLED) 그 주문의 체결 의도가
     * 보존되므로, 단일 전략 보유의 "주문 차원 evidence"로 쓴다([SingleStrategyOrderEvidence.netFilledFromOrders]).
     *
     * R1 구현은 종결 주문에 귀속된 order_execution 합을 사용한다(execution 차원과 동일 집계이나, status FILLED 필터로
     * 종결성 evidence 부여). 외부(EXTERNAL)·MANUAL(strategyId=0) 제외 — 단일 자동매매 전략 귀속 근거.
     *
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     *
     * @return (strategyId, side, qtySum) 행 목록. 종결 체결 없으면 빈 목록.
     */
    @Query(
        "SELECT r.strategyId AS strategyId, r.side AS side, " +
            "COALESCE(SUM(e.executedQty), 0) AS qtySum, " +
            "COALESCE(SUM(e.executedQty * e.executedPrice), 0) AS notionalSum " +
            "FROM ConsumerOrderRequestJpaEntity r, ConsumerOrderExecutionJpaEntity e " +
            "WHERE e.orderId = r.id " +
            "AND r.userId = :userId AND r.code = :code " +
            "AND r.strategyId <> 0 " +
            "AND r.mode = :mode " +
            "AND r.source = kr.spdaewoon.magicjar.domain.order.execution.OrderSource.AUTO " +
            "AND r.status IN (" +
            "kr.spdaewoon.magicjar.domain.order.execution.OrderStatus.FILLED, " +
            "kr.spdaewoon.magicjar.domain.order.execution.OrderStatus.PARTIALLY_FILLED) " +
            "GROUP BY r.strategyId, r.side",
    )
    fun aggregateOrderFilledNetByStrategy(
        @Param("userId") userId: String,
        @Param("code") code: String,
        @Param("mode") mode: TradingMode,
    ): List<StrategyLedgerRow>

    /**
     * ADR 0115 §2-P2 R1 (2026-06-10) — strategy_position **rebuild**(대안 B) 입력 execution 목록.
     *
     * 동일 user+code 의 **우리 자동매매 전략(strategyId≠0·source=AUTO)** 이 체결시킨 order_execution 을
     * **체결 1건(분할 단위) 단위**로 반환한다([aggregateFilledByStrategyAndSide] 의 GROUP BY 집계와 달리 행 보존).
     * [kr.spdaewoon.magicjar.strategy.integrity.StrategyPositionRebuild.rebuild] 가 이 목록을 eventId distinct →
     * 시간순 fold 하여 strategy_position 을 **결정론적으로 전량 재계산**한다(부분체결 N건 정확 N회 귀속 — G4 직격).
     *
     * order_execution 은 order_request 에 JPA 관계가 없으므로 `e.orderId = r.id` 명시 조인(implicit join + predicate).
     * 외부(EXTERNAL)·MANUAL(strategyId=0) 체결은 제외 — rebuild 는 "우리 전략이 체결시킨 순보유량" 만 재계산.
     * hotfix 2026-06-11 (D1): `r.mode = :mode` — REAL rebuild 는 REAL 체결만 fold(교차검산 축=REAL position 정합).
     *   과거 mode 무차원이라 MOCK 과거 체결이 혼입(rebuildTotal=10 vs REAL authority=1) → 교차검산 영구 실패였다.
     *
     * 헌법 §6 — interface body 0건, @Query 만 사용. 시간순(executedAt) 정렬은 caller 도 재정렬하나 1차 정렬로 결정론 보강.
     *
     * @return execution 1건당 [StrategyRebuildExecutionRow] 행 목록. 체결 없으면 빈 목록.
     */
    @Query(
        "SELECT e.eventId AS eventId, e.orderId AS orderId, r.strategyId AS strategyId, " +
            "r.side AS side, e.executedQty AS executedQty, e.executedPrice AS executedPrice, " +
            "e.executedAt AS executedAt, r.signalId AS signalId " +
            "FROM ConsumerOrderRequestJpaEntity r, ConsumerOrderExecutionJpaEntity e " +
            "WHERE e.orderId = r.id " +
            "AND r.userId = :userId AND r.code = :code " +
            "AND r.strategyId <> 0 " +
            "AND r.mode = :mode " +
            "AND r.source = kr.spdaewoon.magicjar.domain.order.execution.OrderSource.AUTO " +
            "ORDER BY e.executedAt ASC, e.id ASC",
    )
    fun findRebuildExecutionsByUserAndCode(
        @Param("userId") userId: String,
        @Param("code") code: String,
        @Param("mode") mode: TradingMode,
    ): List<StrategyRebuildExecutionRow>

    @Modifying
    @Query(
        value = "UPDATE order_execution SET order_id = :targetId WHERE order_id = :sourceId",
        nativeQuery = true,
    )
    fun reassignExecutions(
        @Param("sourceId") sourceId: Long,
        @Param("targetId") targetId: Long,
    ): Int

    @Modifying
    @Query(
        value = "UPDATE order_audit_event SET order_id = :targetId WHERE order_id = :sourceId",
        nativeQuery = true,
    )
    fun reassignAuditEvents(
        @Param("sourceId") sourceId: Long,
        @Param("targetId") targetId: Long,
    ): Int
}

/**
 * [ConsumerOrderRequestJpaRepository.aggregateFilledByStrategyAndSide] 행 — Spring Data interface projection.
 *
 * (strategyId, side) 그룹의 체결 수량 합. caller 가 BUY-SELL net 으로 strategy_position 부족 보정 귀속 근거 산정.
 */
interface StrategyLedgerRow {
    val strategyId: Long
    val side: kr.spdaewoon.magicjar.domain.order.execution.OrderSide
    val qtySum: java.math.BigDecimal

    /** `SUM(executedQty * executedPrice)` — BUY 행에서 가중평단(notional/qty) 산정용. */
    val notionalSum: java.math.BigDecimal
}

/**
 * [ConsumerOrderRequestJpaRepository.findRebuildExecutionsByUserAndCode] 행 — Spring Data interface projection.
 *
 * order_execution(진실원) 1행 + 귀속 order_request 메타(strategyId·side·signalId)의 join 투영.
 * caller([kr.spdaewoon.magicjar.consumer.reconcile] reconcile 경로)가
 * [kr.spdaewoon.magicjar.strategy.integrity.RebuildExecution] DTO 로 매핑해 rebuild 에 넘긴다.
 */
interface StrategyRebuildExecutionRow {
    /** 체결 멱등 키(`order_execution.event_id`). null 가능(legacy row) — caller 가 fallback 키 합성. */
    val eventId: String?
    val orderId: Long
    val strategyId: Long
    val side: kr.spdaewoon.magicjar.domain.order.execution.OrderSide

    /** 이 체결 1건의 분할 단위 수량(cumulative 아님). */
    val executedQty: java.math.BigDecimal
    val executedPrice: java.math.BigDecimal
    val executedAt: java.time.Instant

    /** 귀속 시그널 id(부분익절 단계 `PARTIAL_TAKE_PROFIT#k` 해석용 — 0 sentinel 가능). */
    val signalId: Long
}
