package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * instrument JPA 엔티티 (consumer 모듈 — V3 stub 허용).
 * Bootstrap 시 KIS 잔고 종목에 대해 stub INSERT.
 *
 * tradingHalt: 거래정지 여부 (trht_yn — ADR 0021 §2 매매가드).
 *   cold-start skip 판단에 사용 (결함 정정 2026-05-11).
 *
 * listedAt (ADR 0086 §2-7 §12 amend, 2026-05-19):
 *   Active gap detection 4축 교차 검증 중 4번째 축(상장일 이후 ts) 권위 source.
 *   api/InstrumentJpaEntity에 이미 존재하는 `listed_at` 컬럼을 read-only로 매핑 (add-only).
 *   stub 생성 시점에는 null이며, T-IM1 정식 데이터 인입 시 KIS `lstn_dt`로 채워진다.
 *   null이면 §12 검증에서 "상장일 미상" → 결함으로 간주하지 않고 skip(false positive 회피).
 */
@Entity
@Table(name = "instrument")
class ConsumerInstrumentJpaEntity(
    @Id
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "name", length = 80, nullable = false)
    val name: String,
    @Column(name = "market", length = 10, nullable = false)
    val market: String = "UNKNOWN",
    @Column(name = "type", length = 20, nullable = false)
    val type: String = "UNKNOWN",
    @Column(name = "active", nullable = false)
    val active: Boolean = true,
    /** KOSPI200 여부. NXT 통합 캔들 조회 allow-list 1차 근거로 사용. */
    @Column(name = "is_kospi200", nullable = false)
    val isKospi200: Boolean = false,
    /** KOSDAQ150 여부. NXT 통합 캔들 조회 allow-list 1차 근거로 사용. */
    @Column(name = "is_kosdaq150", nullable = false)
    val isKosdaq150: Boolean = false,
    /** 거래정지 여부 (trht_yn). cold-start skip 판단에 사용. */
    @Column(name = "trading_halt", nullable = false)
    val tradingHalt: Boolean = false,
    /** 정리매매 여부 (sltr_yn). cold-start skip 판단에 사용. */
    @Column(name = "liquidation", nullable = false)
    val liquidation: Boolean = false,
    /**
     * 상장일 (KIS lstn_dt). ADR 0086 §2-7 §12 4축 교차 검증의 4번째 축 권위 source.
     * null 시: 상장일 미상 → §12 검증에서 false positive 회피 (skip).
     */
    @Column(name = "listed_at")
    val listedAt: LocalDate? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(code = "", name = "")
}
