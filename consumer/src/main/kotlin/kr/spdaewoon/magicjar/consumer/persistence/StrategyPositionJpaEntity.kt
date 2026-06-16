package kr.spdaewoon.magicjar.consumer.persistence

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
 * strategy_position JPA 엔티티 (consumer 모듈 — ADR 0110 V64, 분할매수 R2-2).
 *
 * 기존 [PositionJpaEntity](user+code 합산, KIS 권위) 아래의 전략별 lot ledger.
 * UQ(user_id, strategy_id, code) — 체결 listener가 find-then-save로 upsert(같은 transaction boundary).
 *
 * `executed_exit_stages` 는 JSONB 정수 배열 — [ConsumerStrategyJpaEntity] codes/intervals 패턴 정합
 * (`@JdbcTypeCode(SqlTypes.JSON)` + String). 어댑터가 Set<Int> ↔ JSON String 매핑.
 */
@Entity
@Table(name = "strategy_position")
class StrategyPositionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "quantity", nullable = false)
    val quantity: Int = 0,
    @Column(name = "avg_price", precision = 19, scale = 4, nullable = false)
    val avgPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "filled_lots", nullable = false)
    val filledLots: Int = 0,
    @Column(name = "realized_pnl", precision = 19, scale = 4, nullable = false)
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
    /** 발화·체결된 PartialExit 단계 인덱스 — JSONB 정수 배열 String(`[]` default). 어댑터가 Set<Int> 매핑. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "executed_exit_stages", columnDefinition = "jsonb", nullable = false)
    val executedExitStages: String = "[]",
    @Column(name = "opened_at", nullable = false)
    val openedAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(userId = "", strategyId = 0, code = "")
}
