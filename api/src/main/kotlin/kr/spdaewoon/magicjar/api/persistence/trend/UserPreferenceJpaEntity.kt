package kr.spdaewoon.magicjar.api.persistence.trend

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * user_preference JPA 엔티티 (Flyway V51 + ADR 0101 §2-2).
 *
 * userId = user_account.user_id (VARCHAR(20) KIS 계좌번호 자연 키 — ADR 0046 §2-1) PK + FK.
 *
 * - `trendMaPeriodsJson` — JSONB array (예: `[5,20,60]`). Kotlin 도메인 변환은 adapter 책임.
 * - `trendCrossPairJson` — JSONB object (예: `{"short":5,"long":60}`) · null 가능.
 * - `extraJson` — forward-compat 확장 슬롯 (Map<String, Any?>) · null 가능.
 *
 * ## 헌법 §6 (ADR 0051) — Repository default fun 함정 회피
 * 본 클래스는 Entity 만. UPSERT 헬퍼는 caller (`TrendPreferenceJpaAdapter`) 의 private fun 이동.
 */
@Entity
@Table(name = "user_preference")
class UserPreferenceJpaEntity(
    @Id
    @Column(name = "user_id", length = 20, nullable = false, updatable = false)
    val userId: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trend_ma_periods", nullable = false, columnDefinition = "jsonb")
    var trendMaPeriodsJson: String,
    @Column(name = "trend_rs_period", nullable = false)
    var trendRsPeriod: Int = 60,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trend_cross_pair", columnDefinition = "jsonb")
    var trendCrossPairJson: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra", columnDefinition = "jsonb")
    var extraJson: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    // no-arg constructor for JPA
    protected constructor() : this(
        userId = "",
        trendMaPeriodsJson = "[5,20,60]",
    )
}
