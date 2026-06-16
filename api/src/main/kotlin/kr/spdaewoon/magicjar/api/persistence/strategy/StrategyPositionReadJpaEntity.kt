package kr.spdaewoon.magicjar.api.persistence.strategy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

/**
 * strategy_position **read-only** JPA 엔티티 (api 모듈 — ADR 0110 V64, 분할매수 R3-a).
 *
 * 쓰기(upsert)는 consumer 모듈 [StrategyPositionJpaEntity] 책임(체결 listener). api 모듈은 전략별 포지션
 * 표시(GET /api/strategies/{id}/position)를 위한 조회만 한다 — 별도 엔티티 클래스로 매핑(모듈 분리·테이블 공유).
 *
 * ADR 0051 주의: kotlin-jpa no-arg constructor 필요(api 모듈 적용). 본 PR add-only read path.
 */
@Entity
@Table(name = "strategy_position")
class StrategyPositionReadJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String = "",
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0,
    @Column(name = "code", length = 8, nullable = false)
    val code: String = "",
    @Column(name = "quantity", nullable = false)
    val quantity: Int = 0,
    @Column(name = "avg_price", precision = 19, scale = 4, nullable = false)
    val avgPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "filled_lots", nullable = false)
    val filledLots: Int = 0,
    @Column(name = "realized_pnl", precision = 19, scale = 4, nullable = false)
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "executed_exit_stages", columnDefinition = "jsonb", nullable = false)
    val executedExitStages: String = "[]",
    @Column(name = "opened_at", nullable = false)
    val openedAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
