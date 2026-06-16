package kr.spdaewoon.magicjar.api.persistence.position

import kr.spdaewoon.magicjar.domain.reconcile.InternalHolding
import kr.spdaewoon.magicjar.domain.reconcile.port.InternalPositionQueryPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [InternalPositionQueryPort] JPA 어댑터 — T-RECON Phase 2 (ADR 0041·0070 §2-2).
 *
 * reconcile 비교용 내부 DB position 조회.
 * 기존 [PositionJpaRepository] (Flyway V18, ADR 0067 §5-4 G-1) reuse.
 *
 * ### 조회 기준
 * - qty > 0 (청산 포지션 제외 — reconcile 비교는 현재 보유 중인 종목만)
 * - userId 격리 (multi-tenant — ADR 0010 정합)
 *
 * 헌법 §6 (ADR 0051): helper 메서드는 이 클래스 private fun 위치.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class InternalPositionQueryAdapter(
    private val positionJpaRepository: PositionJpaRepository,
) : InternalPositionQueryPort {
    private val log = LoggerFactory.getLogger(InternalPositionQueryAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findCurrentByUser(userId: String): List<InternalHolding> {
        val entities = positionJpaRepository.findByUserIdAndQtyGreaterThan(userId, 0L)
        log.debug(
            "InternalPositionQueryAdapter — userId={}**** holdings={}건",
            maskUserId(userId),
            entities.size,
        )
        return entities.map { toHolding(it) }
    }

    // --- private helper ---

    private fun toHolding(entity: PositionJpaEntity): InternalHolding =
        InternalHolding(
            symbol = entity.code,
            quantity = entity.qty.toInt(),
            avgPrice = entity.avgPrice,
        )

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) else userId
}
