package kr.spdaewoon.magicjar.api.persistence.position

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.position.TradingMode
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlinx.datetime.Instant as KInstant

/**
 * RiskPositionPort JPA 어댑터 — Flyway V18 정합 (ADR 0067 §5-4 G-1 + ADR 0042 정합).
 *
 * 헌법 §6 (ADR 0051) 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 *
 * ### ADR 0042 vs ADR 0067 port 분리 정합
 * [kr.spdaewoon.magicjar.domain.position.port.PositionRepository] — KIS 잔고 부트스트랩 upsert 전용.
 * [RiskPositionPort] — risk 평가용 현재 포지션 조회 전용.
 * 양 port 별도 어댑터 (기존 PositionRepository 어댑터와 충돌 0).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class PositionJpaAdapter(
    private val positionJpaRepository: PositionJpaRepository,
) : RiskPositionPort {
    @Transactional(readOnly = true)
    override fun findCurrentByUser(userId: String): List<Position> =
        positionJpaRepository
            .findByUserIdAndQtyGreaterThan(userId, 0L)
            .map { toDomain(it) }

    @Transactional(readOnly = true)
    override fun countCurrentByUser(userId: String): Int = positionJpaRepository.countByUserIdAndQtyGreaterThan(userId, 0L)

    @Transactional(readOnly = true)
    override fun sumDailyRealizedPnl(
        userId: String,
        kstMidnight: KInstant,
    ): BigDecimal = positionJpaRepository.sumClosedPnlSince(userId, kstMidnight.toJavaInstant())

    /**
     * source 필터 포지션 전체 조회.
     * PositionQueryService에서 직접 호출 — port 외 추가 메서드.
     */
    @Transactional(readOnly = true)
    fun findByUserAndSource(
        userId: String,
        source: String?,
    ): List<Position> =
        if (source != null) {
            positionJpaRepository.findByUserIdAndSource(userId, source).map { toDomain(it) }
        } else {
            positionJpaRepository.findByUserIdAndQtyGreaterThan(userId, 0L).map { toDomain(it) }
        }

    /**
     * id로 단건 조회.
     * PositionQueryService에서 직접 호출.
     */
    @Transactional(readOnly = true)
    fun findById(id: Long): Position? = positionJpaRepository.findById(id).map { toDomain(it) }.orElse(null)

    // --- private helper ---

    private fun toDomain(entity: PositionJpaEntity): Position =
        Position(
            id = entity.id,
            userId = entity.userId,
            code = entity.code,
            quantity = entity.qty.toInt(),
            avgPrice = entity.avgPrice,
            updatedAt = entity.createdAt.toKotlinInstant(),
        )

    private fun safeMode(source: String): TradingMode = runCatching { TradingMode.valueOf(source) }.getOrDefault(TradingMode.MOCK)
}
