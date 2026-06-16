package kr.spdaewoon.magicjar.api.persistence.performance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * market_index_daily JPA 엔티티 — Flyway V86 정합 (spec 2026-06-16 §3 · ADR 0126 §3-1).
 *
 * 지수×일자 OHLC 롤업(전 전략 공유) — `market_index_snapshot`(V47, 1분)을 그날치로 집계한 영속행.
 * publisher = batch DailyPerformanceJob([MarketIndexDailyRollupService] 호출 결과 UPSERT) + 백필.
 *
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 * UQ (index_code, trade_date) — 배치/백필 멱등 UPSERT 비즈니스 키.
 */
@Entity
@Table(name = "market_index_daily")
class MarketIndexDailyJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "index_code", nullable = false, length = 16)
    val indexCode: String,
    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,
    @Column(name = "open", precision = 14, scale = 2)
    val open: BigDecimal?,
    @Column(name = "high", precision = 14, scale = 2)
    val high: BigDecimal?,
    @Column(name = "low", precision = 14, scale = 2)
    val low: BigDecimal?,
    @Column(name = "close", precision = 14, scale = 2)
    val close: BigDecimal?,
    @Column(name = "change_pct", precision = 10, scale = 4)
    val changePct: BigDecimal?,
    @Column(name = "range_pct", precision = 10, scale = 4)
    val rangePct: BigDecimal?,
    @Column(name = "regime", length = 16)
    val regime: String?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        indexCode = "",
        tradeDate = LocalDate.EPOCH,
        open = null,
        high = null,
        low = null,
        close = null,
        changePct = null,
        rangePct = null,
        regime = null,
    )
}
