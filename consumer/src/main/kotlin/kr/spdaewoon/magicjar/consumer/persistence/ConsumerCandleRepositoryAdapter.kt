package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.Types
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

/**
 * CandleRepository 도메인 포트 구현 — consumer 모듈.
 * KIS cold-start backfill 저장에 사용 (Yahoo Finance 2026-05-11 폐기 — KIS-only 정책).
 */
@Component
class ConsumerCandleRepositoryAdapter(
    private val candleJpaRepository: ConsumerCandleJpaRepository,
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : CandleRepository {
    private val log = LoggerFactory.getLogger(ConsumerCandleRepositoryAdapter::class.java)

    companion object {
        /**
         * 단건 INSERT … ON CONFLICT DO UPDATE — [ConsumerCandleJpaRepository.insertOrUpdate] 와 1:1 동일 SQL.
         * batchUpdate 로 multi-row round-trip 을 1 회로 묶기 위해 named-param 형태로 인라인(JPA @Query 와 동일 semantics).
         * ON CONFLICT (code, interval, ts) DO UPDATE — 무조건 갱신(gapfill 가드 없음, insertOrUpdate 정합).
         */
        private const val UPSERT_SQL =
            """
            INSERT INTO candle (code, interval, ts, open_raw, high_raw, low_raw, close_raw, volume, source)
            VALUES (:code, :interval, :ts, :openRaw, :highRaw, :lowRaw, :closeRaw, :volume, :source)
            ON CONFLICT (code, interval, ts) DO UPDATE SET
                open_raw = EXCLUDED.open_raw,
                high_raw = EXCLUDED.high_raw,
                low_raw = EXCLUDED.low_raw,
                close_raw = EXCLUDED.close_raw,
                volume = EXCLUDED.volume,
                source = EXCLUDED.source
            """

        /** JDBC batch chunk — 대량(종목당 ~2,000봉) 시 단일 statement 과대 방지(메모리·드라이버 한계). */
        const val UPSERT_BATCH_CHUNK = 500
    }

    @Transactional
    override fun saveAll(candles: List<Candle>): Int {
        if (candles.isEmpty()) return 0
        var inserted = 0
        for (candle in candles) {
            val ts = Instant.ofEpochSecond(candle.ts.epochSeconds)
            val count =
                candleJpaRepository.insertIgnore(
                    code = candle.code,
                    interval = candle.interval.code,
                    ts = ts,
                    openRaw = candle.openRaw,
                    highRaw = candle.highRaw,
                    lowRaw = candle.lowRaw,
                    closeRaw = candle.closeRaw,
                    volume = candle.volume,
                    source = candle.source,
                )
            inserted += count
        }
        log.debug("candle 저장 (consumer): total={} inserted={}", candles.size, inserted)
        return inserted
    }

    /**
     * 캔들 일괄 upsert (ON CONFLICT DO UPDATE) — **JDBC batchUpdate**.
     *
     * ## 성능 (2026-06-10 ADR 0120 amend — row-by-row 병목 제거)
     *   기존: 종목당 ~2,000봉(1wk 1568+1mo 361+1y 30)을 [ConsumerCandleJpaRepository.insertOrUpdate]
     *   단건 호출로 루프 → ~2,000 DB round-trip → ~20초/종목 → 전종목 백필 ~3종목/분(curl 타임아웃).
     *   변경: 동일 ON CONFLICT DO UPDATE SQL([UPSERT_SQL] = insertOrUpdate @Query 와 1:1)을
     *   [NamedParameterJdbcTemplate.batchUpdate] 로 [UPSERT_BATCH_CHUNK] 묶음 실행 → round-trip 수 회로 축소.
     *
     * ## semantics 보존 (공유 경로 회귀 0 — R2 snapshot·rollup·AggregateBackfillRunner 공유)
     *   - SQL 동일(무조건 DO UPDATE, gapfill 가드 없음) — insertOrUpdate 와 동일 결과.
     *   - 반환값: JDBC batch 의 row 별 update count 합산. Postgres 는 INSERT 1행/UPDATE 1행 모두 1 을 반환하고,
     *     ON CONFLICT … WHERE 절이 없어(무조건 갱신) row 별 항상 1 → 합계 = candles.size(중복 ts 입력만 예외).
     *     단건 루프(insertOrUpdate per row 합산)와 동일한 affected 의미를 유지한다.
     *   - 일부 JDBC 드라이버가 batch 에서 `Statement.SUCCESS_NO_INFO`(-2)/`EXECUTE_FAILED`(-3)를 줄 수 있어
     *     음수는 합산에서 제외하고 1 로 보정(영향 row 보수 추정) — affected count 음수 오염 방지.
     */
    @Transactional
    override fun upsertAll(candles: List<Candle>): Int {
        if (candles.isEmpty()) return 0
        var affected = 0
        for (chunk in candles.chunked(UPSERT_BATCH_CHUNK)) {
            val params: Array<SqlParameterSource> = chunk.map { toUpsertParams(it) }.toTypedArray()
            val counts = jdbcTemplate.batchUpdate(UPSERT_SQL, params)
            // batchUpdate 는 row 별 update count 배열 반환. 음수(SUCCESS_NO_INFO=-2 등)는 1 로 보정.
            affected += counts.sumOf { if (it >= 0) it else 1 }
        }
        log.debug("candle upsert (consumer · batch): total={} affected={}", candles.size, affected)
        return affected
    }

    /**
     * Candle → [UPSERT_SQL] named-param 매핑 (insertOrUpdate @Param 시그니처와 1:1).
     * nullable 컬럼(open/high/low/close/volume)은 명시 SQL 타입을 부여해 batch 의 null 바인딩을 안전화한다.
     */
    private fun toUpsertParams(candle: Candle): SqlParameterSource =
        MapSqlParameterSource()
            .addValue("code", candle.code)
            .addValue("interval", candle.interval.code)
            // ts = candle.ts(timestamptz). ★ batch 경로는 pgjdbc 가 java.time.Instant 타입 추론을 못해
            // "could not determine data type" → BadSqlGrammar 전종목 실패(2026-06-10 실측). Timestamp 명시 바인딩 정정.
            .addValue("ts", java.sql.Timestamp.from(Instant.ofEpochSecond(candle.ts.epochSeconds)), Types.TIMESTAMP)
            .addValue("openRaw", candle.openRaw, Types.NUMERIC)
            .addValue("highRaw", candle.highRaw, Types.NUMERIC)
            .addValue("lowRaw", candle.lowRaw, Types.NUMERIC)
            .addValue("closeRaw", candle.closeRaw, Types.NUMERIC)
            .addValue("volume", candle.volume, Types.BIGINT)
            .addValue("source", candle.source)

    @Transactional(readOnly = true)
    override fun countByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): Long = candleJpaRepository.countByCodeAndInterval(code, interval.code)

    @Transactional(readOnly = true)
    override fun countByCodeAndIntervalIn(
        code: String,
        intervals: List<CandleInterval>,
    ): Long = candleJpaRepository.countByCodeAndIntervalIn(code, intervals.map { it.code })

    /**
     * 종목·interval 전체 candle 반환 (ts ASC).
     * JPA entity → domain Candle 변환 (adj 필드 null 허용).
     * ADR 0062 §9 AggregateBackfillRunner 1m 전량 fetch 용.
     */
    @Transactional(readOnly = true)
    override fun findByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Candle> =
        candleJpaRepository
            .findByCodeAndIntervalOrderByTsAsc(code, interval.code)
            .map { it.toDomain(interval) }

    @Transactional(readOnly = true)
    override fun findLatestByCodeAndInterval(
        code: String,
        interval: CandleInterval,
        limit: Int,
    ): List<Candle> =
        candleJpaRepository
            .findByCodeAndIntervalLatest(code, interval.code, PageRequest.of(0, limit.coerceAtLeast(1)))
            .sortedBy { it.ts }
            .map { it.toDomain(interval) }

    @Transactional(readOnly = true)
    override fun findLatestBeforeByCodeAndInterval(
        code: String,
        interval: CandleInterval,
        beforeExclusive: Instant,
        limit: Int,
    ): List<Candle> =
        candleJpaRepository
            .findByCodeAndIntervalAndTsBefore(code, interval.code, beforeExclusive, PageRequest.of(0, limit.coerceAtLeast(1)))
            .sortedBy { it.ts }
            .map { it.toDomain(interval) }

    @Transactional(readOnly = true)
    override fun findMaxTsByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): Instant? = candleJpaRepository.findMaxTsByCodeAndInterval(code, interval.code)

    /**
     * 범위 내 ts ASC 조회 — ADR 0086 §2-2 (T-CC Phase 2-A CandleGapDetector 사용).
     */
    @Transactional(readOnly = true)
    override fun findAllTsByCodeAndIntervalBetween(
        code: String,
        interval: CandleInterval,
        from: Instant,
        to: Instant,
    ): List<Instant> =
        candleJpaRepository.findTsByCodeAndIntervalAndTsBetween(
            code = code,
            interval = interval.code,
            fromTs = from,
            toTs = to,
        )

    /**
     * 기간 버킷 내 고아 앵커 삭제 — ADR 0120 amend (2026-06-11 P1 롤업 고아 정리).
     * 버킷 경계 `[bucketStart, bucketEnd)` ∩ `ts <> keepTs` 이중 조건의 native DELETE.
     */
    @Transactional
    override fun deleteStaleBucketAnchors(
        code: String,
        interval: CandleInterval,
        bucketStart: Instant,
        bucketEnd: Instant,
        keepTs: Instant,
    ): Int {
        val deleted =
            candleJpaRepository.deleteStaleBucketAnchors(
                code = code,
                interval = interval.code,
                bucketStart = bucketStart,
                bucketEnd = bucketEnd,
                keepTs = keepTs,
            )
        if (deleted > 0) {
            log.debug(
                "candle 고아 앵커 삭제 (consumer): code={} interval={} bucket=[{},{}) keepTs={} deleted={}",
                code,
                interval.code,
                bucketStart,
                bucketEnd,
                keepTs,
                deleted,
            )
        }
        return deleted
    }

    /**
     * 정확히 (code, interval, ts) 1행 삭제 — ADR 0118 R-BF amend (2026-06-15 특정 날짜 1d 정밀 복구).
     * 손상 0값 row 가 KIS range 미보유(무거래·거래정지)여서 DO UPDATE 로 덮을 수 없을 때 그 정확한 1행만 제거.
     */
    @Transactional
    override fun deleteByCodeAndIntervalAndTs(
        code: String,
        interval: CandleInterval,
        ts: Instant,
    ): Int {
        val deleted = candleJpaRepository.deleteByCodeAndIntervalAndTs(code, interval.code, ts)
        if (deleted > 0) {
            log.debug("candle 정확 ts 삭제 (consumer): code={} interval={} ts={} deleted={}", code, interval.code, ts, deleted)
        }
        return deleted
    }

    private fun ConsumerCandleJpaEntity.toDomain(interval: CandleInterval): Candle =
        Candle(
            id = id,
            code = code,
            interval = interval,
            ts = KtInstant.fromEpochSeconds(ts.epochSecond),
            openRaw = openRaw ?: BigDecimal.ZERO,
            highRaw = highRaw ?: BigDecimal.ZERO,
            lowRaw = lowRaw ?: BigDecimal.ZERO,
            closeRaw = closeRaw ?: BigDecimal.ZERO,
            openAdj = openAdj,
            highAdj = highAdj,
            lowAdj = lowAdj,
            closeAdj = closeAdj,
            volume = volume ?: 0L,
            source = source,
        )
}
