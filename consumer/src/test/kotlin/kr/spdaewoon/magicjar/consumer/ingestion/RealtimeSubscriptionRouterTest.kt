package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.port.SubscriptionPolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArraySet

class RealtimeSubscriptionRouterTest {
    private lateinit var kisWsPort: TestKisWsPort
    private val todayCandleSeedPort = mockk<kr.spdaewoon.magicjar.domain.market.port.TodayCandleSeedPort>(relaxed = true)
    private lateinit var router: RealtimeSubscriptionRouter

    @BeforeEach
    fun setUp() {
        kisWsPort = TestKisWsPort()
        router =
            RealtimeSubscriptionRouter(
                kisWsPort = kisWsPort,
                todayCandleSeedPort = todayCandleSeedPort,
            )
    }

    @Test
    fun `DETAIL 진입은 watchlist 없이도 가격 WS를 구독한다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        assertTrue(kisWsPort.connected)
        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(router.isExternallyDemanded("005930"))
    }

    @Test
    fun `DETAIL 이탈은 다른 owner가 없으면 가격 WS를 즉시 해제한다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        router.release(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        assertFalse(kisWsPort.priceSubscribed.contains("005930"))
        assertFalse(router.isExternallyDemanded("005930"))
        assertTrue(kisWsPort.priceUnsubscribed.contains("005930"))
    }

    @Test
    fun `DETAIL 이탈은 STRATEGY owner가 남으면 가격 WS를 유지한다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930", priority = 10)

        router.release(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(router.isExternallyDemanded("005930"))
        assertFalse(kisWsPort.priceUnsubscribed.contains("005930"))
        val owners = router.snapshotDemands().first { it.code == "005930" }.owners
        assertTrue(owners.contains("strategy:42"))
        assertFalse(owners.any { it.startsWith("detail:") })
    }

    @Test
    fun `STRATEGY 이탈은 demand가 모두 사라지면 가격 WS를 즉시 해제한다`() {
        // ADR 0124 — watchlist가 WS를 점유하지 않으므로 owner demand 0이면 즉시 unsubscribe.
        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930", priority = 10)
        router.release(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930")

        assertFalse(router.isExternallyDemanded("005930"))
        assertTrue(kisWsPort.priceUnsubscribed.contains("005930"))
    }

    @Test
    fun `slot 포화 시 신규 진입은 demand 없는 잔여 구독을 evict한다 (ADR 0124)`() {
        // ADR 0124 — eviction victim 후보는 어느 owner demand에도 없는 잔여 구독만.
        // slot 을 demand 없는 종목으로 가득 채운다.
        val orphanCodes = (1..SubscriptionPolicy.PRICE_TICK_MAX).map { "1%05d".format(it) }
        kisWsPort.priceSubscribed.addAll(orphanCodes)

        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930", priority = 10)

        // 신규 STRATEGY 구독 발사 + orphan 1건 evict.
        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(kisWsPort.priceUnsubscribed.any { it in orphanCodes })
    }

    @Test
    fun `HOLDING 등록은 가격 WS를 구독하고 최우선 demand로 추적한다 (ADR 0114 P3)`() {
        router.request(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")

        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(router.isExternallyDemanded("005930"))
        // priority 0 (최우선).
        org.junit.jupiter.api.Assertions
            .assertEquals(0, router.priorityOf("005930"))
    }

    @Test
    fun `slot 포화 + 신규 진입 시 HOLDING 종목은 evict 후보에서 제외된다 (ADR 0114 P3 LOCK)`() {
        // 30 slot 중 1개를 HOLDING(보유 종목), 나머지는 demand 없는 잔여 구독으로 채운다.
        // ADR 0124 — evict 후보는 demand 없는 잔여 구독만이지만 HOLDING demand 종목은 절대 victim 아님.
        val holdingCode = "0HOLD0"
        val orphanCodes = (1 until SubscriptionPolicy.PRICE_TICK_MAX).map { "1%05d".format(it) }
        kisWsPort.priceSubscribed.add(holdingCode)
        kisWsPort.priceSubscribed.addAll(orphanCodes)

        // HOLDING demand 등록(이미 구독 중이라 재구독 X, demand만 추적).
        router.request(RealtimeDemandOwner.HOLDING, "holding:$holdingCode", holdingCode)
        // slot 포화 상태에서 신규 STRATEGY 진입 → evict가 일어나도 HOLDING은 절대 victim 아님.
        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930", priority = 10)

        assertTrue(kisWsPort.priceSubscribed.contains(holdingCode))
        assertFalse(kisWsPort.priceUnsubscribed.contains(holdingCode))
    }

    @Test
    fun `HOLDING release는 즉시 demand를 제거한다 (전량 청산)`() {
        router.request(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")
        assertTrue(router.isExternallyDemanded("005930"))

        router.release(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")

        assertFalse(router.isExternallyDemanded("005930"))
        assertTrue(kisWsPort.priceUnsubscribed.contains("005930"))
    }

    // ===== today-Hash seed 보강 (이미 WS 구독 중인 종목의 DETAIL 재진입 복구) =====

    @Test
    fun `신규 DETAIL 구독은 KisWsPort subscribe에 seed 책임을 위임하고 라우터 중복 시드는 하지 않는다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        Thread.sleep(300)
        verify(exactly = 0) { todayCandleSeedPort.seedTodayCandle("005930") }
    }

    @Test
    fun `이미 구독 중인 종목 재요청은 today-Hash seed를 보강한다`() {
        kisWsPort.priceSubscribed.add("005930")
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        verify(timeout = 2000) { todayCandleSeedPort.seedTodayCandle("005930") }
    }

    @Test
    fun `이미 구독 중인 종목 재요청 seed는 cooldown 내 중복 호출하지 않는다`() {
        kisWsPort.priceSubscribed.add("005930")
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        verify(timeout = 2000) { todayCandleSeedPort.seedTodayCandle("005930") }

        router.request(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")
        Thread.sleep(300)
        verify(exactly = 1) { todayCandleSeedPort.seedTodayCandle("005930") }
    }

    @Test
    fun `snapshotDemands — code별 owner 합집합 + since (관리자 실시간 상태)`() {
        router.request(RealtimeDemandOwner.DETAIL, "user001", "005930")
        router.request(RealtimeDemandOwner.STRATEGY, "86", "005930", priority = 10)
        router.request(RealtimeDemandOwner.HOLDING, "holding", "000660")

        val snap = router.snapshotDemands().associateBy { it.code }
        assertTrue(snap.containsKey("005930"))
        assertTrue(snap.containsKey("000660"))
        // 005930은 DETAIL + STRATEGY owner 합집합 (정렬). bare ownerId → 단일 prefix 1회.
        assertTrue(snap["005930"]!!.owners.contains("detail:user001"))
        assertTrue(snap["005930"]!!.owners.contains("strategy:86"))
        assertTrue(snap["005930"]!!.sinceMs != null)
    }

    @Test
    fun `snapshotDemands — legacy prefix 포함 ownerId여도 이중 prefix 방어`() {
        // 롤링 배포·미발견 producer가 prefix 포함 ownerId를 넣어도 스냅샷은 단일 prefix(이중 prefix 회피).
        router.request(RealtimeDemandOwner.STRATEGY, "strategy:86", "005930", priority = 10)
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        val owners = router.snapshotDemands().first { it.code == "005930" }.owners
        assertTrue(owners.contains("strategy:86")) // strategy:strategy:86 아님
        assertTrue(owners.contains("detail:user001")) // detail:detail:user001 아님
        assertTrue(owners.none { it.startsWith("strategy:strategy:") || it.startsWith("detail:detail:") })
    }

    @Test
    fun `snapshotDemands — since는 재요청에도 최초 구독 시각 보존`() {
        router.request(RealtimeDemandOwner.STRATEGY, "86", "005930", priority = 10)
        val firstSince = router.snapshotDemands().first { it.code == "005930" }.sinceMs
        Thread.sleep(10)
        router.request(RealtimeDemandOwner.STRATEGY, "86", "005930", priority = 10)
        val secondSince = router.snapshotDemands().first { it.code == "005930" }.sinceMs
        assertTrue(firstSince == secondSince)
    }

    class TestKisWsPort : KisWsPort {
        var connected = false
        val priceSubscribed: MutableSet<String> = CopyOnWriteArraySet()
        val priceUnsubscribed: MutableSet<String> = CopyOnWriteArraySet()

        override fun connect() {
            connected = true
        }

        override fun subscribe(codes: List<String>) {
            priceSubscribed.addAll(codes)
        }

        override fun unsubscribe(codes: List<String>) {
            priceSubscribed.removeAll(codes.toSet())
            priceUnsubscribed.addAll(codes)
        }

        override fun subscribedCodes(): Set<String> = priceSubscribed.toSet()

        override fun subscribeOrderbook(codes: List<String>) {}

        override fun unsubscribeOrderbook(codes: List<String>) {}

        override fun subscribedOrderbookCodes(): Set<String> = emptySet()

        override fun subscribeMarketStatus(codes: List<String>) {}

        override fun unsubscribeMarketStatus(codes: List<String>) {}

        override fun subscribedMarketStatusCodes(): Set<String> = emptySet()
    }
}
