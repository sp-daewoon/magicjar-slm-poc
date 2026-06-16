package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.CandleReconciliationStateStore
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleGapDetectorPort
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import kotlinx.datetime.Instant as KtInstant

/**
 * CandleBackfillWorker 단위 테스트 — 햇징 룰 10건 검증 (ADR 0086 §2-1).
 *
 * 룰 ↔ 테스트 매핑:
 *   #1 (Double Verification) → sanity_OHLC, sanity_price_range, sanity_volume_negative, sanity_time_monotonic
 *   #2 (Dry-run)              → dry_run_no_db_write
 *   #3 (Rollback)             → rollback_audit_recorded, backfill_batch_id_format
 *   #4 (Idempotency)          → idempotency_uses_saveAll (UPSERT 위임)
 *   #5 (Source-of-truth)      → source_of_truth_kis_raw_only (source 필드 LOCK)
 *   #6 (False positive 가드)  → skip_trading_halt
 *   #7 (False negative 가드)  → no_gaps_skipped_audit
 *   #8 (Retry + KillSwitch)   → killswitch_on_total_fail, retry_3times
 *   #9 (Audit trail)          → audit_5_paths (START·SUCCESS·FAIL·SKIPPED·ROLLBACK)
 *   #10 (운영자 우회)         → CandleBackfillAdminControllerTest 별도
 */
class CandleBackfillWorkerTest {
    private val candleGapDetector = mockk<CandleGapDetectorPort>()
    private val candleRepository = mockk<CandleRepository>()
    private val kisRestPort = mockk<KisRestPort>()
    private val instrumentJpaRepository = mockk<ConsumerInstrumentJpaRepository>()
    private val auditRepository = mockk<ConsumerAuditTrailJpaRepository>()
    private val aggregateBackfillRunner = mockk<AggregateBackfillRunner>()
    private val reconciliationStateStore = mockk<CandleReconciliationStateStore>(relaxed = true)
    private val slackSender = mockk<SlackWebhookSender>()
    private val killSwitchRepo = mockk<KillSwitchJpaRepository>()

    // rate-limit safety #1·#2·#3 (2026-06-04) — 가드/throttle/properties mock.
    private val backfillGapGuardStore = mockk<BackfillGapGuardStore>(relaxed = true)
    private val backfillThrottleGate = mockk<BackfillThrottleGate>(relaxed = true)
    private val chartCandleProperties =
        kr.spdaewoon.magicjar.consumer.config
            .ChartCandleProperties()

    private val slackProvider =
        mockk<ObjectProvider<SlackWebhookSender>>().also {
            every { it.getIfAvailable() } returns slackSender
        }

    private val ksProvider =
        mockk<ObjectProvider<KillSwitchJpaRepository>>().also {
            every { it.getIfAvailable() } returns killSwitchRepo
        }

    private val objectMapper = jacksonObjectMapper()

    private fun newWorker(dryRun: Boolean = false): CandleBackfillWorker {
        // 가드 default: 회복 불가 아님 + give-up 아님 (기존 동작 보존 — 회귀 0)
        every { backfillGapGuardStore.isUnrecoverable(any(), any()) } returns false
        every { backfillGapGuardStore.recordTotalMissAndShouldGiveUp(any(), any()) } returns false
        return CandleBackfillWorker(
            candleGapDetector = candleGapDetector,
            candleRepository = candleRepository,
            kisRestPort = kisRestPort,
            instrumentJpaRepository = instrumentJpaRepository,
            auditRepository = auditRepository,
            aggregateBackfillRunner = aggregateBackfillRunner,
            reconciliationStateStore = reconciliationStateStore,
            slackSenderProvider = slackProvider,
            killSwitchJpaRepositoryProvider = ksProvider,
            objectMapper = objectMapper,
            backfillGapGuardStore = backfillGapGuardStore,
            backfillThrottleGate = backfillThrottleGate,
            chartCandleProperties = chartCandleProperties,
            dryRunPropertyDefault = dryRun,
            windowDaysDefault = 30,
        )
    }

    private fun candle(
        code: String = "004090",
        tsEpochSec: Long,
        o: String = "100",
        h: String = "110",
        l: String = "95",
        c: String = "105",
        volume: Long = 1000L,
        interval: CandleInterval = CandleInterval.ONE_DAY,
    ): Candle =
        Candle(
            code = code,
            interval = interval,
            ts = KtInstant.fromEpochSeconds(tsEpochSec),
            openRaw = BigDecimal(o),
            highRaw = BigDecimal(h),
            lowRaw = BigDecimal(l),
            closeRaw = BigDecimal(c),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = volume,
            source = "KIS",
        )

    private fun setupNoInstrument(code: String = "004090") {
        every { instrumentJpaRepository.findById(code) } returns Optional.empty()
    }

    private fun setupAuditCapture() {
        every { auditRepository.save(any<ConsumerAuditTrailJpaEntity>()) } answers
            { firstArg<ConsumerAuditTrailJpaEntity>() }
    }

    // ---------- 룰 #1 sanity check ----------

    @Test
    fun `룰 #1 OHLC sanity — high lt close 위반 candle 제외`() {
        val worker = newWorker()
        val valid = candle(tsEpochSec = 1_700_000_000L)
        // high=90 < close=105 위반
        val invalid =
            candle(
                tsEpochSec = 1_700_086_400L,
                h = "90", // high < close
                c = "105",
            )
        val result = worker.sanityCheck(listOf(valid, invalid), "test-batch")
        assertThat(result).hasSize(1)
        assertThat(result[0].ts).isEqualTo(valid.ts)
    }

    @Test
    fun `룰 #1 price range — 음수 가격 제외 + 1e9 초과 제외`() {
        val worker = newWorker()
        val negative = candle(tsEpochSec = 1_700_000_000L, o = "-1")
        val overflow = candle(tsEpochSec = 1_700_086_400L, c = "2000000000")
        val valid = candle(tsEpochSec = 1_700_172_800L)
        val result = worker.sanityCheck(listOf(negative, overflow, valid), "test-batch")
        assertThat(result).hasSize(1)
        assertThat(result[0].ts).isEqualTo(valid.ts)
    }

    @Test
    fun `룰 #1 volume — 음수 volume 제외`() {
        val worker = newWorker()
        val invalid = candle(tsEpochSec = 1_700_000_000L, volume = -1L)
        val valid = candle(tsEpochSec = 1_700_086_400L)
        val result = worker.sanityCheck(listOf(invalid, valid), "test-batch")
        assertThat(result).hasSize(1)
        assertThat(result[0].volume).isEqualTo(1000L)
    }

    @Test
    fun `룰 #1 time monotonic — ts 역순 candle 제외`() {
        val worker = newWorker()
        val first = candle(tsEpochSec = 1_700_086_400L)
        val outOfOrder = candle(tsEpochSec = 1_700_000_000L) // 이전 ts
        val result = worker.sanityCheck(listOf(first, outOfOrder), "test-batch")
        assertThat(result).hasSize(1)
        assertThat(result[0].ts.epochSeconds).isEqualTo(1_700_086_400L)
    }

    // ---------- 룰 #2 Dry-run ----------

    @Test
    fun `룰 #2 dry-run — KIS fetch 0 + DB 적재 0 + audit SUCCESS dryRun true`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns
            listOf(Instant.ofEpochSecond(1_700_000_000L))

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = true)

        assertThat(result.dryRun).isTrue
        assertThat(result.insertedCount).isEqualTo(0)
        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.SUCCESS)
        // KIS fetch 호출 0
        verify(exactly = 0) { kisRestPort.fetchCandles(any(), any(), any()) }
        // DB saveAll 호출 0
        verify(exactly = 0) { candleRepository.saveAll(any()) }
        // audit START + SUCCESS 2건
        verify(atLeast = 2) { auditRepository.save(any<ConsumerAuditTrailJpaEntity>()) }
    }

    // ---------- 룰 #3 Rollback ----------

    @Test
    fun `룰 #3 backfill_batch_id — 형식 LOCK candle-backfill code interval epochMs UUID`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns emptyList()

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(result.backfillBatchId).matches("candle-backfill:004090:1d:\\d+-[0-9a-f-]+")
    }

    @Test
    fun `룰 #3 recordRollback — audit CANDLE_BACKFILL_ROLLBACK 발행`() {
        val worker = newWorker()
        setupAuditCapture()
        val captured = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { captured.captured }

        worker.recordRollback(
            backfillBatchId = "candle-backfill:004090:1d:1700000000000-uuid",
            deletedCount = 42,
            reason = "manual",
        )

        assertThat(captured.captured.action).isEqualTo("CANDLE_BACKFILL_ROLLBACK")
        assertThat(captured.captured.detailJson).contains("deletedCount\":42")
        assertThat(captured.captured.detailJson).contains("reason\":\"manual\"")
    }

    // ---------- 룰 #4 Idempotency ----------

    @Test
    fun `룰 #4 idempotency — saveAll 단일 호출로 UPSERT 위임 (ON CONFLICT DO NOTHING)`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        val gapTs = Instant.ofEpochSecond(1_700_000_000L)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns listOf(gapTs)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L))
        val saveSlot = slot<List<Candle>>()
        every { candleRepository.saveAll(capture(saveSlot)) } returns 1

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        // saveAll 정확히 1회 호출 (idempotency UPSERT 위임)
        verify(exactly = 1) { candleRepository.saveAll(any()) }
        assertThat(result.insertedCount).isEqualTo(1)
        assertThat(saveSlot.captured).hasSize(1)
    }

    @Test
    fun `ADR0105 1m backfill 성공 시 합산봉을 upsert 재생성한다`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        val gapTs = Instant.ofEpochSecond(1_700_000_000L)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_MIN, 30) } returns listOf(gapTs)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_MIN, 1) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L, interval = CandleInterval.ONE_MIN))
        every { candleRepository.saveAll(any()) } returns 1
        every { aggregateBackfillRunner.backfill("004090") } returns mapOf("3m" to 1, "5m" to 1)

        val result = worker.runBackfill("004090", CandleInterval.ONE_MIN, dryRun = false)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.SUCCESS)
        verify(exactly = 1) { aggregateBackfillRunner.backfill("004090") }
    }

    // ---------- 룰 #5 Source-of-truth ----------

    @Test
    fun `룰 #5 source-of-truth — source 필드 KIS-BACKFILL LOCK (raw 권위)`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        val gapTs = Instant.ofEpochSecond(1_700_000_000L)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns listOf(gapTs)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L)) // source="KIS"
        val saveSlot = slot<List<Candle>>()
        every { candleRepository.saveAll(capture(saveSlot)) } returns 1

        worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        // saveAll 호출된 candle의 source는 KIS-BACKFILL LOCK
        assertThat(saveSlot.captured[0].source).isEqualTo("KIS-BACKFILL")
        // 합성 데이터 적재 0건 — fetched candle만 saveAll
        assertThat(saveSlot.captured).hasSize(1)
    }

    // ---------- 룰 #6 False positive 가드 ----------

    @Test
    fun `룰 #6 false positive — 거래정지 종목 skip + audit CANDLE_BACKFILL_SKIPPED`() {
        val worker = newWorker()
        val haltInstrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns true
                every { it.liquidation } returns false
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(haltInstrument)
        setupAuditCapture()
        val captured = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { captured.captured }

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.SKIPPED)
        assertThat(captured.captured.action).isEqualTo("CANDLE_BACKFILL_SKIPPED")
        assertThat(captured.captured.detailJson).contains("TRADING_HALT")
        // KIS fetch 호출 0 (가드 통과 전)
        verify(exactly = 0) { kisRestPort.fetchCandles(any(), any(), any()) }
        // GapDetector 호출 0
        verify(exactly = 0) { candleGapDetector.detectGaps(any(), any(), any()) }
    }

    // ---------- 룰 #7 False negative 가드 ----------

    @Test
    fun `룰 #7 false negative — 갭 0건이면 SKIPPED + audit NO_GAPS`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns emptyList()
        val captured = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { firstArg<ConsumerAuditTrailJpaEntity>() }

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.SKIPPED)
        assertThat(result.gapCount).isEqualTo(0)
        // audit START + SKIPPED 2건
        assertThat(captured.map { it.action }).contains("CANDLE_BACKFILL_START", "CANDLE_BACKFILL_SKIPPED")
        // SKIPPED detail에 NO_GAPS 명시
        val skipped = captured.first { it.action == "CANDLE_BACKFILL_SKIPPED" }
        assertThat(skipped.detailJson).contains("NO_GAPS")
    }

    // ---------- 룰 #8 Retry + KillSwitch ----------

    @Test
    fun `룰 #8 retry — KIS 3회 모두 실패 시 KillSwitch ON + audit FAIL (user_account 존재 path)`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns
            listOf(Instant.ofEpochSecond(1_700_000_000L))
        // KIS 3회 모두 빈 응답 (또는 throw)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns emptyList()
        every { slackSender.sendDirect(any()) } returns true
        // 햇징 룰 §8 hotfix — user_account 존재 path: existsUserAccount true → enableKillSwitch 호출
        every { killSwitchRepo.existsUserAccount(CandleBackfillWorker.SYSTEM_USER_ID) } returns true
        every {
            killSwitchRepo.enableKillSwitch(
                userId = CandleBackfillWorker.SYSTEM_USER_ID,
                reason = any(),
                updatedBy = "chart-curator",
            )
        } just Runs

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.FAILED_KILLSWITCH)
        // 3회 retry 호출 (즉시·1s·5s)
        verify(exactly = 3) { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) }
        // existsUserAccount 사전 검증
        verify(exactly = 1) { killSwitchRepo.existsUserAccount(CandleBackfillWorker.SYSTEM_USER_ID) }
        // KillSwitch ON
        verify(exactly = 1) {
            killSwitchRepo.enableKillSwitch(
                userId = CandleBackfillWorker.SYSTEM_USER_ID,
                reason = match { it!!.startsWith("CANDLE_BACKFILL_FAIL") },
                updatedBy = "chart-curator",
            )
        }
        // Slack notice
        verify { slackSender.sendDirect(match { it.contains("CandleBackfill 실패") }) }
    }

    // ---------- 룰 #8 hotfix 회귀 가드 — user_account 부재 KillSwitch SKIP ----------

    @Test
    fun `(killswitch-fix) §8 hotfix — user_account 부재 시 KillSwitch INSERT 0 + Slack alert + KILLSWITCH_SKIPPED audit`() {
        val worker = newWorker()
        setupNoInstrument()
        val captured = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { firstArg<ConsumerAuditTrailJpaEntity>() }
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns
            listOf(Instant.ofEpochSecond(1_700_000_000L))
        // KIS 3회 모두 실패 → fatal path 진입
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns emptyList()
        every { slackSender.sendDirect(any()) } returns true
        // user_account 부재 — kill_switch_state FK violation 사전 차단
        every { killSwitchRepo.existsUserAccount(CandleBackfillWorker.SYSTEM_USER_ID) } returns false

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.FAILED_KILLSWITCH)
        // existsUserAccount 사전 검증 호출
        verify(exactly = 1) { killSwitchRepo.existsUserAccount(CandleBackfillWorker.SYSTEM_USER_ID) }
        // KillSwitch enable INSERT 0건 — FK violation 회피
        verify(exactly = 0) { killSwitchRepo.enableKillSwitch(any(), any(), any()) }
        // audit_trail에 CANDLE_BACKFILL_KILLSWITCH_SKIPPED 발행
        val skipAudit = captured.firstOrNull { it.action == "CANDLE_BACKFILL_KILLSWITCH_SKIPPED" }
        assertThat(skipAudit).isNotNull
        assertThat(skipAudit!!.detailJson).contains("USER_ACCOUNT_NOT_FOUND")
        // Slack notice 2건 — 실패 alert + KillSwitch SKIPPED alert
        verify(atLeast = 1) { slackSender.sendDirect(match { it.contains("KillSwitch SKIPPED") }) }
    }

    // ---------- audit JSON hotfix 회귀 가드 ----------

    @Test
    fun `(audit-json-fix) §9 hotfix — audit detail_json은 valid JSON으로 직렬화 (ObjectMapper)`() {
        val worker = newWorker()
        setupNoInstrument()
        val captured = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { firstArg<ConsumerAuditTrailJpaEntity>() }
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns
            listOf(Instant.ofEpochSecond(1_700_000_000L))
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L))
        every { candleRepository.saveAll(any()) } returns 1

        worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        // 모든 발행 audit detail_json이 valid JSON object여야 함
        assertThat(captured).isNotEmpty
        captured.forEach { row ->
            val parsed = objectMapper.readTree(row.detailJson)
            assertThat(parsed.isObject)
                .overridingErrorMessage(
                    "audit_trail.detail_json은 JSON object여야 함 (action=${row.action}, json=${row.detailJson})",
                ).isTrue
        }
        // START detail은 필수 key 보유
        val startAudit = captured.first { it.action == "CANDLE_BACKFILL_START" }
        val startNode = objectMapper.readTree(startAudit.detailJson)
        assertThat(startNode.get("backfillBatchId").asText())
            .matches("candle-backfill:004090:1d:\\d+-[0-9a-f-]+")
        assertThat(startNode.get("gapCount").asInt()).isEqualTo(1)
        assertThat(startNode.get("dryRun").asBoolean()).isFalse
    }

    @Test
    fun `(safety-1) KIS 응답 정상 + 일부 expected 미커버는 실패 아님 SUCCESS + KillSwitch 미발동`() {
        // rate-limit safety #1 핵심 — failed=5008/5008 무한 루프 root cause fix.
        // 거래 없는 분(illiquid)이나 KIS 미보유 expected ts는 영원히 unmatched이지만 실패가 아니다.
        // KIS가 캔들을 반환한 이상 upstream 실패가 아니므로 SUCCESS + KillSwitch 미발동.
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        val gap1 = Instant.ofEpochSecond(1_700_000_000L)
        val gap2 = Instant.ofEpochSecond(1_700_086_400L) // KIS가 반환하지 않는 expected (거래 없는 분 가정)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns listOf(gap1, gap2)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L)) // gap1만 매칭, gap2는 uncovered
        every { candleRepository.saveAll(any()) } returns 1
        every { slackSender.sendDirect(any()) } returns true

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        // uncovered 갭은 실패 아님 → SUCCESS
        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.SUCCESS)
        assertThat(result.insertedCount).isEqualTo(1)
        // 3분류 (readiness P0-2) — gap2는 uncovered, gap1은 inserted, failed 0
        assertThat(result.uncoveredCount).isEqualTo(1)
        assertThat(result.failedGapsCount).isEqualTo(0)
        // KillSwitch 미발동 (자본 영향 0)
        verify(exactly = 0) {
            killSwitchRepo.enableKillSwitch(any(), any(), any())
        }
        // 회복 신호 reset 호출 (KIS 응답 정상이므로 연속 miss 카운터 0으로)
        verify { backfillGapGuardStore.recordRecovery("004090", CandleInterval.ONE_DAY) }
    }

    @Test
    fun `(safety-1) saveAll 자체 실패는 PARTIAL_FAIL 또는 FAILED 집계`() {
        // KIS 응답은 정상이나 DB saveAll이 예외로 전건 실패 → 이때만 실패 집계 (DB 결함 path).
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        val gap1 = Instant.ofEpochSecond(1_700_000_000L)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns listOf(gap1)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L))
        every { candleRepository.saveAll(any()) } throws RuntimeException("DB down")
        every { slackSender.sendDirect(any()) } returns true
        every { killSwitchRepo.existsUserAccount(CandleBackfillWorker.SYSTEM_USER_ID) } returns true
        every { killSwitchRepo.enableKillSwitch(any(), any(), any()) } just Runs

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        // 단일 갭 전건 실패 → failureRatio 1.0 → FAILED_KILLSWITCH
        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.FAILED_KILLSWITCH)
        assertThat(result.failedGapsCount).isEqualTo(1)
    }

    // ---------- 룰 #9 Audit trail (5 path) ----------

    @Test
    fun `룰 #9 audit — START + SUCCESS 2 path 발행`() {
        val worker = newWorker()
        setupNoInstrument()
        val captured = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { firstArg<ConsumerAuditTrailJpaEntity>() }
        val gap = Instant.ofEpochSecond(1_700_000_000L)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns listOf(gap)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_700_000_000L))
        every { candleRepository.saveAll(any()) } returns 1

        worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        val actions = captured.map { it.action }
        assertThat(actions).contains("CANDLE_BACKFILL_START", "CANDLE_BACKFILL_SUCCESS")
        // START detail에 backfillBatchId·gapCount·dryRun 명시
        val startAudit = captured.first { it.action == "CANDLE_BACKFILL_START" }
        assertThat(startAudit.detailJson).contains("backfillBatchId", "gapCount\":1", "dryRun\":false")
        // SUCCESS detail에 insertedCount·durationMs 명시
        val successAudit = captured.first { it.action == "CANDLE_BACKFILL_SUCCESS" }
        assertThat(successAudit.detailJson).contains("insertedCount\":1", "durationMs")
    }

    @Test
    fun `룰 #9 audit — actor=chart-curator entityType=candle entityId=code LOCK`() {
        val worker = newWorker()
        setupNoInstrument()
        val captured = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { captured.captured }
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns emptyList()

        worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(captured.captured.actor).isEqualTo("chart-curator")
        assertThat(captured.captured.entityType).isEqualTo("candle")
        assertThat(captured.captured.entityId).isEqualTo("004090")
    }

    // ---------- rate-limit safety #1·#4 — 회복 불가 영구 마킹 + 재시도 중단 ----------

    @Test
    fun `(safety-1) KIS 전건 미반환 연속 임계 도달 시 UNRECOVERABLE 마킹 + terminal 알림 + KillSwitch 미발동`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        val captured = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { firstArg<ConsumerAuditTrailJpaEntity>() }
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_MIN, 30) } returns
            listOf(Instant.ofEpochSecond(1_700_000_000L))
        // KIS 전건 미반환 (3회 retry 모두 빈 응답)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_MIN, 1) } returns emptyList()
        every { slackSender.sendDirect(any()) } returns true
        // 연속 임계치 도달 → give-up
        every { backfillGapGuardStore.recordTotalMissAndShouldGiveUp("004090", CandleInterval.ONE_MIN) } returns true

        val result = worker.runBackfill("004090", CandleInterval.ONE_MIN, dryRun = false)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.UNRECOVERABLE)
        // 영구 마킹 호출
        verify(exactly = 1) {
            backfillGapGuardStore.markUnrecoverable("004090", CandleInterval.ONE_MIN, any())
        }
        // terminal audit 발행
        assertThat(captured.map { it.action }).contains("CANDLE_BACKFILL_UNRECOVERABLE")
        // terminal Slack 알림 (재시도 중단 명시)
        verify { slackSender.sendDirect(match { it.contains("회복 불가") && it.contains("재시도 중단") }) }
        // KillSwitch 미발동 (자본 결함 아님)
        verify(exactly = 0) { killSwitchRepo.enableKillSwitch(any(), any(), any()) }
    }

    @Test
    fun `(safety-1) 임계치 미만 전건 미반환은 기존 fatal 경로 보존 (재시도 허용)`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns
            listOf(Instant.ofEpochSecond(1_700_000_000L))
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns emptyList()
        every { slackSender.sendDirect(any()) } returns true
        every { killSwitchRepo.existsUserAccount(CandleBackfillWorker.SYSTEM_USER_ID) } returns true
        every { killSwitchRepo.enableKillSwitch(any(), any(), any()) } just Runs
        // 임계치 미만 → give-up 아님
        every { backfillGapGuardStore.recordTotalMissAndShouldGiveUp("004090", CandleInterval.ONE_DAY) } returns false

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.FAILED_KILLSWITCH)
        // 영구 마킹 미호출 (다음 사이클 재시도 허용)
        verify(exactly = 0) { backfillGapGuardStore.markUnrecoverable(any(), any(), any()) }
    }

    @Test
    fun `(safety-4) 회복 불가 마킹된 종목은 KIS fetch 0 + SKIPPED 반환`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        // 이미 회복 불가 마킹됨
        every { backfillGapGuardStore.isUnrecoverable("004090", CandleInterval.ONE_MIN) } returns true

        val result = worker.runBackfill("004090", CandleInterval.ONE_MIN, dryRun = false)

        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.UNRECOVERABLE)
        // detectGaps·KIS fetch 호출 0 (무한 재시도 차단)
        verify(exactly = 0) { candleGapDetector.detectGaps(any(), any(), any()) }
        verify(exactly = 0) { kisRestPort.fetchCandles(any(), any(), any()) }
    }

    // ---------- rate-limit safety #2 — 갭 상한 cap ----------

    @Test
    fun `(safety-2) detectGaps가 cap 초과 시 가장 최근 cap건만 처리`() {
        // chartCandleProperties.backfillGuard.maxGapsPerRun default=5000.
        // detected 6000건 → 가장 최근 5000건만 (ASC takeLast).
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        val captured = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { firstArg<ConsumerAuditTrailJpaEntity>() }
        // 6000개 갭 (ASC) — base + i*60s
        val base = 1_700_000_000L
        val detected = (0 until 6000).map { Instant.ofEpochSecond(base + it * 60L) }
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_MIN, 30) } returns detected
        // KIS 응답은 임의 1건 (cap 동작만 검증)
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_MIN, 1) } returns
            listOf(candle(tsEpochSec = base + 5999L * 60L, interval = CandleInterval.ONE_MIN))
        every { candleRepository.saveAll(any()) } returns 1
        every { aggregateBackfillRunner.backfill("004090") } returns emptyMap()

        val result = worker.runBackfill("004090", CandleInterval.ONE_MIN, dryRun = false)

        // gapCount는 cap된 5000 (detected 6000 아님)
        assertThat(result.gapCount).isEqualTo(5000)
        // START audit에 cap evidence
        val start = captured.first { it.action == "CANDLE_BACKFILL_START" }
        val node = objectMapper.readTree(start.detailJson)
        assertThat(node.get("detectedGapCount").asInt()).isEqualTo(6000)
        assertThat(node.get("gapCapApplied").asBoolean()).isTrue
        assertThat(node.get("gapCount").asInt()).isEqualTo(5000)
    }

    // ---------- 3분류 + sanity monotonic 정정 (2026-06-05 readiness P0-2) ----------

    @Test
    fun `(P0-2) SUCCESS인데 insertedCount 0 — 전부 uncovered이며 SUCCESS audit에 uncoveredCount 명시`() {
        val worker = newWorker()
        setupNoInstrument()
        setupAuditCapture()
        val captured = mutableListOf<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(captured)) } answers { firstArg<ConsumerAuditTrailJpaEntity>() }
        val gap1 = Instant.ofEpochSecond(1_700_000_000L)
        val gap2 = Instant.ofEpochSecond(1_700_086_400L)
        every { candleGapDetector.detectGaps("004090", CandleInterval.ONE_DAY, 30) } returns listOf(gap1, gap2)
        // KIS가 캔들을 반환하지만 어느 gap과도 매칭 안 됨 (전부 uncovered) — 다른 ts
        every { kisRestPort.fetchCandles("004090", CandleInterval.ONE_DAY, 200) } returns
            listOf(candle(tsEpochSec = 1_699_900_000L))
        every { candleRepository.saveAll(any()) } returns 0

        val result = worker.runBackfill("004090", CandleInterval.ONE_DAY, dryRun = false)

        // KIS 응답 정상 + 매칭 0 → inserted 0, uncovered 2, failed 0 → SUCCESS
        assertThat(result.status).isEqualTo(CandleBackfillWorker.BackfillStatus.SUCCESS)
        assertThat(result.insertedCount).isEqualTo(0)
        assertThat(result.uncoveredCount).isEqualTo(2)
        assertThat(result.failedGapsCount).isEqualTo(0)
        // SUCCESS audit에 3분류 명시 — "SUCCESS + insertedCount=0" 설명 가능
        val success = captured.first { it.action == "CANDLE_BACKFILL_SUCCESS" }
        val node = objectMapper.readTree(success.detailJson)
        assertThat(node.get("insertedCount").asInt()).isEqualTo(0)
        assertThat(node.get("uncoveredCount").asInt()).isEqualTo(2)
        assertThat(node.get("failedGapsCount").asInt()).isEqualTo(0)
    }

    @Test
    fun `(P0-2) sanity monotonic — 정상 ASC 응답은 monotonic 위반 0건 (어댑터 정규화 정합)`() {
        // 어댑터가 전역 ASC 정규화한 응답을 sanityCheck에 통과시키면 monotonic 위반 0.
        val worker = newWorker()
        val asc =
            (0 until 50).map { candle(tsEpochSec = 1_700_000_000L + it * 60L, interval = CandleInterval.ONE_MIN) }
        val result = worker.sanityCheck(asc, "test-batch")
        // ASC 정렬된 50건 전부 통과 (monotonic reject 0)
        assertThat(result).hasSize(50)
        assertThat(result.map { it.ts.epochSeconds }).isSorted
    }

    @Test
    fun `(P0-2) sanity monotonic — 비단조 응답은 위반 candle만 reject (로그 cap 무관 정확성)`() {
        // 진짜 비단조 응답(역순 1건)은 해당 candle만 reject, 나머지 유지.
        val worker = newWorker()
        val candles =
            listOf(
                candle(tsEpochSec = 1_700_000_060L),
                candle(tsEpochSec = 1_700_000_000L), // 역순 위반
                candle(tsEpochSec = 1_700_000_120L),
            )
        val result = worker.sanityCheck(candles, "test-batch")
        // 위반 1건 reject → 2건 유지 + ASC
        assertThat(result).hasSize(2)
        assertThat(result.map { it.ts.epochSeconds }).containsExactly(1_700_000_060L, 1_700_000_120L)
    }
}
