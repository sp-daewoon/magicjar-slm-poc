package kr.spdaewoon.magicjar.batch.job.market

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.index.IndexCategory
import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexDaily
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.index.SectorIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.index.port.KisIndexFetcher
import kr.spdaewoon.magicjar.domain.market.index.port.MarketIndexSnapshotRepository
import kr.spdaewoon.magicjar.domain.market.index.port.SectorIndexSnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * MarketIndexSyncJob + MarketIndexDailyCloseJob 단위 테스트.
 *
 * mock fetcher / repository / kafkaTemplate 주입으로 시나리오 검증:
 *  - sync Job: 26 endpoint 일괄 + sector ranking + Kafka publish
 *  - daily Job: domestic 24 + overseas 2 + tradeDate→Instant conversion
 *  - disabled 시 zero-op (자본 path 영향 0)
 */
class MarketIndexJobsTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `MarketIndexSyncJob disabled 시 zero-op — Kafka 호출 0`() {
        val fetcher = mock<KisIndexFetcher>()
        val indexRepo = mock<MarketIndexSnapshotRepository>()
        val sectorRepo = mock<SectorIndexSnapshotRepository>()
        val kafka = mock<KafkaTemplate<String, String>>()
        val job =
            MarketIndexSyncJob(
                kisIndexFetcher = fetcher,
                indexRepository = indexRepo,
                sectorRepository = sectorRepo,
                kafkaTemplate = kafka,
                objectMapper = objectMapper,
                enabled = false,
            )

        val result = job.run("test")

        assertThat(result.indexAttempted).isZero()
        assertThat(result.sectorAttempted).isZero()
        verify(fetcher, org.mockito.kotlin.never()).fetchDomesticIndex(any())
    }

    @Test
    fun `MarketIndexSyncJob 정상 — 일부 fetch null + 일부 success scenario`() {
        val fetcher = mock<KisIndexFetcher>()
        val indexRepo = mock<MarketIndexSnapshotRepository>()
        val sectorRepo = mock<SectorIndexSnapshotRepository>()
        val kafka = mock<KafkaTemplate<String, String>>()

        // KOSPI는 성공, 나머지는 null (disabled simul)
        val now = Instant.now()
        val kospiSnapshot =
            MarketIndexSnapshot(
                code = "0001",
                category = IndexCategory.DOMESTIC_INDEX,
                ts = now,
                price = BigDecimal("2640.5"),
                changeRate = BigDecimal("0.5"),
                volume = 1_000_000L,
            )
        // 모든 fetchDomesticIndex 호출 → 모두 null (KOSPI 제외)
        whenever(fetcher.fetchDomesticIndex(any())).thenReturn(null)
        whenever(fetcher.fetchDomesticIndex(IndexCode.KOSPI)).thenReturn(kospiSnapshot)
        whenever(fetcher.fetchOverseasIndex(any())).thenReturn(null)
        whenever(fetcher.fetchDomesticSectorRanking(any(), any())).thenReturn(emptyList())
        whenever(indexRepo.save(any<MarketIndexSnapshot>())).thenReturn(true)

        val job =
            MarketIndexSyncJob(
                kisIndexFetcher = fetcher,
                indexRepository = indexRepo,
                sectorRepository = sectorRepo,
                kafkaTemplate = kafka,
                objectMapper = objectMapper,
                enabled = true,
            )

        val result = job.run("test")

        // 26 endpoint 시도 (24 국내 + 2 해외) — IndexCode.ALL_FOR_MINUTE_SYNC.size
        assertThat(result.indexAttempted).isEqualTo(IndexCode.ALL_FOR_MINUTE_SYNC.size)
        assertThat(result.indexInserted).isEqualTo(1) // KOSPI 1건만 success
        assertThat(result.failed).isZero() // null 응답은 failed 아님

        // KafkaTemplate publish KOSPI 1건 partition key
        val keyCaptor = argumentCaptor<String>()
        verify(kafka).send(
            org.mockito.kotlin.eq(MarketIndexSyncJob.TOPIC_INDEX),
            keyCaptor.capture(),
            any<String>(),
        )
        assertThat(keyCaptor.firstValue).isEqualTo("0001")
    }

    @Test
    fun `MarketIndexSyncJob — sector ranking publish 검증`() {
        val fetcher = mock<KisIndexFetcher>()
        val indexRepo = mock<MarketIndexSnapshotRepository>()
        val sectorRepo = mock<SectorIndexSnapshotRepository>()
        val kafka = mock<KafkaTemplate<String, String>>()

        whenever(fetcher.fetchDomesticIndex(any())).thenReturn(null)
        whenever(fetcher.fetchOverseasIndex(any())).thenReturn(null)
        whenever(fetcher.fetchDomesticSectorRanking(any(), any())).thenReturn(
            listOf(
                SectorIndexSnapshot(
                    sectorCode = "0018",
                    sectorName = "금융업",
                    ts = Instant.now(),
                    price = BigDecimal("550.20"),
                    changeRate = BigDecimal("1.25"),
                    tradeValue = BigDecimal("8500000000000"),
                ),
            ),
        )
        whenever(sectorRepo.save(any<SectorIndexSnapshot>())).thenReturn(true)

        val job =
            MarketIndexSyncJob(
                kisIndexFetcher = fetcher,
                indexRepository = indexRepo,
                sectorRepository = sectorRepo,
                kafkaTemplate = kafka,
                objectMapper = objectMapper,
                enabled = true,
            )
        val result = job.run("test")

        assertThat(result.sectorAttempted).isEqualTo(1)
        assertThat(result.sectorInserted).isEqualTo(1)
        verify(kafka).send(
            org.mockito.kotlin.eq(MarketIndexSyncJob.TOPIC_SECTOR),
            org.mockito.kotlin.eq("ALL"),
            any<String>(),
        )
    }

    @Test
    fun `MarketIndexDailyCloseJob domestic — DOMESTIC_FOR_DAILY 24건 iterate + persist`() {
        val fetcher = mock<KisIndexFetcher>()
        val indexRepo = mock<MarketIndexSnapshotRepository>()

        val sampleDaily =
            MarketIndexDaily(
                code = "0001",
                category = IndexCategory.DOMESTIC_INDEX,
                tradeDate = LocalDate.of(2026, 5, 23),
                open = BigDecimal("2640"),
                high = BigDecimal("2650"),
                low = BigDecimal("2630"),
                close = BigDecimal("2645"),
                volume = 1_000_000L,
            )
        // 첫 호출만 1건 반환, 나머지는 empty
        whenever(fetcher.fetchDomesticIndexDaily(any(), any(), any())).thenReturn(emptyList())
        whenever(
            fetcher.fetchDomesticIndexDaily(IndexCode.KOSPI, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")), "D"),
        ).thenReturn(listOf(sampleDaily))
        whenever(indexRepo.save(any<MarketIndexSnapshot>())).thenReturn(true)

        val job =
            MarketIndexDailyCloseJob(
                kisIndexFetcher = fetcher,
                indexRepository = indexRepo,
                enabled = true,
            )
        val result = job.runDomestic("16:30")

        // DOMESTIC_FOR_DAILY 24건 호출
        assertThat(result.attempted).isEqualTo(IndexCode.DOMESTIC_FOR_DAILY.size)
        assertThat(result.inserted).isEqualTo(1) // KOSPI 1건만 영속
    }

    @Test
    fun `persistDailies — tradeDate to 16시30분 KST Instant 변환 정합`() {
        val fetcher = mock<KisIndexFetcher>()
        val indexRepo = mock<MarketIndexSnapshotRepository>()
        whenever(indexRepo.save(any<MarketIndexSnapshot>())).thenReturn(true)

        val job =
            MarketIndexDailyCloseJob(
                kisIndexFetcher = fetcher,
                indexRepository = indexRepo,
                enabled = true,
            )

        val daily =
            MarketIndexDaily(
                code = "0001",
                category = IndexCategory.DOMESTIC_INDEX,
                tradeDate = LocalDate.of(2026, 5, 23),
                open = BigDecimal("2640"),
                high = BigDecimal("2650"),
                low = BigDecimal("2630"),
                close = BigDecimal("2645"),
            )
        val inserted = job.persistDailies(listOf(daily))
        assertThat(inserted).isEqualTo(1)

        val captor = argumentCaptor<MarketIndexSnapshot>()
        verify(indexRepo).save(captor.capture())
        val expectedTs =
            LocalDate
                .of(2026, 5, 23)
                .atTime(16, 30)
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toInstant()
        assertThat(captor.firstValue.ts).isEqualTo(expectedTs)
        assertThat(captor.firstValue.price).isEqualByComparingTo(BigDecimal("2645"))
    }
}
