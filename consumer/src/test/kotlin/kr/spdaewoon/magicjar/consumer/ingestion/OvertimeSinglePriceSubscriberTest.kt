package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.watchlist.WatchlistEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * OvertimeSinglePriceSubscriber 단위 테스트 (ADR 0076 Phase 3 B-2).
 *
 * 검증 항목:
 * 1. 16:00 KST cron(onOvertimeStart) → watchlist 종목 H0STOUP0 subscribe 호출
 * 2. 18:00 KST cron(onOvertimeEnd) → 구독 종목 전체 H0STOUP0 unsubscribe 호출
 * 3. 부팅 시 16:30 KST이면 catch-up subscribe 호출 (isOvertimeWindow=true)
 * 4. isOvertimeWindow 경계 조건 — 16:00 이상 18:00 미만만 true
 *
 * KIS-first 출처:
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
 *   tr_id="H0STOUP0" — 국내주식 시간외 단일가(16:00~18:00) 시간대 활성
 *
 * AI 호출 0 — 룰 5 정합.
 */
class OvertimeSinglePriceSubscriberTest {
    private val kisWsBridge = mockk<KisWebSocketBridge>(relaxed = true)
    private val watchlistRepository = mockk<ConsumerWatchlistRepositoryAdapter>(relaxed = true)

    private lateinit var subscriber: OvertimeSinglePriceSubscriber

    private val sampleWatchlist =
        listOf(
            WatchlistEntry(
                id = 1L,
                userId = "user001",
                code = "005930",
                priority = 1,
                createdAt = Clock.System.now(),
            ),
            WatchlistEntry(
                id = 2L,
                userId = "user001",
                code = "000660",
                priority = 2,
                createdAt = Clock.System.now(),
            ),
        )

    @BeforeEach
    fun setUp() {
        subscriber =
            OvertimeSinglePriceSubscriber(
                kisWsBridge = kisWsBridge,
                watchlistRepository = watchlistRepository,
            )
    }

    /**
     * 16:00 cron(onOvertimeStart) → watchlist 종목 H0STOUP0 subscribe 호출 검증.
     *
     * KIS-first: H0STOUP0 활성 시작 16:00 KST.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     */
    @Test
    fun `16시 cron onOvertimeStart — watchlist 종목 H0STOUP0 subscribe 호출`() {
        // given
        every { watchlistRepository.findAll() } returns sampleWatchlist
        every { kisWsBridge.subscribeOvertimeSinglePrice(any()) } returns Unit

        // when
        subscriber.onOvertimeStart()

        // then: subscribeOvertimeSinglePrice가 watchlist 종목으로 호출됨
        val codesSlot = slot<List<String>>()
        verify(exactly = 1) { kisWsBridge.subscribeOvertimeSinglePrice(capture(codesSlot)) }
        assertThat(codesSlot.captured).containsExactlyInAnyOrder("005930", "000660")
    }

    /**
     * 18:00 cron(onOvertimeEnd) → 구독 종목 전체 H0STOUP0 unsubscribe 호출 검증.
     *
     * KIS-first: H0STOUP0 활성 종료 18:00 KST.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     */
    @Test
    fun `18시 cron onOvertimeEnd — 구독 종목 전체 H0STOUP0 unsubscribe 호출`() {
        // given: 구독 중인 종목 있음
        val subscribedCodes = setOf("005930", "000660")
        every { kisWsBridge.subscribedOvertimeSinglePriceCodes() } returns subscribedCodes
        every { kisWsBridge.unsubscribeOvertimeSinglePrice(any()) } returns Unit

        // when
        subscriber.onOvertimeEnd()

        // then: unsubscribeOvertimeSinglePrice가 구독 종목으로 호출됨
        val codesSlot = slot<List<String>>()
        verify(exactly = 1) { kisWsBridge.unsubscribeOvertimeSinglePrice(capture(codesSlot)) }
        assertThat(codesSlot.captured).containsExactlyInAnyOrder("005930", "000660")
    }

    /**
     * 18:00 cron — 구독 종목 없으면 unsubscribe 미호출 검증.
     */
    @Test
    fun `18시 cron 구독 종목 없으면 unsubscribeOvertimeSinglePrice 미호출`() {
        // given: 구독 종목 없음
        every { kisWsBridge.subscribedOvertimeSinglePriceCodes() } returns emptySet()

        // when
        subscriber.onOvertimeEnd()

        // then: unsubscribeOvertimeSinglePrice 호출 없음
        verify(exactly = 0) { kisWsBridge.unsubscribeOvertimeSinglePrice(any()) }
    }

    /**
     * 부팅 시 16:30 KST이면 catch-up subscribe 호출 검증.
     *
     * ADR 0076 Phase 3 B-2: 부팅 catch-up — 16:00~18:00 사이 부팅 시 즉시 subscribe.
     */
    @Test
    fun `isOvertimeWindow 16시 30분 → true (catch-up subscribe 진입 조건)`() {
        // given: 16:30 KST
        val t1630 = LocalTime.of(16, 30)

        // when/then
        assertThat(subscriber.isOvertimeWindow(t1630))
            .describedAs("16:30 KST는 시간외 단일가 활성 시간 — isOvertimeWindow=true")
            .isTrue()
    }

    /**
     * isOvertimeWindow 경계 조건 — 16:00 이상 18:00 미만만 true.
     *
     * KIS-first: H0STOUP0 활성 시간 16:00~18:00 KST.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     */
    @Test
    fun `isOvertimeWindow 경계 조건 — 16시 이상 18시 미만만 true`() {
        // 16:00 이상 18:00 미만 → true
        assertThat(subscriber.isOvertimeWindow(LocalTime.of(16, 0)))
            .describedAs("16:00 정각 → 활성 시간 시작 (포함)")
            .isTrue()
        assertThat(subscriber.isOvertimeWindow(LocalTime.of(17, 59)))
            .describedAs("17:59 → 활성 시간 내")
            .isTrue()

        // 18:00 이상 → false
        assertThat(subscriber.isOvertimeWindow(LocalTime.of(18, 0)))
            .describedAs("18:00 정각 → 활성 시간 종료 (미포함)")
            .isFalse()

        // 16:00 미만 → false
        assertThat(subscriber.isOvertimeWindow(LocalTime.of(15, 59)))
            .describedAs("15:59 → 활성 시간 전")
            .isFalse()
        assertThat(subscriber.isOvertimeWindow(LocalTime.of(9, 0)))
            .describedAs("09:00 정규장 → 비활성")
            .isFalse()
    }
}
