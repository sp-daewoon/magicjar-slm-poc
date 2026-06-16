package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * instrument Spring Data JPA Repository.
 * PR-11b 보강: active 조회·markDelisted·pg_trgm fallback 검색 메서드 추가.
 * PR-21b 보강: findActiveTradeable·findByMarketCapDescLimit·findByIsKospi200True 추가.
 */
interface InstrumentJpaRepository :
    JpaRepository<InstrumentJpaEntity, String>,
    JpaSpecificationExecutor<InstrumentJpaEntity> {
    fun existsByCode(code: String): Boolean

    /** 활성 종목 전체 조회 (Redis 인덱스 rebuild 용도). */
    fun findByActiveTrue(): List<InstrumentJpaEntity>

    /** 특정 market의 활성 종목 조회. */
    fun findByActiveTrueAndMarket(market: String): List<InstrumentJpaEntity>

    /** 종목코드 목록으로 활성 종목 조회 (sync diff 용도). */
    fun findByActiveTrueAndCodeIn(codes: Collection<String>): List<InstrumentJpaEntity>

    /**
     * 상장폐지 처리 — DELETE 금지 (ADR 0017 §2-2).
     * active=false + delisted_at=today + updated_at=now UPDATE.
     */
    @Modifying
    @Query(
        """
        UPDATE InstrumentJpaEntity i
        SET i.active = false, i.delistedAt = :delistedAt, i.updatedAt = CURRENT_TIMESTAMP
        WHERE i.code = :code
        """,
    )
    fun markDelisted(
        @Param("code") code: String,
        @Param("delistedAt") delistedAt: LocalDate,
    )

    /**
     * JPQL fallback 검색 (pg_trgm 미가용 시 LIKE 기반).
     * name·nameNorm 부분 일치 + code prefix/contains 부분 일치.
     * 정규화: 공백·점 제거 + lowercase는 InstrumentSearchService에서 전처리.
     *
     * 사용자 요청: 보통주+우선주만 검색 (KIS scrt_grp_cls_code='ST').
     * ETF(EF/FE)·ETN·REIT(RT)·WARRANT(EW/SW/SR)·DR·외국주식(FS)·펀드(MF/BC/SC/IF) 제외.
     */
    @Query(
        """
        SELECT i FROM InstrumentJpaEntity i
        WHERE i.active = true
        AND i.securitiesGroup = 'ST'
        AND (
            LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%'))
            OR i.code LIKE CONCAT(:query, '%')
            OR i.code LIKE CONCAT('%', :query, '%')
            OR (i.nameNorm IS NOT NULL AND LOWER(i.nameNorm) LIKE LOWER(CONCAT('%', :query, '%')))
        )
        AND (:market IS NULL OR i.market = :market)
        ORDER BY
            CASE WHEN LOWER(i.code) = LOWER(:query) THEN 0
                 WHEN i.code LIKE CONCAT(:query, '%') THEN 1
                 WHEN i.code LIKE CONCAT('%', :query, '%') THEN 2
                 WHEN LOWER(i.name) = LOWER(:query) THEN 3
                 WHEN LOWER(i.name) LIKE LOWER(CONCAT(:query, '%')) THEN 4
                 ELSE 5 END,
            i.name ASC
        """,
    )
    fun searchByNameOrCode(
        @Param("query") query: String,
        @Param("market") market: String?,
        pageable: Pageable,
    ): List<InstrumentJpaEntity>

    // ==========================================================================
    // PR-21b 신규 — T-SC·T-IND screening 지원
    // ==========================================================================

    /**
     * 매매 가능한 활성 종목 (PR-21b).
     * active=true AND trading_halt=false AND liquidation=false.
     * T-SC universe 1차 필터.
     */
    @Query(
        """
        SELECT i FROM InstrumentJpaEntity i
        WHERE i.active = true
          AND i.tradingHalt = false
          AND i.liquidation = false
        ORDER BY i.code ASC
        """,
    )
    fun findActiveTradeable(): List<InstrumentJpaEntity>

    /**
     * market별 활성 종목 수 집계 (PR-21b).
     * T-MS1 시장 통계용.
     */
    @Query(
        """
        SELECT i.market, COUNT(i) FROM InstrumentJpaEntity i
        WHERE i.active = true
        GROUP BY i.market
        """,
    )
    fun countByMarket(): List<Array<Any>>

    /**
     * 시가총액 내림차순 종목 목록 (PR-21b).
     * market_cap_prev DESC NULLS LAST.
     * T-SC 대형주 screening.
     */
    @Query(
        """
        SELECT i FROM InstrumentJpaEntity i
        WHERE i.active = true
          AND i.marketCapPrev IS NOT NULL
        ORDER BY i.marketCapPrev DESC
        """,
    )
    fun findByMarketCapDescLimit(pageable: Pageable): List<InstrumentJpaEntity>

    /**
     * KOSPI200 편입 종목 목록 (PR-21b).
     * T-SC 기준 universe.
     */
    fun findByActiveTrueAndIsKospi200True(): List<InstrumentJpaEntity>

    /**
     * 활성 일반/우선주 조회 (securities_group='ST').
     * 사용자 검색 인덱스 rebuild — ETF·ETN·REIT·DR·WARRANT 제외, 보통주+우선주 포함.
     */
    fun findByActiveTrueAndSecuritiesGroup(securitiesGroup: String): List<InstrumentJpaEntity>

    // ==========================================================================
    // T-SC Phase 2 — InstrumentLookupAdapter 용 필터 쿼리
    // 헌법 §6: body 가진 fun 금지 — @Query abstract fun 단독 사용
    // ==========================================================================

    /**
     * T-SC Screener universe 필터 쿼리 (ADR 0071 §2-2 B-1 + spec §10).
     *
     * 필터 매트릭스:
     * - active=true + delistedAt IS NULL LOCK (생존자 편향 cover)
     * - market IN :markets
     * - type IN :types
     * - marketCapPrev >= :marketCapMin (null=미적용)
     * - prevVolume >= :avgVolumeMin (null=미적용, prevVolume을 5일 평균 근사값으로 사용)
     *
     * KIS endpoint 호출 0 LOCK — instrument 테이블 영속 데이터 단일 출처.
     */
    @Query(
        """
        SELECT i FROM InstrumentJpaEntity i
        WHERE i.active = true
          AND i.delistedAt IS NULL
          AND i.tradingHalt = false
          AND i.liquidation = false
          AND i.market IN :markets
          AND i.type IN :types
          AND (:marketCapMin IS NULL OR (i.marketCapPrev IS NOT NULL AND i.marketCapPrev >= :marketCapMin))
          AND (:avgVolumeMin IS NULL OR (i.prevVolume IS NOT NULL AND i.prevVolume >= :avgVolumeMin))
        ORDER BY i.code ASC
        """,
    )
    fun findForScreener(
        @Param("markets") markets: List<String>,
        @Param("types") types: List<String>,
        @Param("marketCapMin") marketCapMin: Long?,
        @Param("avgVolumeMin") avgVolumeMin: Long?,
    ): List<InstrumentJpaEntity>
}
