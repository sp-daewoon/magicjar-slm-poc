package kr.spdaewoon.magicjar.api.persistence.position

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant

/**
 * position Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [PositionJpaAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface PositionJpaRepository : JpaRepository<PositionJpaEntity, Long> {
    /** qty > 0인 현재 보유 포지션 — RiskPositionPort.findCurrentByUser 기반. */
    fun findByUserIdAndQtyGreaterThan(
        userId: String,
        minQty: Long,
    ): List<PositionJpaEntity>

    /** qty > 0인 현재 포지션 수 — RiskPositionPort.countCurrentByUser 기반. */
    fun countByUserIdAndQtyGreaterThan(
        userId: String,
        minQty: Long,
    ): Int

    /** source 필터 조회 (MOCK/REAL). */
    fun findByUserIdAndSource(
        userId: String,
        source: String,
    ): List<PositionJpaEntity>

    /**
     * ADR 0115 §2-B 정합 검산 (불변식 #3·#4) — user+code KIS 권위 보유 수량 합산 (read-only).
     *
     * 시그널 추적 드릴다운에서 "전략 귀속 보유 합 == KIS 권위" 정합 badge 산정에 사용.
     * 같은 code가 MOCK·REAL 행으로 분리될 수 있어 SUM. 행 없으면 0.
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT COALESCE(SUM(p.qty), 0) FROM PositionJpaEntity p " +
            "WHERE p.userId = :userId AND p.code = :code",
    )
    fun sumQtyByUserIdAndCode(
        @Param("userId") userId: String,
        @Param("code") code: String,
    ): Long

    /** 일일 실현 손익 합산 — qty=0(청산) + entry_ts >= kstMidnight. */
    @Query(
        "SELECT COALESCE(SUM((p.avgPrice * -1) * p.qty), 0) FROM PositionJpaEntity p " +
            "WHERE p.userId = :userId AND p.qty = 0 AND p.entryTs >= :since",
    )
    fun sumClosedPnlSince(
        @Param("userId") userId: String,
        @Param("since") since: Instant,
    ): BigDecimal
}
