package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * MARKET_DAILY universe 조회 Repository (ADR 0118 R1 — 전종목 일봉 적재 대상).
 *
 * 기존 `instrument` 테이블(KIS 73필드 마스터 이미 적재) 재사용 — 신규 테이블 0.
 * 보통주 ~3,000 코드 집합 산출 (결재 ① LOCK — ETF·ETN·우선주·SPAC·REIT 제외).
 *
 * ★ 헌법 §6 (Repository default fun 함정) 정합:
 *   interface body 가진 fun 0건. native @Query만 사용 (ADR 0051 함정 회피).
 *
 * 보통주 필터 근거 (KIS scrt_grp_cls_code 권위 매핑 — KisInstrumentMasterAdapter.mapType 정합):
 *   - securities_group = 'ST'  → 보통주 (STOCK). ETF(EF/FE)·REIT(RT)·DR·WARRANT(EW/SW/SR) 자동 제외.
 *   - preferred_stock_type NOT IN ('1','2') → 구형/신형 우선주 제외 (0=보통주·null 허용).
 *   - is_spac = false → SPAC(기업인수목적회사) 제외.
 *   - active = true AND delisted_at IS NULL → 상장 중인 종목만.
 *   ETN은 securities_group이 EF/FE이거나 etp_product_type IN ('3','4')이므로 ST 필터로 자동 배제된다.
 */
interface ConsumerMarketDailyUniverseJpaRepository : JpaRepository<ConsumerInstrumentJpaEntity, String> {
    /**
     * MARKET_DAILY universe 보통주 코드 집합 (정렬: code ASC — 결정적 순서).
     *
     * raw 필터 (ADR 0118 §2-A 결재 ① LOCK):
     *   securities_group='ST' AND (preferred_stock_type IS NULL OR preferred_stock_type NOT IN ('1','2'))
     *   AND is_spac=false AND active=true AND delisted_at IS NULL
     */
    @Query(
        value = """
            SELECT code FROM instrument
            WHERE securities_group = 'ST'
              AND (preferred_stock_type IS NULL OR preferred_stock_type NOT IN ('1', '2'))
              AND is_spac = false
              AND active = true
              AND delisted_at IS NULL
            ORDER BY code ASC
        """,
        nativeQuery = true,
    )
    fun findMarketDailyCommonStockCodes(): List<String>

    /** MARKET_DAILY universe 보통주 카운트 (운영 관측·실측 evidence용). */
    @Query(
        value = """
            SELECT count(*) FROM instrument
            WHERE securities_group = 'ST'
              AND (preferred_stock_type IS NULL OR preferred_stock_type NOT IN ('1', '2'))
              AND is_spac = false
              AND active = true
              AND delisted_at IS NULL
        """,
        nativeQuery = true,
    )
    fun countMarketDailyCommonStock(): Long

    /**
     * MARKET_DAILY 종목별 listed_at clamp용 상장일 조회 (R3 gap catch-up·R-BF backfill 경계).
     * R1에서는 미사용이나, 후속 라운드가 false gap 방지(§2-E)에 재사용할 수 있도록 발행.
     */
    @Query(
        value = "SELECT listed_at FROM instrument WHERE code = :code",
        nativeQuery = true,
    )
    fun findListedAtByCode(
        @Param("code") code: String,
    ): java.time.LocalDate?
}
