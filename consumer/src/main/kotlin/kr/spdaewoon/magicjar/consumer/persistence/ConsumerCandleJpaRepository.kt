package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * candle Spring Data JPA 리포지터리 — consumer 모듈.
 */
interface ConsumerCandleJpaRepository : JpaRepository<ConsumerCandleJpaEntity, Long> {
    fun countByCodeAndInterval(
        code: String,
        interval: String,
    ): Long

    fun countByCodeAndIntervalIn(
        code: String,
        intervals: Collection<String>,
    ): Long

    /**
     * 종목·interval 전체 candle 조회 (ts ASC).
     * Spring Data query inference — body 없음 (헌법 §6 JPA default fun 함정 회피).
     * ADR 0062 §9 AggregateBackfillRunner 1m 전량 fetch 용.
     */
    fun findByCodeAndIntervalOrderByTsAsc(
        code: String,
        interval: String,
    ): List<ConsumerCandleJpaEntity>

    @Query(
        "SELECT c FROM ConsumerCandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval = :interval " +
            "ORDER BY c.ts DESC",
    )
    fun findByCodeAndIntervalLatest(
        @Param("code") code: String,
        @Param("interval") interval: String,
        pageable: Pageable,
    ): List<ConsumerCandleJpaEntity>

    @Query(
        "SELECT c FROM ConsumerCandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval = :interval AND c.ts < :before " +
            "ORDER BY c.ts DESC",
    )
    fun findByCodeAndIntervalAndTsBefore(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("before") before: java.time.Instant,
        pageable: Pageable,
    ): List<ConsumerCandleJpaEntity>

    /**
     * 종목·interval 의 마지막 ts (Instant) — row 0 건이면 null.
     * hotfix 2026-05-15: CandleBootstrapRunner stale 검출 용.
     */
    @Query(
        "SELECT MAX(c.ts) FROM ConsumerCandleJpaEntity c WHERE c.code = :code AND c.interval = :interval",
    )
    fun findMaxTsByCodeAndInterval(
        @Param("code") code: String,
        @Param("interval") interval: String,
    ): java.time.Instant?

    /**
     * 종목·interval·범위 내 ts 만 ASC 조회 — ADR 0086 §2-2 add-only.
     * T-CC Phase 2-A CandleGapDetector actual sequence diff 산출 용.
     */
    @Query(
        "SELECT c.ts FROM ConsumerCandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval = :interval " +
            "AND c.ts >= :fromTs AND c.ts <= :toTs " +
            "ORDER BY c.ts ASC",
    )
    fun findTsByCodeAndIntervalAndTsBetween(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("fromTs") fromTs: java.time.Instant,
        @Param("toTs") toTs: java.time.Instant,
    ): List<java.time.Instant>

    /**
     * 일봉 패밀리 wipe — ADR 0118 amend MIGRATE 모드 전용 (전 source 대상).
     *
     * ## ★ 안전 제약 (가장 중요)
     *   삭제 대상은 **호출자가 넘긴 [intervals] 화이트리스트로 한정**된다. MIGRATE 캠페인은 이 메서드에
     *   `['1d','1wk','1mo','1y']`(REST 재생성 가능 패밀리)만 전달한다. **`1m`·`5m` 등 실시간 누적분은
     *   KIS REST 로 복구 불가(영구 손실)이므로 절대 전달 금지** — 캠페인 상수([MIGRATE_WIPE_INTERVALS])가
     *   이 화이트리스트를 LOCK 한다.
     *
     *   본 쿼리 자체는 `interval IN (:intervals)` 로만 동작하므로(테이블 전체 truncate 아님), 화이트리스트
     *   밖 interval 은 어떤 경로로도 삭제되지 않는다.
     *
     * @param intervals 삭제 대상 interval code 화이트리스트(예: 1d·1wk·1mo·1y). 빈 컬렉션이면 0행 삭제.
     * @return 삭제된 row 수(wipedRows — 운영 evidence).
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = "DELETE FROM candle WHERE interval IN (:intervals)",
    )
    fun deleteByIntervalIn(
        @Param("intervals") intervals: Collection<String>,
    ): Int

    /**
     * 일봉 패밀리 wipe — **배치 삭제** 변형 (대량 테이블 long-transaction 회피).
     *
     * `ctid` 기반 [batchSize] 청크 삭제. 캠페인이 반환 0 이 될 때까지 반복 호출하여 누적 wipedRows 산출.
     * 각 호출은 독립 트랜잭션(adapter `@Transactional(REQUIRES_NEW)`)이라 한 청크 실패가 전체를 막지 않는다.
     * 안전 제약은 [deleteByIntervalIn] 과 동일 — [intervals] 화이트리스트 밖은 절대 삭제 0 (1m·5m 보존).
     *
     * @return 이 청크에서 삭제된 row 수. [batchSize] 미만이면 더 이상 대상 없음(루프 종료 신호).
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            DELETE FROM candle
            WHERE ctid IN (
                SELECT ctid FROM candle
                WHERE interval IN (:intervals)
                LIMIT :batchSize
            )
        """,
    )
    fun deleteByIntervalInBatch(
        @Param("intervals") intervals: Collection<String>,
        @Param("batchSize") batchSize: Int,
    ): Int

    /**
     * 기간 버킷 내 고아 앵커 삭제 — ADR 0120 amend (2026-06-11 P1 롤업 고아 정리).
     *
     * ## ★ 안전 제약 (광역 DELETE 금지 — 버킷 경계 + 앵커 불일치 이중 조건)
     *   삭제 대상은 `code=? AND interval=?` ∩ `bucketStart ≤ ts < bucketEnd`(반열린 1 버킷) ∩ `ts <> keepTs`.
     *   keepTs(이번 롤업 산출 앵커)와 일치하는 정당 봉은 절대 삭제되지 않는다. 버킷 밖 과거 닫힌 봉도 무손상.
     *   호출은 롤업 산출 봉이 만든 버킷에 한정되므로 incremental 윈도우 밖은 건드리지 않는다.
     *
     * @return 삭제된 고아 row 수.
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            DELETE FROM candle
            WHERE code = :code
              AND interval = :interval
              AND ts >= :bucketStart
              AND ts < :bucketEnd
              AND ts <> :keepTs
        """,
    )
    fun deleteStaleBucketAnchors(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("bucketStart") bucketStart: java.time.Instant,
        @Param("bucketEnd") bucketEnd: java.time.Instant,
        @Param("keepTs") keepTs: java.time.Instant,
    ): Int

    /**
     * 정확히 (code, interval, ts) 1행 삭제 — ADR 0118 R-BF amend (2026-06-15 특정 날짜 1d 정밀 복구).
     *
     * ## ★ 안전 제약 (광역 DELETE 금지 — 정확 키 매칭)
     *   삭제 조건은 `code=? AND interval=? AND ts=?` 정확 매칭뿐(범위·LIKE·IN 없음). 손상 0값 row 가
     *   KIS range 미보유(무거래·거래정지)여서 DO UPDATE 로 덮을 수 없을 때, 그 정확한 1행만 제거한다.
     *   다른 날짜·interval·종목은 절대 삭제되지 않는다.
     *
     * @return 삭제된 row 수(0 또는 1).
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            DELETE FROM candle
            WHERE code = :code
              AND interval = :interval
              AND ts = :ts
        """,
    )
    fun deleteByCodeAndIntervalAndTs(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("ts") ts: java.time.Instant,
    ): Int

    /**
     * 단건 idempotent INSERT (ON CONFLICT DO NOTHING).
     */
    @Modifying
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
     * 실시간/마감 candle 저장용 UPSERT.
     *
     * KIS-RANGE-GAPFILL row는 가격 gapfill 목적이라 volume=0일 수 있다.
     * 같은 ts의 실제 KIS candle이 도착하면 OHLCV를 교정하되, 이미 더 신선한/동일한 row는 건드리지 않는다.
     *
     * 반환 rows:
     * - 1: 신규 INSERT 또는 gapfill/낮은 volume row UPDATE
     * - 0: 이미 동일하거나 더 큰 volume의 실제 row 존재
     */
    @Modifying
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
            WHERE candle.source IN ('KIS-RANGE-GAPFILL', 'KIS-POLLING')
               OR COALESCE(candle.volume, 0) < COALESCE(EXCLUDED.volume, 0)
        """,
    )
    fun insertOrUpdateIfGapfill(
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
     * 1m gap fill 후 aggregate candle 재생성처럼 기존 값을 교정해야 하는 path 전용.
     */
    @Modifying
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
