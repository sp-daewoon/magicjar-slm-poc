package kr.spdaewoon.magicjar.batch.job.market

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.index.IndexCategory
import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.index.SectorIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.index.port.KisIndexFetcher
import kr.spdaewoon.magicjar.domain.market.index.port.MarketIndexSnapshotRepository
import kr.spdaewoon.magicjar.domain.market.index.port.SectorIndexSnapshotRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * T-MARKET 1분 cron Job — 지수·섹터 snapshot 일괄 발사 (ADR 0100 §2-4 LOCK).
 *
 * ### 책임
 * 1. ALL_FOR_MINUTE_SYNC 26 endpoint (국내 3 지수 + 21 업종 + 해외 2 지수) 일괄 fetch
 * 2. market_index_snapshot 영속 (ON CONFLICT DO NOTHING 멱등)
 * 3. KOSPI 21 섹터 ranking 1회 호출(FHPUP02140000) → sector_index_snapshot 영속
 * 4. Kafka publish (market.index.updated.v1 × N · market.sector.updated.v1 × 1)
 *
 * ### 호출 매트릭스 (ADR 0100 §2-4)
 *   국내: 24 req/min (3 지수 + 21 업종)
 *   해외: 2 req/min (.IXIC + .SPX)
 *   섹터 ranking: 1 req/min (1 page · KOSPI 21 업종 일괄 반환)
 *   총 = ~27 req/min (raw 26 + sector ranking 1) — INGESTION_LOW priority Bucket4j 흡수
 *
 * ### Rate Limit 정합 (ADR 0037 amend 2026-05-24)
 * - 각 adapter 내부에서 acquire(INGESTION_LOW) 호출 — Job은 라우팅만
 *
 * ### 자본 영향 0 (ADR 0100 §4-1·§4-2)
 * - read-only KIS 호출 + market_index_snapshot·sector_index_snapshot 영속만
 * - 주문·Risk·Outbox·KillSwitch 0 touch
 *
 * ### 영업일 게이트
 * - scheduler MON-FRI cron + 시간 09:00~15:30 KST 진입 자체가 1차 게이트
 * - 본 Job 내부는 KIS adapter의 gate.shouldSkip()으로 disabled 환경 자연 우회
 */
@Component
class MarketIndexSyncJob(
    private val kisIndexFetcher: KisIndexFetcher,
    private val indexRepository: MarketIndexSnapshotRepository,
    private val sectorRepository: SectorIndexSnapshotRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.market.index.sync.enabled:true}")
    private val enabled: Boolean,
    /**
     * 지수·섹터 호출 간 gap (ms · ADR 0116 §2-D 스케줄러 호출 분산).
     *
     * 26 req/min burst 를 시간 분산하여 BG 버킷(refillIntervally 3/s) 순간 소진 + EGW00201 방지.
     * 250ms × 26 ≈ 6.5초 분산 → 1분 윈도 안 균등 발사. 토큰 버킷은 순간 차단만 할 뿐
     * 순간 burst 의 시간 분산은 caller(본 Job) 책임 (ADR 0116 §2-D 원칙).
     */
    @Value("\${magicjar.market.index.sync.inter-request-delay-ms:250}")
    private val interRequestDelayMs: Long = 0L,
) {
    private val log = LoggerFactory.getLogger(MarketIndexSyncJob::class.java)

    companion object {
        const val TOPIC_INDEX = KafkaTopics.Market.INDEX_UPDATED
        const val TOPIC_SECTOR = KafkaTopics.Market.SECTOR_UPDATED
        const val SCHEMA_VERSION = 1
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val OVERSEAS_DAILY_FALLBACK_LOOKBACK_DAYS = 7
        private val OVERSEAS_DAILY_FALLBACK_CACHE_TTL: Duration = Duration.ofHours(36)
    }

    /**
     * 1분 cron 발사 — domestic + overseas index + sector ranking 일괄.
     *
     * @param cronTag 로깅용 cron 시각 태그 (예: "minute", "boot")
     * @return MarketIndexSyncResult — 진단·테스트 evidence 용
     */
    fun run(cronTag: String): MarketIndexSyncResult {
        val startedAt = Instant.now()

        if (!enabled) {
            log.debug("[market-index-sync] cron={} — magicjar.market.index.sync.enabled=false skip", cronTag)
            return MarketIndexSyncResult(
                cronTag = cronTag,
                indexAttempted = 0,
                indexInserted = 0,
                sectorAttempted = 0,
                sectorInserted = 0,
                failed = 0,
                startedAt = startedAt,
                finishedAt = Instant.now(),
            )
        }

        var indexAttempted = 0
        var indexInserted = 0
        var failed = 0

        for (code in IndexCode.ALL_FOR_MINUTE_SYNC) {
            indexAttempted++
            try {
                val snapshot = fetchSnapshot(code)
                if (snapshot != null) {
                    val saved = indexRepository.save(snapshot)
                    if (saved) {
                        indexInserted++
                        publishIndexUpdate(snapshot)
                    }
                }
            } catch (e: Exception) {
                log.warn("[market-index-sync] code={} 실패 err={}", code.code, e.message)
                failed++
            } finally {
                delayBetweenKisCalls()
            }
        }

        // KOSPI 섹터 ranking 1회 호출 (21 업종 일괄)
        var sectorAttempted = 0
        var sectorInserted = 0
        try {
            val sectors = kisIndexFetcher.fetchDomesticSectorRanking(marketClsCode = "K", belongClsCode = "0")
            sectorAttempted = sectors.size
            for (sector in sectors) {
                try {
                    val saved = sectorRepository.save(sector)
                    if (saved) sectorInserted++
                } catch (e: Exception) {
                    log.warn("[market-index-sync] sector={} 영속 실패 err={}", sector.sectorCode, e.message)
                    failed++
                }
            }
            if (sectors.isNotEmpty()) {
                publishSectorUpdate(sectors)
            }
        } catch (e: Exception) {
            log.warn("[market-index-sync] sector ranking 실패 err={}", e.message)
            failed++
        }

        val result =
            MarketIndexSyncResult(
                cronTag = cronTag,
                indexAttempted = indexAttempted,
                indexInserted = indexInserted,
                sectorAttempted = sectorAttempted,
                sectorInserted = sectorInserted,
                failed = failed,
                startedAt = startedAt,
                finishedAt = Instant.now(),
            )
        log.info(
            "[market-index-sync] cron={} index={}/{} sector={}/{} failed={} duration={}ms",
            cronTag,
            indexInserted,
            indexAttempted,
            sectorInserted,
            sectorAttempted,
            failed,
            Duration.between(startedAt, result.finishedAt).toMillis(),
        )
        return result
    }

    private fun fetchSnapshot(code: IndexCode): MarketIndexSnapshot? =
        when (code.category) {
            IndexCategory.OVERSEAS_INDEX ->
                kisIndexFetcher.fetchOverseasIndex(code)
                    ?: fetchLatestOverseasDailyAsSnapshot(code)
            IndexCategory.DOMESTIC_INDEX, IndexCategory.DOMESTIC_SECTOR ->
                kisIndexFetcher.fetchDomesticIndex(code)
        }

    /**
     * 미국 정규장 외 시간에는 해외 현재가 endpoint 가 0 가격을 줄 수 있다.
     * 한국 장중 화면에서는 최신 미국 종가라도 보여야 하므로 dailyprice 최신 close 를 snapshot 으로 저장한다.
     */
    private fun fetchLatestOverseasDailyAsSnapshot(code: IndexCode): MarketIndexSnapshot? {
        val now = Instant.now()
        indexRepository
            .findLatest(code.code, 1)
            .firstOrNull()
            ?.takeIf { Duration.between(it.ts, now).abs() <= OVERSEAS_DAILY_FALLBACK_CACHE_TTL }
            ?.let { return it.copy(ts = now) }

        val today = LocalDate.now(KST)
        val latestDaily =
            (0..OVERSEAS_DAILY_FALLBACK_LOOKBACK_DAYS)
                .firstNotNullOfOrNull { daysAgo ->
                    val daily =
                        kisIndexFetcher
                            .fetchOverseasIndexDaily(code, today.minusDays(daysAgo.toLong()), modp = "1")
                            .maxByOrNull { it.tradeDate }
                    delayBetweenKisCalls()
                    daily
                }
                ?: run {
                    log.debug("[market-index-sync] overseas daily fallback empty code={}", code.code)
                    return null
                }
        return MarketIndexSnapshot(
            code = latestDaily.code,
            category = latestDaily.category,
            ts = Instant.now(),
            price = latestDaily.close,
            changeRate = null,
            volume = latestDaily.volume,
            rawPayload = latestDaily.rawPayload,
        )
    }

    private fun delayBetweenKisCalls() {
        if (interRequestDelayMs <= 0L) return
        try {
            Thread.sleep(interRequestDelayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * market.index.updated.v1 publish — partition key = code.
     *
     * 스키마 v1 (ADR 0100 §2-6 정합):
     *   { eventId, code, category, ts, price, changeRate, volume, occurredAt, schemaVersion }
     */
    internal fun publishIndexUpdate(snapshot: MarketIndexSnapshot) {
        try {
            val payload =
                mapOf(
                    "eventId" to
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    "code" to snapshot.code,
                    "category" to snapshot.category.name,
                    "ts" to snapshot.ts.toString(),
                    "price" to snapshot.price.toPlainString(),
                    "changeRate" to snapshot.changeRate?.toPlainString(),
                    "volume" to snapshot.volume,
                    "occurredAt" to Instant.now().toString(),
                    "schemaVersion" to SCHEMA_VERSION,
                )
            kafkaTemplate.send(TOPIC_INDEX, snapshot.code, objectMapper.writeValueAsString(payload))
        } catch (e: Exception) {
            log.warn("[market-index-sync] Kafka publish 실패 (비치명) code={} err={}", snapshot.code, e.message)
        }
    }

    /**
     * market.sector.updated.v1 publish — single broadcast.
     */
    internal fun publishSectorUpdate(sectors: List<SectorIndexSnapshot>) {
        try {
            val payload =
                mapOf(
                    "eventId" to
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    "ts" to (sectors.firstOrNull()?.ts?.toString() ?: Instant.now().toString()),
                    "sectors" to
                        sectors.map {
                            mapOf(
                                "sectorCode" to it.sectorCode,
                                "sectorName" to it.sectorName,
                                "price" to it.price.toPlainString(),
                                "changeRate" to it.changeRate.toPlainString(),
                                "tradeValue" to it.tradeValue?.toPlainString(),
                            )
                        },
                    "occurredAt" to Instant.now().toString(),
                    "schemaVersion" to SCHEMA_VERSION,
                )
            kafkaTemplate.send(TOPIC_SECTOR, "ALL", objectMapper.writeValueAsString(payload))
        } catch (e: Exception) {
            log.warn("[market-index-sync] sector Kafka publish 실패 (비치명) err={}", e.message)
        }
    }
}

/**
 * 1분 cron 결과 진단 DTO — log + 테스트 evidence.
 */
data class MarketIndexSyncResult(
    val cronTag: String,
    val indexAttempted: Int,
    val indexInserted: Int,
    val sectorAttempted: Int,
    val sectorInserted: Int,
    val failed: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
)
