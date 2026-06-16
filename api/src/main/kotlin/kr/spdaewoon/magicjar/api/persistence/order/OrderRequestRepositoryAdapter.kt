package kr.spdaewoon.magicjar.api.persistence.order

import jakarta.persistence.criteria.Predicate
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * OrderRequestRepository port JPA 어댑터 — Flyway V20 정합 (ADR 0068 §15-2 LOCK).
 *
 * 헌법 §6 (ADR 0051) 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class OrderRequestRepositoryAdapter(
    private val jpa: OrderRequestJpaRepository,
) : OrderRequestRepository {
    @Transactional
    override fun save(request: OrderRequest): OrderRequest = toDomain(jpa.save(toEntity(request)))

    @Transactional(readOnly = true)
    override fun findById(id: Long): OrderRequest? = jpa.findById(id).map { toDomain(it) }.orElse(null)

    @Transactional(readOnly = true)
    override fun findByKisOrderNo(kisOrderNo: String): OrderRequest? =
        jpa
            .findAllByKisOrderNoOrderByIdAsc(kisOrderNo)
            .preferCanonicalOrder()
            ?.let { toDomain(it) }

    @Transactional(readOnly = true)
    override fun findByIdempotencyKey(idempotencyKey: String): OrderRequest? =
        jpa.findByIdempotencyKey(idempotencyKey)?.let { toDomain(it) }

    @Transactional(readOnly = true)
    override fun findOpenByUserId(userId: String): List<OrderRequest> = jpa.findOpenByUserId(userId).map { toDomain(it) }

    @Transactional(readOnly = true)
    override fun findLastFilledAt(userId: String): Instant? = jpa.findLastFilledAtByUserId(userId)

    /**
     * ADR 0084 P0-2 — OrderReconciliationWorker cron 1분 lookup (api 모듈 공통 구현).
     *
     * 본 어댑터는 OrderQueryService (api) + admin 화면 등 다른 caller도 활용 가능.
     * 자본 path worker는 consumer 모듈 [kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestRepositoryAdapter]
     * 가 사용 (api 미의존이므로 자체 어댑터 합류).
     */
    @Transactional(readOnly = true)
    override fun findByStatusAndRequestedAtBefore(
        status: OrderStatus,
        cutoff: Instant,
        limit: Int,
    ): List<OrderRequest> =
        jpa
            .findByStatusAndRequestedAtBefore(status, cutoff, PageRequest.of(0, limit))
            .map { toDomain(it) }

    @Transactional(readOnly = true)
    override fun findOpenBuyLimitBefore(
        cutoff: Instant,
        limit: Int,
    ): List<OrderRequest> =
        jpa
            .findOpenBuyLimitBefore(cutoff, PageRequest.of(0, limit))
            .map { toDomain(it) }

    @Transactional
    override fun updateStatus(
        id: Long,
        status: OrderStatus,
        rejectReason: String?,
    ): OrderRequest? {
        val entity = jpa.findById(id).orElse(null) ?: return null
        entity.status = status
        entity.rejectReason = rejectReason
        return toDomain(jpa.save(entity))
    }

    @Transactional
    override fun updateKisAck(
        id: Long,
        kisOrderNo: String,
        kisFwdgOrgno: String,
        acknowledgedAt: Instant,
    ): OrderRequest? {
        val entity = jpa.findById(id).orElse(null) ?: return null
        mergeWsPreinsertIfPresent(target = entity, kisOrderNo = kisOrderNo)
        entity.kisOrderNo = kisOrderNo
        entity.kisFwdgOrgno = kisFwdgOrgno
        entity.acknowledgedAt = acknowledgedAt
        entity.status = OrderStatus.ACK
        return toDomain(jpa.save(entity))
    }

    @Transactional
    override fun markCompleted(
        id: Long,
        completedAt: Instant,
        finalStatus: OrderStatus,
    ): OrderRequest? {
        val entity = jpa.findById(id).orElse(null) ?: return null
        entity.status = finalStatus
        entity.completedAt = completedAt
        return toDomain(jpa.save(entity))
    }

    /**
     * ADR 0109 GAP-3 — canary 일일 BUY 주문 횟수 전역 cap 카운트 (port 정합 — api 모듈 공통 구현).
     *
     * 자본 path 게이트 caller는 consumer 모듈이나, port 구현 의무로 api 어댑터도 동일 동작 보장.
     * side=BUY · status<>REJECTED 만 집계 (2026-06-12 자본 보호 역전 hotfix — 왕복 1회 = 1카운트).
     * order_request 단일 권위 count (Redis 미사용 — drift 회피, ADR 0109 §2-4).
     */
    @Transactional(readOnly = true)
    override fun countRealOrdersBetween(
        fromInclusive: Instant,
        toExclusive: Instant,
    ): Long = jpa.countRealOrdersBetween(TradingMode.REAL, OrderSource.EXTERNAL, fromInclusive, toExclusive)

    /**
     * 사용자 시계열 주문 페이징 — OrderQueryService에서 직접 호출.
     */
    @Transactional(readOnly = true)
    fun findByUserAndRange(
        userId: String,
        from: Instant?,
        to: Instant?,
        statuses: List<OrderStatus>?,
        source: OrderSource?,
        side: OrderSide?,
        code: String?,
        page: Int,
        size: Int,
    ): org.springframework.data.domain.Page<OrderRequest> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"))
        val fromTs = from ?: Instant.EPOCH
        val toTs = to ?: Instant.now().plusSeconds(86400)
        val codeQuery =
            code
                ?.trim()
                ?.replace("%", "")
                ?.replace("_", "")
                ?.takeIf { it.isNotBlank() }

        val spec =
            Specification<OrderRequestJpaEntity> { root, _, cb ->
                val predicates = mutableListOf<Predicate>()
                predicates += cb.equal(root.get<String>("userId"), userId)
                predicates += cb.between(root.get<Instant>("requestedAt"), fromTs, toTs)

                if (!statuses.isNullOrEmpty()) {
                    predicates += root.get<OrderStatus>("status").`in`(statuses)
                }
                source?.let {
                    predicates += cb.equal(root.get<OrderSource>("source"), it)
                }
                side?.let {
                    predicates += cb.equal(root.get<OrderSide>("side"), it)
                }
                codeQuery?.let {
                    predicates += cb.like(root.get<String>("code"), "%$it%")
                }

                cb.and(*predicates.toTypedArray())
            }

        return jpa.findAll(spec, pageable).map { toDomain(it) }
    }

    /**
     * ADR 0115 §2-B 키 사슬 추적성 (불변식 #4) — 시그널 → 파생 주문 역추적 (read-only).
     *
     * SignalTraceService에서 호출 — STRATEGY_SIGNAL trigger + triggerRefId == signalId 주문.
     */
    @Transactional(readOnly = true)
    fun findBySignalTrigger(
        userId: String,
        signalId: Long,
    ): List<OrderRequest> = jpa.findByUserIdAndSignalTrigger(userId, signalId).map { toDomain(it) }

    // --- private helper ---

    private fun mergeWsPreinsertIfPresent(
        target: OrderRequestJpaEntity,
        kisOrderNo: String,
    ) {
        val targetId = target.id ?: return
        val existing =
            jpa
                .findAllByKisOrderNoOrderByIdAsc(kisOrderNo)
                .firstOrNull { it.id != targetId }
                ?: return

        if (!existing.idempotencyKey.isWsPreinsertKey(kisOrderNo)) {
            throw IllegalStateException(
                "kis_order_no already bound to order_request id=${existing.id} (target=$targetId, kisOrderNo=$kisOrderNo)",
            )
        }

        val sourceId = existing.id ?: return
        jpa.reassignExecutions(sourceId = sourceId, targetId = targetId)
        jpa.reassignAuditEvents(sourceId = sourceId, targetId = targetId)
        jpa.delete(existing)
    }

    private fun List<OrderRequestJpaEntity>.preferCanonicalOrder(): OrderRequestJpaEntity? =
        sortedWith(
            compareBy<OrderRequestJpaEntity> { if (it.idempotencyKey.isWsPreinsertKey(it.kisOrderNo)) 1 else 0 }
                .thenBy { it.id ?: Long.MAX_VALUE },
        ).firstOrNull()

    private fun String.isWsPreinsertKey(kisOrderNo: String?): Boolean = kisOrderNo != null && this == "WS_PREINSERT:$kisOrderNo"

    private fun toEntity(domain: OrderRequest): OrderRequestJpaEntity =
        OrderRequestJpaEntity(
            id = domain.id,
            signalId = domain.signalId,
            strategyId = domain.strategyId,
            userId = domain.userId,
            code = domain.code,
            side = domain.side,
            qty = domain.quantity.toBigDecimal(),
            priceType = domain.priceType,
            limitPrice = domain.limitPrice,
            status = domain.status,
            mode = domain.mode,
            idempotencyKey = domain.idempotencyKey,
            kisOrderNo = domain.kisOrderNo,
            rejectReason = domain.rejectReason,
            requestedAt = domain.requestedAt,
            acknowledgedAt = domain.acknowledgedAt,
            completedAt = domain.completedAt,
            source = domain.source,
            // ADR 0115 §2-P2-⑥ — 발사 출처 키 사슬 영속
            orderTrigger = domain.orderTrigger,
            triggerRefId = domain.triggerRefId,
        )

    private fun toDomain(entity: OrderRequestJpaEntity): OrderRequest =
        OrderRequest(
            id = entity.id,
            signalId = entity.signalId,
            userId = entity.userId,
            strategyId = entity.strategyId,
            code = entity.code,
            side = safeEnum<OrderSide>(entity.side.name) ?: OrderSide.BUY,
            priceType = safeEnum<OrderPriceType>(entity.priceType.name) ?: OrderPriceType.MARKET,
            quantity = entity.qty.toInt(),
            limitPrice = entity.limitPrice,
            status = safeEnum<OrderStatus>(entity.status.name) ?: OrderStatus.REQUESTED,
            mode = safeEnum<TradingMode>(entity.mode.name) ?: TradingMode.MOCK,
            idempotencyKey = entity.idempotencyKey,
            kisOrderNo = entity.kisOrderNo,
            kisFwdgOrgno = entity.kisFwdgOrgno,
            rejectReason = entity.rejectReason,
            requestedAt = entity.requestedAt,
            acknowledgedAt = entity.acknowledgedAt,
            completedAt = entity.completedAt,
            source = safeEnum<OrderSource>(entity.source.name) ?: OrderSource.AUTO,
            // ADR 0115 §2-P2-⑥ — 발사 출처 키 사슬 복원 (legacy 미분류는 null 유지 → 읽기 경로 fallback)
            orderTrigger = entity.orderTrigger,
            triggerRefId = entity.triggerRefId,
        )

    private inline fun <reified T : Enum<T>> safeEnum(value: String): T? = runCatching { enumValueOf<T>(value) }.getOrNull()
}
