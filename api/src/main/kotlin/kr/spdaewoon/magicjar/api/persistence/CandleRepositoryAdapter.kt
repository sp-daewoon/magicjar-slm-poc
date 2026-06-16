package kr.spdaewoon.magicjar.api.persistence

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

/**
 * CandleRepository 도메인 포트 구현 (api 모듈).
 * saveAll: 각 행을 insertIgnore (ON CONFLICT DO NOTHING)으로 실행.
 * 트랜잭션 내에서 실행 — 호출자가 트랜잭션 없으면 신규 생성.
 */
@Component
class CandleRepositoryAdapter(
    private val candleJpaRepository: CandleJpaRepository,
) : CandleRepository {
    private val log = LoggerFactory.getLogger(CandleRepositoryAdapter::class.java)

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
        log.debug("candle 저장: total={} inserted={}", candles.size, inserted)
        return inserted
    }

    @Transactional
    override fun upsertAll(candles: List<Candle>): Int {
        if (candles.isEmpty()) return 0
        var affected = 0
        for (candle in candles) {
            val ts = Instant.ofEpochSecond(candle.ts.epochSeconds)
            val count =
                candleJpaRepository.insertOrUpdate(
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
            affected += count
        }
        log.debug("candle upsert: total={} affected={}", candles.size, affected)
        return affected
    }

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
        beforeExclusive: java.time.Instant,
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
    ): java.time.Instant? = candleJpaRepository.findMaxTsByCodeAndInterval(code, interval.code)

    /**
     * 범위 내 ts ASC 조회 — ADR 0086 §2-2 (T-CC Phase 2-A CandleGapDetector 사용).
     */
    @Transactional(readOnly = true)
    override fun findAllTsByCodeAndIntervalBetween(
        code: String,
        interval: CandleInterval,
        from: java.time.Instant,
        to: java.time.Instant,
    ): List<java.time.Instant> =
        candleJpaRepository.findTsByCodeAndIntervalAndTsBetween(
            code = code,
            interval = interval.code,
            fromTs = from,
            toTs = to,
        )

    private fun CandleJpaEntity.toDomain(interval: CandleInterval): Candle =
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
