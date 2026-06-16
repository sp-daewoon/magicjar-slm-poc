package kr.spdaewoon.magicjar.api.persistence.journal

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
 * trading_journal JPA 엔티티 — Flyway V29 정합 (ADR 0072 §3-1).
 *
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 * payload: JSONB 영속 (원본 이벤트 페이로드 audit + W4 AI context).
 * 멱등성 UQ: ux_trading_journal_source (source_table, source_id, event_type) — DB constraint.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Entity
@Table(name = "trading_journal")
class TradingJournalJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "event_type", nullable = false, length = 32)
    val eventType: String,
    @Column(name = "instrument_code", length = 16)
    val instrumentCode: String? = null,
    @Column(name = "instrument_name", length = 64)
    val instrumentName: String? = null,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(name = "summary", nullable = false, length = 255)
    val summary: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    val payload: String,
    @Column(name = "source_table", nullable = false, length = 32)
    val sourceTable: String,
    @Column(name = "source_id", nullable = false)
    val sourceId: Long,
    @Column(name = "realized_pnl", precision = 18, scale = 2)
    val realizedPnl: BigDecimal? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
