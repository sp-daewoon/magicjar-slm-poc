package kr.spdaewoon.magicjar.consumer.monitoring

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.aggregation.CandleBackfillWorker
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional

/**
 * LastCandleAtMonitor 단위 테스트 (T-CC Phase 1 — ADR 0119 amend: 일봉 단독 watchdog).
 *
 * ★ ADR 0119 — proactive cold-start 백필 폐지 + 1분봉 자동 백필 제거 정합:
 *   본 monitor 는 일봉(ONE_DAY) stale 만 감지 + CandleBackfillWorker(ONE_DAY) 트리거 + Slack alert.
 *   1분봉 stale 감지·자동 백필은 제거됨(실시간 1m 은 WS 구독 종목만, 과거 1m 은 on-demand 전용).
 *
 * 케이스 매트릭스:
 *  (1) watchlist 비어 있음 → graceful return
 *  (2) 한국석유(004090) 일봉 5개월 stale → CandleBackfillWorker(ONE_DAY) submit + Slack alert + audit row (dogfooding)
 *  (3) 1분봉만 stale (일봉 정상) → 트리거 0 (1m 자동 백필 폐지 — ADR 0119)
 *  (4) 정상 종목 (일봉 임계치 이내) → backfill 호출 0
 *  (5) 거래정지·정리매매 종목 → skip
 *  (6) 일봉 row 0건 (lastTs null) → stale 표기 + CandleBackfillWorker(ONE_DAY) submit
 *  (7) Slack sender 미등록 → graceful skip
 *
 * AI 호출 0 — 룰 5 정합 (W3 트랙).
 */
class LastCandleAtMonitorTest {
    private val watchlistJpaRepository = mockk<ConsumerWatchlistJpaRepository>()
    private val candleRepository = mockk<CandleRepository>()
    private val instrumentJpaRepository = mockk<ConsumerInstrumentJpaRepository>()
    private val auditRepository = mockk<ConsumerAuditTrailJpaRepository>(relaxed = true)
    private val slackSender = mockk<SlackWebhookSender>(relaxed = true)
    private val slackSenderProvider = mockk<ObjectProvider<SlackWebhookSender>>()
    private val candleBackfillWorker = mockk<CandleBackfillWorker>(relaxed = true)

    private val kst = ZoneId.of("Asia/Seoul")

    private fun newMonitor(): LastCandleAtMonitor {
        every { slackSenderProvider.getIfAvailable() } returns slackSender
        return LastCandleAtMonitor(
            watchlistJpaRepository = watchlistJpaRepository,
            candleRepository = candleRepository,
            instrumentJpaRepository = instrumentJpaRepository,
            auditRepository = auditRepository,
            slackSenderProvider = slackSenderProvider,
            candleBackfillWorker = candleBackfillWorker,
        )
    }

    private fun watchlist(vararg codes: String): List<ConsumerWatchlistJpaEntity> =
        codes.mapIndexed { idx, c ->
            ConsumerWatchlistJpaEntity(id = (idx + 1).toLong(), userId = "u1", code = c)
        }

    private fun normalInstrument(code: String): ConsumerInstrumentJpaEntity =
        ConsumerInstrumentJpaEntity(
            code = code,
            name = "Test-$code",
            market = "KOSPI",
            type = "STOCK",
            active = true,
            tradingHalt = false,
            liquidation = false,
        )

    private fun haltedInstrument(code: String): ConsumerInstrumentJpaEntity =
        ConsumerInstrumentJpaEntity(
            code = code,
            name = "Halted-$code",
            market = "KOSPI",
            type = "STOCK",
            active = true,
            tradingHalt = true,
            liquidation = false,
        )

    @Test
    fun `watchlist 비어 있음 graceful return`() {
        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns emptyList()

        val result = newMonitor().runMonitor("test")

        assertThat(result).isEmpty()
        verify(exactly = 0) { candleBackfillWorker.submit(any(), any(), any()) }
        verify(exactly = 0) { slackSender.sendDirect(any()) }
    }

    @Test
    fun `한국석유 일봉 5개월 stale 검출 시 일봉 backfill submit + Slack alert + audit 발행`() {
        val code = "004090" // 한국석유 — dogfooding evidence

        // 2025-12-24 KST 00:00:00 → 약 5개월 stale (사용자 dogfooding 인용)
        val staleDailyTs =
            LocalDate
                .of(2025, 12, 24)
                .atStartOfDay(kst)
                .toInstant()
        // 1분봉은 stale 이어도 더 이상 검사하지 않음 (ADR 0119) — 일봉만 검출 대상
        val normalMinuteTs = Instant.now().minusSeconds(60 * 60 * 2)

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(normalInstrument(code))
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns staleDailyTs
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns normalMinuteTs

        val auditSlot = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(auditSlot)) } answers { firstArg() }

        val slackSlot = slot<String>()
        every { slackSender.sendDirect(capture(slackSlot)) } returns true

        val stale = newMonitor().runMonitor("dogfooding")

        // 일봉 stale 1건만 검출 (1분봉은 검사 대상 아님)
        assertThat(stale).hasSize(1)
        assertThat(stale[0].code).isEqualTo(code)
        assertThat(stale[0].interval).isEqualTo(CandleInterval.ONE_DAY)
        assertThat(stale[0].lastTs).isEqualTo(staleDailyTs)
        // 5개월 ≈ 100+ 거래일 (정확 값 변동 OK — 임계치 5 초과만 검증)
        assertThat(stale[0].staleBusinessDays).isGreaterThan(LastCandleAtMonitor.STALE_THRESHOLD_DAILY_BUSINESS_DAYS)

        // 일봉 backfill submit 1회 (cold-start 일괄 재수집 제거 — CandleBackfillWorker ONE_DAY 단독)
        verify(exactly = 1) { candleBackfillWorker.submit(code, CandleInterval.ONE_DAY, "monitor-daily") }
        // 1m backfill 은 절대 호출되지 않음 (ADR 0119)
        verify(exactly = 0) { candleBackfillWorker.submit(code, CandleInterval.ONE_MIN, any()) }
        // Slack alert 1회 — 한국석유 dogfooding evidence 인용 가능 메시지
        verify(exactly = 1) { slackSender.sendDirect(any()) }
        assertThat(slackSlot.captured).contains(code)
        assertThat(slackSlot.captured).contains("stale")
        // audit row 1건 — 운영 audit baseline
        assertThat(auditSlot.captured.action).isEqualTo("CANDLE_LAST_AT_MONITOR")
        assertThat(auditSlot.captured.entityType).isEqualTo("candle")
        assertThat(auditSlot.captured.detailJson).contains("\"trigger\":\"dogfooding\"")
        assertThat(auditSlot.captured.detailJson).contains("\"stale\":1")
        assertThat(auditSlot.captured.detailJson).contains("\"triggered\":1")
    }

    @Test
    fun `1분봉만 stale 이고 일봉 정상이면 트리거 0 (1m 자동 백필 폐지 - ADR 0119)`() {
        val code = "005930"

        // 일봉은 정상 (최신)
        val normalDailyTs = Instant.now().minusSeconds(60 * 60 * 2)
        // 1분봉은 5일 전 stale — 그러나 ADR 0119로 검사·트리거 안 함
        val staleMinuteTs =
            LocalDate
                .now(kst)
                .minusDays(5)
                .atStartOfDay(kst)
                .toInstant()

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(normalInstrument(code))
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns normalDailyTs
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns staleMinuteTs

        val stale = newMonitor().runMonitor("test")

        // 1m stale 은 검출 대상에서 제거 → stale 0건, backfill 0건
        assertThat(stale).isEmpty()
        verify(exactly = 0) { candleBackfillWorker.submit(any(), any(), any()) }
    }

    @Test
    fun `정상 종목 일봉 임계치 이내 backfill 호출 0`() {
        val code = "000660"

        val normalDailyTs = Instant.now().minusSeconds(60 * 60 * 2)
        val normalMinuteTs = Instant.now().minusSeconds(60 * 30)

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(normalInstrument(code))
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns normalDailyTs
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns normalMinuteTs

        val stale = newMonitor().runMonitor("test")

        assertThat(stale).isEmpty()
        verify(exactly = 0) { candleBackfillWorker.submit(any(), any(), any()) }
        // 정상 시 Slack alert 발화 0 (spam 회피)
        verify(exactly = 0) { slackSender.sendDirect(any()) }
    }

    @Test
    fun `거래정지·정리매매 종목 자동 skip backfill 호출 0`() {
        val code = "999999" // 거래정지 가정

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(haltedInstrument(code))
        // candleRepository 호출 안 됨 — skip path

        val stale = newMonitor().runMonitor("test")

        assertThat(stale).isEmpty()
        verify(exactly = 0) { candleBackfillWorker.submit(any(), any(), any()) }
        verify(exactly = 0) { candleRepository.findMaxTsByCodeAndInterval(any(), any()) }
    }

    @Test
    fun `일봉 row 0건 lastTs null 시 stale 표기 + 일봉 backfill submit`() {
        val code = "035420"

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(normalInstrument(code))
        // 일봉 row 0건 (1분봉은 검사 대상 아님 — stub 만 둠)
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns null
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns null

        val stale = newMonitor().runMonitor("test")

        // 일봉만 stale 표기 (1m 검사 제거 — ADR 0119)
        assertThat(stale).hasSize(1)
        assertThat(stale[0].interval).isEqualTo(CandleInterval.ONE_DAY)
        assertThat(stale[0].staleBusinessDays).isEqualTo(Long.MAX_VALUE)
        assertThat(stale[0].lastTs).isNull()
        verify(exactly = 1) { candleBackfillWorker.submit(code, CandleInterval.ONE_DAY, "monitor-daily") }
    }

    @Test
    fun `Slack sender 미등록 시 graceful skip - alert 발화 0`() {
        val code = "004090"
        val staleDailyTs = LocalDate.of(2025, 12, 24).atStartOfDay(kst).toInstant()
        val normalMinuteTs = Instant.now().minusSeconds(60 * 60 * 2)

        every { watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc() } returns watchlist(code)
        every { instrumentJpaRepository.findById(code) } returns Optional.of(normalInstrument(code))
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_DAY) } returns staleDailyTs
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns normalMinuteTs

        // SlackWebhookSender 미등록 (예: notification 비활성 환경)
        every { slackSenderProvider.getIfAvailable() } returns null

        val monitor =
            LastCandleAtMonitor(
                watchlistJpaRepository = watchlistJpaRepository,
                candleRepository = candleRepository,
                instrumentJpaRepository = instrumentJpaRepository,
                auditRepository = auditRepository,
                slackSenderProvider = slackSenderProvider,
                candleBackfillWorker = candleBackfillWorker,
            )

        val stale = monitor.runMonitor("test")

        // stale 검출 + 일봉 backfill 발화는 정상
        assertThat(stale).hasSize(1)
        verify(exactly = 1) { candleBackfillWorker.submit(code, CandleInterval.ONE_DAY, "monitor-daily") }
        // Slack 발화는 graceful skip
        verify(exactly = 0) { slackSender.sendDirect(any()) }
    }
}
