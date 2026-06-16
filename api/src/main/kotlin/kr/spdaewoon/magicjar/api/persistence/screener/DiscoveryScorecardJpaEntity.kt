package kr.spdaewoon.magicjar.api.persistence.screener

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
 * discovery_scorecard JPA 엔티티 (Flyway V77).
 *
 * 매일 장전 발굴 TOP N 기록 + 다음 거래일 일봉으로 자동 채점하는 forward-test row.
 *
 * 헌법 §6: no-arg constructor 필요 → kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * body 가진 fun 0건 LOCK.
 *
 * INSERT는 native ON CONFLICT DO NOTHING([DiscoveryScorecardJpaRepository.insertIgnore]) 경로로 멱등 처리하고,
 * 본 엔티티는 조회/채점 read·dirty-update 용도로만 사용한다.
 */
@Entity
@Table(name = "discovery_scorecard")
class DiscoveryScorecardJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "snapshot_date", nullable = false)
    val snapshotDate: LocalDate,
    @Column(name = "preset_key", length = 64, nullable = false)
    val presetKey: String,
    @Column(name = "rank", nullable = false)
    val rank: Int,
    @Column(name = "instrument_code", length = 32, nullable = false)
    val instrumentCode: String,
    @Column(name = "instrument_name", length = 120, nullable = false)
    val instrumentName: String,
    @Column(name = "score", nullable = false)
    val score: BigDecimal = BigDecimal.ZERO,
    @Column(name = "base_close")
    var baseClose: BigDecimal? = null,
    @Column(name = "next_open")
    var nextOpen: BigDecimal? = null,
    @Column(name = "next_high")
    var nextHigh: BigDecimal? = null,
    @Column(name = "next_close")
    var nextClose: BigDecimal? = null,
    @Column(name = "next_return_pct")
    var nextReturnPct: BigDecimal? = null,
    @Column(name = "next_open_return_pct")
    var nextOpenReturnPct: BigDecimal? = null,
    @Column(name = "scored_at")
    var scoredAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
