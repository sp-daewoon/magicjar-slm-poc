package kr.spdaewoon.magicjar.api.persistence.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * order_request Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [OrderRequestRepositoryAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface OrderRequestJpaRepository :
    JpaRepository<OrderRequestJpaEntity, Long>,
    JpaSpecificationExecutor<OrderRequestJpaEntity> {
    fun findByKisOrderNo(kisOrderNo: String): OrderRequestJpaEntity?

    fun findAllByKisOrderNoOrderByIdAsc(kisOrderNo: String): List<OrderRequestJpaEntity>

    fun findByIdempotencyKey(idempotencyKey: String): OrderRequestJpaEntity?

    fun findByUserIdOrderByRequestedAtDesc(
        userId: String,
        pageable: Pageable,
    ): Page<OrderRequestJpaEntity>

    @Query(
        "SELECT e FROM OrderRequestJpaEntity e WHERE e.userId = :userId " +
            "AND e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED', 'PENDING_RECONCILE')",
    )
    fun findOpenByUserId(
        @Param("userId") userId: String,
    ): List<OrderRequestJpaEntity>

    fun findByUserIdAndStatusInAndRequestedAtBetween(
        userId: String,
        statuses: List<OrderStatus>,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<OrderRequestJpaEntity>

    fun findByUserIdAndRequestedAtBetween(
        userId: String,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<OrderRequestJpaEntity>

    /**
     * ADR 0084 P0-2 — PENDING_RECONCILE 검증 worker lookup (consumer/api 모듈 공통).
     *
     * V36 partial index `idx_order_request_pending_reconcile` 활용.
     */
    @Query(
        "SELECT e FROM OrderRequestJpaEntity e " +
            "WHERE e.status = :status AND e.requestedAt < :cutoff " +
            "ORDER BY e.requestedAt ASC",
    )
    fun findByStatusAndRequestedAtBefore(
        @Param("status") status: OrderStatus,
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable,
    ): List<OrderRequestJpaEntity>

    /**
     * ADR 0114 §3-2 — 미체결 진입(BUY LIMIT) 취소 lookup (api 모듈 공통 구현 — admin/조회 caller 재사용).
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT e FROM OrderRequestJpaEntity e " +
            "WHERE e.side = kr.spdaewoon.magicjar.domain.order.execution.OrderSide.BUY " +
            "AND e.priceType = kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType.LIMIT " +
            "AND e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED') " +
            "AND e.requestedAt < :cutoff " +
            "ORDER BY e.requestedAt ASC",
    )
    fun findOpenBuyLimitBefore(
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable,
    ): List<OrderRequestJpaEntity>

    /**
     * KillSwitch race-safe guard B (Grace Window) — userId의 마지막 FILLED 시각.
     *
     * `completedAt MAX WHERE status=FILLED`. 한 건도 없으면 null.
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT MAX(e.completedAt) FROM OrderRequestJpaEntity e " +
            "WHERE e.userId = :userId AND e.status = kr.spdaewoon.magicjar.domain.order.execution.OrderStatus.FILLED",
    )
    fun findLastFilledAtByUserId(
        @Param("userId") userId: String,
    ): Instant?

    /**
     * ADR 0109 GAP-3 — canary 일일 REAL 주문 횟수 전역 cap 카운트 (consumer/api 모듈 공통 port 구현).
     *
     * `mode = :mode` AND `source <> :excludedSource` AND `side = BUY` AND `status <> REJECTED`
     * AND `requestedAt ∈ [from, to)` 건수.
     * caller(adapter)가 REAL + EXTERNAL 제외 + KST 당일 UTC 경계 전달.
     *
     * ### EXTERNAL 제외 (2026-06-05 자본 path fix)
     * 외부(HTS·MTS) 직접 주문을 흡수한 EXTERNAL row 는 우리 자동매매 예산이 아니므로 canary cap 카운트에서 제외.
     *
     * ### BUY-only + REJECTED 제외 (2026-06-12 자본 보호 역전 hotfix · 최고 관리자 결재)
     * 최고 관리자 2026-06-12 직접 결재: "하루 주문 제한을 매수만 포함해야지 매도까지 세려고 만든 건 아니야.
     * 5개 제한이면 1개 종목을 사고팔면 1번 카운팅." → 왕복 1회 = 1카운트 의미로 `side = BUY` 만 집계.
     * SELL(익절·손절·DSL 청산)은 cap 무관 통과 — 매수 cap 도달 후 청산이 거부되는 자본 보호 역전 차단.
     * REJECTED 제외: 게이트 거부 BUY 도 status=REJECTED row 로 INSERT 되므로 "체결 경로에 실제 들어간 BUY" 만 센다.
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT COUNT(e) FROM OrderRequestJpaEntity e " +
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
     * ADR 0115 §2-B 키 사슬 추적성 (불변식 #4) — 시그널 → 파생 주문 역추적 (read-only).
     *
     * 주어진 시그널 id(triggerRefId)와 STRATEGY_SIGNAL trigger로 발사된 주문을 시계열 조회.
     * `GET /api/signals/{id}/trace` 드릴다운에서 "이 사인이 낸 주문 N건"을 잇는다.
     * userId 격리 (ADR 0010) — 다른 사용자 row 반환 안 함.
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT e FROM OrderRequestJpaEntity e " +
            "WHERE e.userId = :userId AND e.triggerRefId = :triggerRefId " +
            "AND e.orderTrigger = kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger.STRATEGY_SIGNAL " +
            "ORDER BY e.requestedAt ASC",
    )
    fun findByUserIdAndSignalTrigger(
        @Param("userId") userId: String,
        @Param("triggerRefId") triggerRefId: Long,
    ): List<OrderRequestJpaEntity>

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
