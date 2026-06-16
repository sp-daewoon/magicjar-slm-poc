package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * OrderRequestRepository port JPA 어댑터 — consumer 모듈.
 *
 * ### backlog §61 fix (2026-05-18)
 * - Phase 2 spec(`_workspace/specs/2026-05-18-to-prereq-mock-prd-verify.md`)는 api 모듈 Adapter만 LOCK했으나
 *   `OrderRouterKafkaListener`가 거주하는 consumer 모듈은 api 모듈 미의존(`consumer/build.gradle.kts` 검증)
 *   → consumer JVM bean scan 범위 외 → ObjectProvider.getIfAvailable() = null 항상 → 모든 주문 dispatch 실패.
 * - root cause: 가설 (b) — port는 domain에 있고 JPA Adapter는 api 모듈 단독, consumer scan 범위 외 (모듈 경계 정합 위반 아님 — 단지 합류 누락).
 * - fix: ConsumerCandle·KillSwitch 패턴 동일 — consumer 모듈에 자체 JPA Adapter 신설 + 동일 테이블 `order_request` 합류.
 *
 * ### 모듈 경계 정합 (ADR 0004)
 * consumer는 `domain` + `pattern-engine`만 의존 (ADR 0004 모듈 경계 LOCK 유지). api 모듈 의존 도입 0건.
 * 동일 테이블 entity 사본은 consumer/ConsumerCandle·ConsumerInstrument·KillSwitch 패턴 baseline (모듈 분리 정합).
 *
 * ### 헌법 §6 (ADR 0051) 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class ConsumerOrderRequestRepositoryAdapter(
    private val jpa: ConsumerOrderRequestJpaRepository,
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
     * ADR 0084 P0-2 — OrderReconciliationWorker cron 1분 lookup.
     *
     * V36 partial index `idx_order_request_pending_reconcile` 활용.
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
     * ADR 0109 GAP-3 — canary 일일 BUY 주문 횟수 전역 cap 카운트 (consumer 자본 path caller).
     *
     * side=BUY · status<>REJECTED 만 집계 (2026-06-12 자본 보호 역전 hotfix — 왕복 1회 = 1카운트,
     * SELL 청산은 cap 무관). order_request 단일 권위 count (Redis 미사용 — drift 회피, ADR 0109 §2-4).
     */
    @Transactional(readOnly = true)
    override fun countRealOrdersBetween(
        fromInclusive: Instant,
        toExclusive: Instant,
    ): Long = jpa.countRealOrdersBetween(TradingMode.REAL, OrderSource.EXTERNAL, fromInclusive, toExclusive)

    // --- private helper ---

    private fun mergeWsPreinsertIfPresent(
        target: ConsumerOrderRequestJpaEntity,
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

    private fun List<ConsumerOrderRequestJpaEntity>.preferCanonicalOrder(): ConsumerOrderRequestJpaEntity? =
        sortedWith(
            compareBy<ConsumerOrderRequestJpaEntity> { if (it.idempotencyKey.isWsPreinsertKey(it.kisOrderNo)) 1 else 0 }
                .thenBy { it.id ?: Long.MAX_VALUE },
        ).firstOrNull()

    private fun String.isWsPreinsertKey(kisOrderNo: String?): Boolean = kisOrderNo != null && this == "WS_PREINSERT:$kisOrderNo"

    private fun toEntity(domain: OrderRequest): ConsumerOrderRequestJpaEntity =
        ConsumerOrderRequestJpaEntity(
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
            kisFwdgOrgno = domain.kisFwdgOrgno,
            rejectReason = domain.rejectReason,
            requestedAt = domain.requestedAt,
            acknowledgedAt = domain.acknowledgedAt,
            completedAt = domain.completedAt,
            // hotfix 2026-05-20 — T-MAN Phase 1 PR #510 회귀 매핑 (MANUAL 주문 보존 의무)
            source = domain.source,
            // hotfix 2026-06-11 — D2 P2-⑥ 회귀 매핑 (발사 출처 키 사슬 영속 — 누락 시 order_trigger·trigger_ref_id NULL).
            orderTrigger = domain.orderTrigger,
            triggerRefId = domain.triggerRefId,
        )

    private fun toDomain(entity: ConsumerOrderRequestJpaEntity): OrderRequest =
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
            // hotfix 2026-05-20 — source 매핑 추가 (audit 정합 의무)
            source = safeEnum<OrderSource>(entity.source.name) ?: OrderSource.AUTO,
            // hotfix 2026-06-11 — D2 P2-⑥ 회귀 읽기 매핑 (rebuild·signal-trace 가 trigger 키로 귀속 판정).
            orderTrigger = entity.orderTrigger,
            triggerRefId = entity.triggerRefId,
        )

    private inline fun <reified T : Enum<T>> safeEnum(value: String): T? = runCatching { enumValueOf<T>(value) }.getOrNull()
}
