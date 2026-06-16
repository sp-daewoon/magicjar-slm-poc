package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * candle Spring Data JPA 리포지터리 (ADR 0019).
 * batch INSERT는 nativeQuery ON CONFLICT DO NOTHING 사용.
 */
interface CandleJpaRepository : JpaRepository<CandleJpaEntity, Long> {
    fun existsByCodeAndIntervalAndTs(
        code: String,
        interval: String,
        ts: java.time.Instant,
    ): Boolean

    fun countByCodeAndInterval(
        code: String,
        interval: String,
    ): Long

    fun countByCodeAndIntervalIn(
        code: String,
        intervals: Collection<String>,
    ): Long

    /**
     * 종목·interval·범위 내 candle 보유 수 — AI 종목발굴 후보 candle 가용량 판정용.
     * Spring Data query inference — body 없음 (헌법 §6 JPA default fun 함정 회피).
     */
    fun countByCodeAndIntervalAndTsBetween(
        code: String,
        interval: String,
        fromTs: Instant,
        toTs: Instant,
    ): Long

    /**
     * 종목·interval 목록 기준 candle 삭제.
     * 사용자 재수집(recollect) path에서만 사용한다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "DELETE FROM CandleJpaEntity c WHERE c.code = :code AND c.interval IN :intervals",
    )
    fun deleteByCodeAndIntervalIn(
        @Param("code") code: String,
        @Param("intervals") intervals: Collection<String>,
    ): Int

    /**
     * 종목·interval 목록·시간 범위 기준 candle 삭제.
     * 리서치용 range 재수집에서만 사용한다. 전체 interval 삭제보다 작은 blast radius를 보장한다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "DELETE FROM CandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval IN :intervals " +
            "AND c.ts >= :fromTs AND c.ts <= :toTs",
    )
    fun deleteByCodeAndIntervalInAndTsBetween(
        @Param("code") code: String,
        @Param("intervals") intervals: Collection<String>,
        @Param("fromTs") fromTs: Instant,
        @Param("toTs") toTs: Instant,
    ): Int

    /**
     * 캔들 시계열 페이지네이션 조회 — before 이전 ts DESC LIMIT limit (CandleQueryService 용).
     * 응답은 ASC 변환은 service 레이어 책임.
     */
    @Query(
        "SELECT c FROM CandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval = :interval AND c.ts < :before " +
            "ORDER BY c.ts DESC",
    )
    fun findByCodeAndIntervalAndTsBefore(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("before") before: Instant,
        pageable: Pageable,
    ): List<CandleJpaEntity>

    /**
     * before 없이 최신순 조회.
     */
    @Query(
        "SELECT c FROM CandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval = :interval " +
            "ORDER BY c.ts DESC",
    )
    fun findByCodeAndIntervalLatest(
        @Param("code") code: String,
        @Param("interval") interval: String,
        pageable: Pageable,
    ): List<CandleJpaEntity>

    /**
     * 백테스트 범위 조회 — fromTs 이상 toTs 이하, ASC 정렬 (BacktestService 용).
     * ADR 0051 준수 — body 없는 @Query abstract fun.
     */
    @Query(
        "SELECT c FROM CandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval = :interval " +
            "AND c.ts >= :fromTs AND c.ts <= :toTs " +
            "ORDER BY c.ts ASC",
    )
    fun findByCodeAndIntervalAndTsBetween(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("fromTs") fromTs: Instant,
        @Param("toTs") toTs: Instant,
    ): List<CandleJpaEntity>

    /**
     * 종목·interval 전체 candle 조회 (ts ASC).
     * Spring Data query inference — body 없음 (헌법 §6 JPA default fun 함정 회피).
     * ADR 0062 §9 AggregateBackfillRunner 1m 전량 fetch 용.
     */
    fun findByCodeAndIntervalOrderByTsAsc(
        code: String,
        interval: String,
    ): List<CandleJpaEntity>

    /**
     * 종목·interval 의 마지막 ts (Instant) — row 0 건이면 null.
     * hotfix 2026-05-15: CandleBootstrapRunner stale 검출 용.
     */
    @Query(
        "SELECT MAX(c.ts) FROM CandleJpaEntity c WHERE c.code = :code AND c.interval = :interval",
    )
    fun findMaxTsByCodeAndInterval(
        @Param("code") code: String,
        @Param("interval") interval: String,
    ): java.time.Instant?

    /**
     * 종목·interval·범위 내 ts 만 ASC 조회 — ADR 0086 §2-2 add-only.
     * T-CC Phase 2-A CandleGapDetector 가 actual sequence diff 산출 시 사용.
     * Entity 전체(findByCodeAndIntervalAndTsBetween) 대비 효율.
     */
    @Query(
        "SELECT c.ts FROM CandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval = :interval " +
            "AND c.ts >= :fromTs AND c.ts <= :toTs " +
            "ORDER BY c.ts ASC",
    )
    fun findTsByCodeAndIntervalAndTsBetween(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("fromTs") fromTs: Instant,
        @Param("toTs") toTs: Instant,
    ): List<Instant>

    /**
     * 단건 idempotent INSERT (ON CONFLICT DO NOTHING).
     * batch INSERT가 필요하면 saveAll + flush 패턴 사용.
     * @Transactional 필수 — @Modifying native query는 활성 트랜잭션 없이 실행 불가
     * ("Executing an update/delete query" 결함 방지, hotfix).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO candle (code, interval, ts, open_raw, high_raw, low_raw, close_raw, volume, source)
            VALUES (:code, :interval, :ts, :openRaw, :highRaw, :lowRaw, :closeRaw, :volume, :source)
            ON CONFLICT (code, interval, ts) DO NOTHING
        """,
    )
    fun insertIgnore(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("ts") ts: java.time.Instant,
        @Param("openRaw") openRaw: java.math.BigDecimal?,
        @Param("highRaw") highRaw: java.math.BigDecimal?,
        @Param("lowRaw") lowRaw: java.math.BigDecimal?,
        @Param("closeRaw") closeRaw: java.math.BigDecimal?,
        @Param("volume") volume: Long?,
        @Param("source") source: String,
    ): Int

    /**
     * 단건 UPSERT (ON CONFLICT DO UPDATE).
     * aggregate candle 재생성처럼 기존 row 교정이 필요한 path 전용.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO candle (code, interval, ts, open_raw, high_raw, low_raw, close_raw, volume, source)
            VALUES (:code, :interval, :ts, :openRaw, :highRaw, :lowRaw, :closeRaw, :volume, :source)
            ON CONFLICT (code, interval, ts) DO UPDATE SET
                open_raw = EXCLUDED.open_raw,
                high_raw = EXCLUDED.high_raw,
                low_raw = EXCLUDED.low_raw,
                close_raw = EXCLUDED.close_raw,
                volume = EXCLUDED.volume,
                source = EXCLUDED.source
        """,
    )
    fun insertOrUpdate(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("ts") ts: java.time.Instant,
        @Param("openRaw") openRaw: java.math.BigDecimal?,
        @Param("highRaw") highRaw: java.math.BigDecimal?,
        @Param("lowRaw") lowRaw: java.math.BigDecimal?,
        @Param("closeRaw") closeRaw: java.math.BigDecimal?,
        @Param("volume") volume: Long?,
        @Param("source") source: String,
    ): Int
}
