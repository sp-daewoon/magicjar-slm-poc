package kr.spdaewoon.magicjar.batch.job.market

import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexDaily
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.index.port.KisIndexFetcher
import kr.spdaewoon.magicjar.domain.market.index.port.MarketIndexSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * T-MARKET 일봉 backfill Job — 16:30 KST(국내) + 06:00 KST(해외) 2회 (ADR 0100 §2-4 LOCK).
 *
 * ### 책임
 * 1. 국내 24 endpoint (3 지수 + 21 업종) 일봉 fetch (FHPUP02120000)
 *    - 16:30 KST 정규장 마감 + 30분 buffer (KIS 일봉 확정 대기)
 * 2. 해외 2 endpoint (.IXIC + .SPX) 일봉 fetch (HHDFS76240000)
 *    - 06:00 KST 미국 정규장 마감 + 30분 buffer (NYSE 05:30 ET ≈ 06:30 KST 여름)
 * 3. market_index_snapshot 영속 (open·high·low·close 채움 — 일봉 source)
 *
 * ### Rate Limit 정합 (ADR 0037 amend 2026-05-24)
 * - acquire(INGESTION_LOW) — adapter 내부 처리
 *
 * ### 자본 영향 0 (read-only)
 *
 * ### MarketIndexSyncJob과의 분리
 * - 1분 cron(MarketIndexSyncJob)은 현재가만 (price), open/high/low/close null
 * - 본 Job은 일봉 OHLC 별도 영속 — `MarketIndexSnapshotJpaEntity.open/high/low/close` 컬럼에 채움
 * - UNIQUE(code, ts) 멱등 — 동일 (code, 일봉 시각) 충돌 시 skip (재시도 안전)
 *
 * ### 일봉 ts 시각 convention
 * - 일봉 ts = 거래일 16:30 KST (국내) 또는 06:00 KST (해외) — UNIQUE 멱등 시각 LOCK
 * - tradeDate 자체는 daily 컬럼에 영속 (raw_payload + close)
 */
@Component
class MarketIndexDailyCloseJob(
    private val kisIndexFetcher: KisIndexFetcher,
    private val indexRepository: MarketIndexSnapshotRepository,
    @Value("\${magicjar.market.index.daily.enabled:true}")
    private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(MarketIndexDailyCloseJob::class.java)

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
    }

    /**
     * 국내 24 endpoint 일봉 backfill — 16:30 KST cron.
     *
     * @param cronTag 로깅용 (예: "16:30", "manual")
     */
    fun runDomestic(cronTag: String): MarketIndexDailyResult {
        val startedAt = Instant.now()
        if (!enabled) {
            log.debug("[market-index-daily] domestic cron={} — disabled skip", cronTag)
            return MarketIndexDailyResult(cronTag, scope = "DOMESTIC", attempted = 0, inserted = 0, failed = 0, startedAt, Instant.now())
        }

        val baseDate = LocalDate.now(KST)
        var attempted = 0
        var inserted = 0
        var failed = 0

        for (code in IndexCode.DOMESTIC_FOR_DAILY) {
            attempted++
            try {
                val dailies = kisIndexFetcher.fetchDomesticIndexDaily(code, baseDate, periodDiv = "D")
                inserted += persistDailies(dailies)
            } catch (e: Exception) {
                log.warn("[market-index-daily] domestic code={} 실패 err={}", code.code, e.message)
                failed++
            }
        }

        val result = MarketIndexDailyResult(cronTag, "DOMESTIC", attempted, inserted, failed, startedAt, Instant.now())
        log.info(
            "[market-index-daily] domestic cron={} inserted={}/{} failed={} duration={}ms",
            cronTag,
            inserted,
            attempted,
            failed,
            Duration.between(startedAt, result.finishedAt).toMillis(),
        )
        return result
    }

    /**
     * 해외 2 endpoint 일봉 backfill — 06:00 KST cron.
     */
    fun runOverseas(cronTag: String): MarketIndexDailyResult {
        val startedAt = Instant.now()
        if (!enabled) {
            log.debug("[market-index-daily] overseas cron={} — disabled skip", cronTag)
            return MarketIndexDailyResult(cronTag, "OVERSEAS", 0, 0, 0, startedAt, Instant.now())
        }

        val baseDate = LocalDate.now(KST)
        var attempted = 0
        var inserted = 0
        var failed = 0

        for (code in IndexCode.OVERSEAS_FOR_DAILY) {
            attempted++
            try {
                val dailies = kisIndexFetcher.fetchOverseasIndexDaily(code, baseDate, modp = "1")
                inserted += persistDailies(dailies)
            } catch (e: Exception) {
                log.warn("[market-index-daily] overseas code={} 실패 err={}", code.code, e.message)
                failed++
            }
        }

        val result = MarketIndexDailyResult(cronTag, "OVERSEAS", attempted, inserted, failed, startedAt, Instant.now())
        log.info(
            "[market-index-daily] overseas cron={} inserted={}/{} failed={} duration={}ms",
            cronTag,
            inserted,
            attempted,
            failed,
            Duration.between(startedAt, result.finishedAt).toMillis(),
        )
        return result
    }

    /**
     * MarketIndexDaily → MarketIndexSnapshot 변환 + 영속.
     *
     * 일봉 ts convention: tradeDate 16:30 KST를 Instant로 변환 (UQ 멱등 시각 — 1분 cron snapshot과 충돌 0).
     *
     * `internal` 가시성 — 단위 테스트 검증.
     */
    internal fun persistDailies(dailies: List<MarketIndexDaily>): Int {
        var inserted = 0
        for (daily in dailies) {
            try {
                val ts =
                    daily.tradeDate
                        .atTime(16, 30)
                        .atZone(KST)
                        .toInstant()
                val snapshot =
                    MarketIndexSnapshot(
                        code = daily.code,
                        category = daily.category,
                        ts = ts,
                        price = daily.close,
                        changeRate = null,
                        volume = daily.volume,
                        rawPayload = daily.rawPayload,
                    )
                if (indexRepository.save(snapshot)) inserted++
            } catch (e: Exception) {
                log.warn("[market-index-daily] persist 실패 code={} tradeDate={} err={}", daily.code, daily.tradeDate, e.message)
            }
        }
        return inserted
    }
}

/**
 * 일봉 backfill 결과 진단 DTO.
 */
data class MarketIndexDailyResult(
    val cronTag: String,
    val scope: String,
    val attempted: Int,
    val inserted: Int,
    val failed: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
)
