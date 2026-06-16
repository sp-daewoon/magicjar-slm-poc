package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * DART 공시 Spring Data JPA Repository (T-NEWS Phase 1 — ADR 0075 §2-1).
 *
 * ADR 0051 default fun 함정 회피 LOCK:
 *   - interface 내부 body 가진 fun 발행 금지
 *   - abstract 선언만 허용 (Spring Data 파생 쿼리 또는 @Query)
 *
 * 멱등성 UPSERT:
 *   existsByRceptNo() — 신규 여부 확인 후 save() 조합
 *   (ON CONFLICT DO NOTHING 네이티브 쿼리는 DisclosureRepositoryAdapter에서 처리)
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
interface DisclosureJpaRepository : JpaRepository<DisclosureJpaEntity, Long> {
    /**
     * rcept_no 존재 여부 — 멱등성 중복 INSERT 방지.
     */
    fun existsByRceptNo(rceptNo: String): Boolean

    /**
     * 종목코드 + 수집 시각 범위 조회 (기간 필터 — REST /disclosures).
     * ix_disclosure_stock_code_ingested_at 인덱스 최적화.
     */
    fun findByStockCodeAndIngestedAtBetweenOrderByIngestedAtDesc(
        stockCode: String,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<DisclosureJpaEntity>

    /**
     * 종목코드 + 정정공시 필터 + 수집 시각 범위 조회 (onlyCorrection=true).
     */
    fun findByStockCodeAndIsCorrectionTrueAndIngestedAtBetweenOrderByIngestedAtDesc(
        stockCode: String,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<DisclosureJpaEntity>

    /**
     * 종목코드 + 수집 시각 범위 총 건수 (페이징 없는 count — 별도 조회 최적화).
     */
    @Query(
        """
        SELECT COUNT(d) FROM DisclosureJpaEntity d
        WHERE d.stockCode = :stockCode
          AND d.ingestedAt BETWEEN :from AND :to
    """,
    )
    fun countByStockCodeAndIngestedAtBetween(
        @Param("stockCode") stockCode: String,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): Long
}
