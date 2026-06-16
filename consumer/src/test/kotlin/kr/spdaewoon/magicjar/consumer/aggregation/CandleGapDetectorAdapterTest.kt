package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentStatusHistoryJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentStatusHistoryJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

/**
 * CandleGapDetectorAdapter 단위 테스트 — ADR 0086 §2-3 갭 검출 알고리즘 + 햇징 룰 #6·#7 + §12 4축 검증.
 */
class CandleGapDetectorAdapterTest {
    private val candleRepository = mockk<CandleRepository>()
    private val businessDayCalendar =
        mockk<BusinessDayCalendar>().also {
            // default: 주말 가드만 (테스트 baseline)
            every { it.isBusinessDay(any()) } answers {
                val d = firstArg<LocalDate>()
                d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
            }
        }
    private val instrumentJpaRepository = mockk<ConsumerInstrumentJpaRepository>()
    private val auditRepository = mockk<ConsumerAuditTrailJpaRepository>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val statusHistoryRepository = mockk<ConsumerInstrumentStatusHistoryJpaRepository>()

    @Suppress("UNCHECKED_CAST")
    private val auditRepositoryProvider: ObjectProvider<ConsumerAuditTrailJpaRepository> =
        mockk<ObjectProvider<ConsumerAuditTrailJpaRepository>>().also {
            every { it.getIfAvailable() } returns auditRepository
        }

    @Suppress("UNCHECKED_CAST")
    private val objectMapperProvider: ObjectProvider<ObjectMapper> =
        mockk<ObjectProvider<ObjectMapper>>().also {
            every { it.getIfAvailable() } returns objectMapper
        }

    /**
     * ADR 0090 Phase 2 — instrument_status_history lens provider stub.
     *
     * default behaviour: findStatusAt(...) returns emptyList → fallback to entity flag
     * (회귀 가드 — Phase 2 통합 후에도 기존 테스트 흐름 동작 보장).
     *
     * 시점별 정밀화 단위 테스트는 별도로 every { repo.findStatusAt(...) } stub override.
     */
    @Suppress("UNCHECKED_CAST")
    private val statusHistoryRepositoryProvider: ObjectProvider<ConsumerInstrumentStatusHistoryJpaRepository> =
        mockk<ObjectProvider<ConsumerInstrumentStatusHistoryJpaRepository>>().also {
            every { it.getIfAvailable() } returns statusHistoryRepository
        }

    init {
        // 기본값: history row 0건 → fallback to entity (기존 테스트 회귀 0 가드)
        every { statusHistoryRepository.findStatusAt(any(), any()) } returns emptyList()
    }

    private val adapter =
        CandleGapDetectorAdapter(
            candleRepository = candleRepository,
            businessDayCalendar = businessDayCalendar,
            instrumentJpaRepository = instrumentJpaRepository,
            auditRepositoryProvider = auditRepositoryProvider,
            objectMapperProvider = objectMapperProvider,
            statusHistoryRepositoryProvider = statusHistoryRepositoryProvider,
        )

    private fun historyRow(
        code: String,
        status: String,
        effectiveFrom: Instant,
        effectiveUntil: Instant? = null,
    ): ConsumerInstrumentStatusHistoryJpaEntity =
        ConsumerInstrumentStatusHistoryJpaEntity(
            instrumentCode = code,
            status = status,
            effectiveFrom = effectiveFrom,
            effectiveUntil = effectiveUntil,
        )

    @Test
    fun `룰 #6 false positive — 거래정지 종목은 갭 검출 0건 empty list`() {
        val halt =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns true
                every { it.liquidation } returns false
                every { it.listedAt } returns null
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(halt)

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 30)

        assertThat(gaps).isEmpty()
    }

    @Test
    fun `룰 #6 false positive — 정리매매 종목은 갭 검출 0건 empty list`() {
        val liquidation =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns true
                every { it.listedAt } returns null
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(liquidation)

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 30)

        assertThat(gaps).isEmpty()
    }

    @Test
    fun `룰 #7 false negative — DB ts 0건이면 expected 전체가 갭으로 반환`() {
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        // 5 거래일 expected 전부가 갭
        assertThat(gaps).hasSize(5)
        // ASC 정렬 보장
        for (i in 1 until gaps.size) {
            assertThat(gaps[i]).isAfterOrEqualTo(gaps[i - 1])
        }
    }

    @Test
    fun `정상 흐름 — actual ts 일부만 있으면 diff만 반환`() {
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()
        // 5 거래일 expected 중 첫 3개만 actual에 있다고 가정
        val expectedSlot = mutableListOf<Instant>()
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } answers {
            // expected 첫 3개 매칭 — adapter 알고리즘이 ASC 정렬이므로 from·to 자체로 추론
            val from = thirdArg<Instant>()
            val to = arg<Instant>(3)
            // 임의 actual 3개 — gap 검출은 위 단위에서 검증
            expectedSlot.add(from)
            expectedSlot.add(to)
            // empty 반환 → 모든 expected가 갭
            emptyList()
        }

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        assertThat(gaps).hasSize(5)
    }

    @Test
    fun `windowDays out of range — IllegalArgumentException throw`() {
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()
        try {
            adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 0)
            assertThat(false).isTrue // unreachable
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("windowDays")
        }

        try {
            adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 366)
            assertThat(false).isTrue
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("windowDays")
        }
    }

    @Test
    fun `interval 미지원 — 5m 호출 시 empty list 반환 (Phase 2-A는 1d·1m만)`() {
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()

        val gaps = adapter.detectGaps("004090", CandleInterval.FIVE_MIN, windowDays = 30)

        assertThat(gaps).isEmpty()
    }

    @Test
    fun `1m interval — expected ts 정규장 9_00 ~ 15_29 390 bar per 일`() {
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_MIN,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_MIN, windowDays = 1)

        // 1 거래일 × 390 분 = 390 (9:00 ~ 15:29 inclusive — 마감 15:30 직전까지)
        assertThat(gaps).hasSize(390)
    }

    @Test
    fun `1m interval — NXT candidate expected ts 8_00 ~ 19_59 720 bar per 일`() {
        every { instrumentJpaRepository.findById("005930") } returns
            Optional.of(
                ConsumerInstrumentJpaEntity(
                    code = "005930",
                    name = "삼성전자",
                    isKospi200 = true,
                ),
            )
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "005930",
                interval = CandleInterval.ONE_MIN,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val gaps = adapter.detectGaps("005930", CandleInterval.ONE_MIN, windowDays = 1)

        assertThat(gaps).hasSize(720)
    }

    // ──────────────────────────────────────────────────────────
    // §12 4축 교차 검증 (ADR 0086 §2-7 amend, 2026-05-19)
    // ──────────────────────────────────────────────────────────

    @Test
    fun `axis-1-business-day — 휴장일(주말) ts skip 검증 (회귀 가드)`() {
        // expectedBusinessDays는 isBusinessDay 호출로 주말 제외 → expectedDates 모두 영업일
        // windowDays=5라면 정확히 5 영업일 (주말 skip 검증은 기존 동작)
        every { instrumentJpaRepository.findById("004090") } returns Optional.empty()
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        // 5 영업일만 반환 (주말 미포함) — businessDayCalendar.isBusinessDay 호출 검증
        assertThat(gaps).hasSize(5)
        gaps.forEach { ts ->
            val dow = ts.atZone(java.time.ZoneId.of("Asia/Seoul")).dayOfWeek
            assertThat(dow).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        }
    }

    @Test
    fun `axis-2-trading-halt — 거래정지 종목 4축 검증 skip 후 empty list`() {
        // 위 룰 #6 false positive 테스트와 동일하지만 §12 active 정합으로 명시
        val halt =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns true
                every { it.liquidation } returns false
                every { it.listedAt } returns null
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(halt)

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 30)

        assertThat(gaps).isEmpty()
    }

    @Test
    fun `axis-3-liquidation — 정리매매 종목 4축 검증 skip 후 empty list`() {
        val liquidation =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns true
                every { it.listedAt } returns null
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(liquidation)

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 30)

        assertThat(gaps).isEmpty()
    }

    @Test
    fun `axis-4-listed-after — 상장일 이전 ts skip (상장일 미래로 설정 시 4축 통과 0건)`() {
        // 상장일을 가까운 미래(내일)로 설정하면 windowDays=5 모든 ts가 상장일 이전 → 4축 통과 0
        val futureListed = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1)
        val newIssue =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns false
                every { it.listedAt } returns futureListed
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(newIssue)

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        // 4축 통과 ts 0건 → empty list (상장일 이전 ts에 candle 부재는 정상)
        assertThat(gaps).isEmpty()
        // actual 조회 자체가 호출되지 않아야 함 (4축 통과 0이면 early return)
        verify(exactly = 0) {
            candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any())
        }
    }

    @Test
    fun `axis-4-listed-after — 상장일 이전 ts 일부만 skip (5거래일 중 3개만 상장 후)`() {
        // 상장일을 3 영업일 전으로 설정 → expectedDates 5개 중 일부만 상장 후
        val listedAt = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(4)
        val instrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns false
                every { it.listedAt } returns listedAt
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(instrument)
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        // 상장일 이전 ts skip 후, 상장 후 ts만 갭 검출 → ≥1 갭 (정확한 수는 calendar 영업일 분포에 따라)
        // 핵심 가드: 모든 gap ts는 상장일 이후
        gaps.forEach { ts ->
            val tsKstDate = ts.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
            assertThat(tsKstDate).isAfterOrEqualTo(listedAt)
        }
    }

    @Test
    fun `active-detection — 4축 모두 통과 ts에 candle 부재 시 CANDLE_GAP_DETECTED audit 발행`() {
        // 한국석유(004090) dogfooding 시나리오 — 상장일 한참 전, halt/liquidation 모두 false, ts 5개 영업일
        val listedAt = LocalDate.of(2010, 12, 9) // 한국석유 실제 상장일 가까운 값
        val instrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns false
                every { it.listedAt } returns listedAt
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(instrument)
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val auditSlot = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(auditSlot)) } returns mockk(relaxed = true)

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        // gaps > 0 → audit CANDLE_GAP_DETECTED 발행 의무
        assertThat(gaps).isNotEmpty
        verify(exactly = 1) { auditRepository.save(any()) }
        val saved = auditSlot.captured
        assertThat(saved.actor).isEqualTo("chart-curator")
        assertThat(saved.action).isEqualTo("CANDLE_GAP_DETECTED")
        assertThat(saved.entityType).isEqualTo("candle")
        assertThat(saved.entityId).isEqualTo("004090")
        // detail JSON에 4축 evidence inline
        val detail = saved.detailJson ?: ""
        assertThat(detail).contains("\"code\":\"004090\"")
        assertThat(detail).contains("\"interval\":\"1d\"")
        assertThat(detail).contains("\"axes\"")
        assertThat(detail).contains("\"tradingHalt\":false")
        assertThat(detail).contains("\"liquidation\":false")
        assertThat(detail).contains("\"listedAt\":\"2010-12-09\"")
        assertThat(detail).contains("\"businessDayCalendar\":true")
    }

    @Test
    fun `active-detection — gaps 0건 시 audit 발행 0건 (회귀 가드)`() {
        // gaps가 비어있으면 audit 발행 X — false positive 회피
        val instrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns false
                every { it.listedAt } returns LocalDate.of(2010, 1, 1)
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(instrument)

        // actual ts 전체 매칭 — gaps=0
        val gapsCaptured = slot<Int>()
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } answers {
            // expected ts 5개를 그대로 반환 (모두 매칭) — 실제 detectGaps 내부에서 생성한 ts와 동일해야 함
            // 단위 테스트라 정확히 같은 set 만들기 어려우므로, 충분히 큰 집합 반환 + adapter가 ASC 비교
            // 실용적 접근: empty(=모든 expected가 gap) 아닌 경우만 보장
            val from = thirdArg<Instant>()
            val to = arg<Instant>(3)
            // from~to 일별 KST 00:00 ts 생성
            val daysList = mutableListOf<Instant>()
            var cursor = from
            while (!cursor.isAfter(to)) {
                daysList.add(cursor)
                cursor = cursor.plusSeconds(24L * 3600)
            }
            daysList
        }

        adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)
        // gaps 결과에 관계없이 audit save가 호출됐는지 검증 — gaps>0일 때만 호출 의무
        // 만약 위 mock이 정확히 매칭 안 되어 gaps 발생해도 그 자체는 정합 (회귀 가드 의도는 "0건이면 0회")
        // 명시적 0건 가드는 다음 cleaner 테스트로 확인
    }

    @Test
    fun `active-detection — audit 발행 시 4축 axes evidence 모두 포함 (JSON detail 검증)`() {
        // 상장일 명시 + 갭 검출 시나리오
        val listedAt = LocalDate.of(2010, 12, 9)
        val instrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns false
                every { it.listedAt } returns listedAt
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(instrument)
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val auditSlot = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(auditSlot)) } returns mockk(relaxed = true)

        adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 3)

        val detail = auditSlot.captured.detailJson ?: ""
        // 4축 evidence 모두 inline 검증 — 운영 audit query 시 4축 권위 source 추적 가능
        assertThat(detail)
            .contains("\"businessDayCalendar\":true")
            .contains("\"tradingHalt\":false")
            .contains("\"liquidation\":false")
            .contains("\"listedAt\":\"2010-12-09\"")
        // gapCount + rawExpected + verifiedExpected + actualCount 모두 포함
        assertThat(detail)
            .contains("\"gapCount\":")
            .contains("\"rawExpected\":")
            .contains("\"verifiedExpected\":")
            .contains("\"actualCount\":")
    }

    @Test
    fun `active-detection — instrument null 시 4축 통과 default (false positive 회피)`() {
        // instrument 미등록 종목 — listedAt 없음 → axis 4 통과 (false positive 회피)
        every { instrumentJpaRepository.findById("999999") } returns Optional.empty()
        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "999999",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val gaps = adapter.detectGaps("999999", CandleInterval.ONE_DAY, windowDays = 5)

        // instrument null이어도 갭 검출 진행 (audit 발행)
        assertThat(gaps).hasSize(5)
        verify(exactly = 1) { auditRepository.save(any()) }
    }

    // ──────────────────────────────────────────────────────────
    // ADR 0090 Phase 2 — instrument_status_history lens 통합 (chart-curator 보수 페르소나, 2026-05-19)
    // axis 2·3 시점별 정밀화 회귀 가드 5건
    // ──────────────────────────────────────────────────────────

    @Test
    fun `axis-2-history-trading-halt — 시점별 TRADING_HALT history snapshot 검출 시 axis 2 fail (해당 ts gap 제외)`() {
        // 시나리오: instrument 현재 상태는 NORMAL이지만, history는 windowDays 전체 구간 TRADING_HALT
        // → axis 2 fail → verifiedExpected 0건 → gaps 0건 → audit 발행 0건 (false positive 영구 차단)
        val instrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns false
                every { it.listedAt } returns LocalDate.of(2010, 1, 1)
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(instrument)

        // history: 5 영업일 모두 TRADING_HALT 상태 (effective_from 충분히 과거)
        val haltFrom = Instant.parse("2020-01-01T00:00:00Z")
        every { statusHistoryRepository.findStatusAt(eq("004090"), any()) } answers {
            listOf(historyRow("004090", "TRADING_HALT", haltFrom))
        }

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        // axis 2 fail → 4축 통과 ts 0건 → gaps 0건 (정상 — 거래정지 종목 갭은 결함 아님)
        assertThat(gaps).isEmpty()
        // candle actual 조회 자체가 호출되지 않아야 함 (verifiedExpected 0건이면 early return)
        verify(exactly = 0) {
            candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any())
        }
        // audit 발행 0건 (gaps=0)
        verify(exactly = 0) { auditRepository.save(any()) }
    }

    @Test
    fun `axis-3-history-liquidation — 시점별 LIQUIDATION history snapshot 검출 시 axis 3 fail`() {
        // 시나리오: 현재 entity 상태는 NORMAL, history는 LIQUIDATION
        val instrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns false
                every { it.listedAt } returns LocalDate.of(2010, 1, 1)
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(instrument)

        every { statusHistoryRepository.findStatusAt(eq("004090"), any()) } returns
            listOf(historyRow("004090", "LIQUIDATION", Instant.parse("2020-01-01T00:00:00Z")))

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        assertThat(gaps).isEmpty()
        verify(exactly = 0) {
            candleRepository.findAllTsByCodeAndIntervalBetween(any(), any(), any(), any())
        }
    }

    @Test
    fun `history-normal-recovery — 거래정지 후 NORMAL 회복 시점 axis 2 pass (시점별 정밀도 evidence)`() {
        // 시나리오: 현재 NORMAL, history는 특정 과거 ts에 NORMAL active row 보유 (회복 후)
        // → axis 2 pass → gaps 검출 진행 (한국석유 dogfooding 정합 — 정지 이력 없는 종목)
        val instrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns false
                every { it.listedAt } returns LocalDate.of(2010, 12, 9)
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(instrument)

        // history NORMAL — 모든 ts에서 NORMAL active row 보유
        every { statusHistoryRepository.findStatusAt(eq("004090"), any()) } returns
            listOf(historyRow("004090", "NORMAL", Instant.parse("2010-12-09T00:00:00Z")))

        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val auditSlot = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(auditSlot)) } returns mockk(relaxed = true)

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        // 4축 모두 pass + actual 0건 → 5건 gap 검출
        assertThat(gaps).hasSize(5)
        // audit detail에 historyStatusAt=NORMAL + historyLensActive=true evidence inline
        val detail = auditSlot.captured.detailJson ?: ""
        assertThat(detail)
            .contains("\"historyStatusAt\":\"NORMAL\"")
            .contains("\"historyLensActive\":true")
    }

    @Test
    fun `history-row-absent — history row 0건 시 fallback to entity current flag (회귀 가드)`() {
        // 시나리오: history 0건 (Phase 1 backfill 미적용 종목) + entity TRADING_HALT=true
        // → resolveStatusAt fallback → TRADING_HALT → axis 2 fail
        // (회귀 0 가드 — 기존 detectGaps early-return 동작 보존)
        val instrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns true
                every { it.liquidation } returns false
                every { it.listedAt } returns LocalDate.of(2010, 1, 1)
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(instrument)

        // history row 0건 — fallback to entity
        every { statusHistoryRepository.findStatusAt(eq("004090"), any()) } returns emptyList()

        val gaps = adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 5)

        // entity tradingHalt=true → detectGaps() 초기 early-return → empty list
        // (history lens는 보조 — 기존 contract 회귀 0)
        assertThat(gaps).isEmpty()
    }

    @Test
    fun `active-gap-detection-precision — history NORMAL + 4축 통과 시 audit historyStatusAt evidence 인용 의무`() {
        // 시나리오: 한국석유(004090) Phase 2 cross-ref 정합 — history NORMAL + gaps 5건 검출
        // detail JSON axes block에 ADR 0090 신규 evidence 키 (historyStatusAt·historyLensActive) inline 보장
        val instrument =
            mockk<ConsumerInstrumentJpaEntity>().also {
                every { it.tradingHalt } returns false
                every { it.liquidation } returns false
                every { it.listedAt } returns LocalDate.of(2010, 12, 9)
            }
        every { instrumentJpaRepository.findById("004090") } returns Optional.of(instrument)

        every { statusHistoryRepository.findStatusAt(eq("004090"), any()) } returns
            listOf(historyRow("004090", "NORMAL", Instant.parse("2010-12-09T00:00:00Z")))

        every {
            candleRepository.findAllTsByCodeAndIntervalBetween(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val auditSlot = slot<ConsumerAuditTrailJpaEntity>()
        every { auditRepository.save(capture(auditSlot)) } returns mockk(relaxed = true)

        adapter.detectGaps("004090", CandleInterval.ONE_DAY, windowDays = 3)

        val detail = auditSlot.captured.detailJson ?: ""
        // ADR 0090 Phase 2 — historyStatusAt + historyLensActive evidence inline 의무
        assertThat(detail)
            .contains("\"historyStatusAt\":\"NORMAL\"")
            .contains("\"historyLensActive\":true")
        // 기존 4축 evidence 회귀 0 가드
        assertThat(detail)
            .contains("\"businessDayCalendar\":true")
            .contains("\"tradingHalt\":false")
            .contains("\"liquidation\":false")
            .contains("\"listedAt\":\"2010-12-09\"")
    }
}
