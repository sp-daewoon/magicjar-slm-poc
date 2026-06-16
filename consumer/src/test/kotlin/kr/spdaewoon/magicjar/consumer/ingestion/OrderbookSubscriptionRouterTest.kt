package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.mockk
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArraySet

/**
 * OrderbookSubscriptionRouter 단위 테스트 — STRATEGY + DETAIL 두 owner (2026-06-12 DETAIL 표시-중 복원 후).
 *
 * ## DETAIL 호가 구독 "표시 중에만" 복원 (anchor `detail-orderbook-live-only`)
 * 종목 상세 진입 호가 구독이 #939에서 제거됐다가, 수동매매 시 호가 필요성으로 **진입 시 구독 / 이탈 즉시 해제**
 * 방식으로 복원됐다(옛 LRU·debounce·5분 idle 잔류 폐기). 호가(H0UNASP0)는 STRATEGY(전략 ACTIVE) + DETAIL
 * (상세 표시 중) 두 owner에서 구독되며 단일 구독을 공유한다(F11 패턴). 본 테스트가 보증하는 것:
 * 1. DETAIL enter → 구독 / exit → 즉시 해제
 * 2. STRATEGY enter → 구독 / deactivate → 해제
 * 3. DETAIL·STRATEGY 공존 — 한 owner 빠져도 다른 owner 남으면 WS 유지(모든 owner 빠질 때만 unsub)
 * 4. DETAIL idle 안전망 — EXIT 누락 시 timeout 경과 후 회수(STRATEGY는 idle 대상 아님)
 */
class OrderbookSubscriptionRouterTest {
    private lateinit var mockKisWsPort: TestKisWsPort
    private lateinit var router: OrderbookSubscriptionRouter

    @BeforeEach
    fun setUp() {
        mockKisWsPort = TestKisWsPort()
        router = OrderbookSubscriptionRouter(mockKisWsPort, mockk(relaxed = true))
    }

    // ── DETAIL owner 호가 구독 (상세 표시 중에만) ──────────────────────────────

    @Test
    fun `종목 상세 진입 시 호가 구독 시작 (DETAIL owner)`() {
        router.onSymbolDetailEntered("005930")

        assertTrue(mockKisWsPort.orderbookSubscribed.contains("005930"))
        assertTrue(router.current().contains("005930"))
        assertEquals(1, router.current().size)
    }

    @Test
    fun `종목 상세 이탈 시 호가 즉시 해제 (DETAIL owner)`() {
        router.onSymbolDetailEntered("005930")
        assertTrue(router.current().contains("005930"))

        router.onSymbolDetailExited("005930")

        assertFalse(router.current().contains("005930"))
        assertTrue(mockKisWsPort.orderbookUnsubscribed.contains("005930"))
        assertEquals(0, router.current().size)
    }

    @Test
    fun `종목 상세 재진입은 중복 subscribe 없이 lastSeen 갱신만`() {
        router.onSymbolDetailEntered("005930")
        val subCountBefore = mockKisWsPort.subscribeOrderbookCount

        router.onSymbolDetailEntered("005930")

        assertEquals(subCountBefore, mockKisWsPort.subscribeOrderbookCount)
        assertEquals(1, router.current().size)
    }

    @Test
    fun `없는 DETAIL demand 이탈 호출은 no-op`() {
        router.onSymbolDetailExited("005930")

        assertFalse(router.current().contains("005930"))
        assertFalse(mockKisWsPort.orderbookUnsubscribed.contains("005930"))
    }

    // ── STRATEGY owner 호가 구독 (전략 ACTIVE 전용) ────────────────────────────

    @Test
    fun `전략 ACTIVE 시 호가 구독 시작 (STRATEGY owner)`() {
        router.onStrategyActivated("093370", "strategy:86")

        assertTrue(mockKisWsPort.orderbookSubscribed.contains("093370"))
        assertTrue(router.current().contains("093370"))
        assertEquals(1, router.current().size)
    }

    @Test
    fun `전략 비활성 시 호가 구독 해제 (STRATEGY owner)`() {
        router.onStrategyActivated("093370", "strategy:86")
        assertTrue(router.current().contains("093370"))

        router.onStrategyDeactivated("093370", "strategy:86")

        assertFalse(router.current().contains("093370"))
        assertTrue(mockKisWsPort.orderbookUnsubscribed.contains("093370"))
        assertEquals(0, router.current().size)
    }

    @Test
    fun `같은 종목 여러 전략 demand는 합집합 — 하나 빠져도 다른 전략 남으면 WS 유지`() {
        router.onStrategyActivated("093370", "strategy:86")
        router.onStrategyActivated("093370", "strategy:99")
        // 멱등 + 합집합 — 구독은 1회만 발사
        assertEquals(1, mockKisWsPort.subscribeOrderbookCount)

        router.onStrategyDeactivated("093370", "strategy:86")
        // strategy:99 demand 남음 → WS 유지
        assertTrue(router.current().contains("093370"))
        assertFalse(mockKisWsPort.orderbookUnsubscribed.contains("093370"))

        router.onStrategyDeactivated("093370", "strategy:99")
        // 마지막 demand 빠짐 → unsub
        assertFalse(router.current().contains("093370"))
        assertTrue(mockKisWsPort.orderbookUnsubscribed.contains("093370"))
    }

    @Test
    fun `같은 종목 동일 전략 재요청은 멱등 — 중복 subscribe 없음`() {
        router.onStrategyActivated("093370", "strategy:86")
        val subCountBefore = mockKisWsPort.subscribeOrderbookCount

        router.onStrategyActivated("093370", "strategy:86")

        assertEquals(subCountBefore, mockKisWsPort.subscribeOrderbookCount)
        assertEquals(1, router.current().size)
    }

    @Test
    fun `없는 전략 demand 비활성 호출은 no-op`() {
        router.onStrategyDeactivated("093370", "strategy:86")

        assertFalse(router.current().contains("093370"))
        assertFalse(mockKisWsPort.orderbookUnsubscribed.contains("093370"))
    }

    // ── DETAIL·STRATEGY 공존 (F11 — 단일 구독 공유) ────────────────────────────

    @Test
    fun `DETAIL과 STRATEGY 공존 — 단일 구독 공유, 한 owner 빠져도 다른 owner 남으면 WS 유지`() {
        // 전략이 먼저 호가를 요구한 종목을 사용자가 상세로 본다.
        router.onStrategyActivated("005930", "strategy:86")
        router.onSymbolDetailEntered("005930")
        // 단일 H0UNASP0 구독 공유 — subscribe는 1회만.
        assertEquals(1, mockKisWsPort.subscribeOrderbookCount)
        assertTrue(router.current().contains("005930"))

        // 상세 이탈 — STRATEGY demand 남으므로 WS 유지.
        router.onSymbolDetailExited("005930")
        assertTrue(router.current().contains("005930"))
        assertFalse(mockKisWsPort.orderbookUnsubscribed.contains("005930"))

        // 전략까지 빠지면 unsub.
        router.onStrategyDeactivated("005930", "strategy:86")
        assertFalse(router.current().contains("005930"))
        assertTrue(mockKisWsPort.orderbookUnsubscribed.contains("005930"))
    }

    @Test
    fun `DETAIL이 먼저 구독한 종목에 STRATEGY가 합류 — DETAIL 이탈해도 STRATEGY로 유지`() {
        router.onSymbolDetailEntered("005930")
        router.onStrategyActivated("005930", "strategy:86")
        assertEquals(1, mockKisWsPort.subscribeOrderbookCount)

        // 상세 이탈 즉시 회수 시도하나 STRATEGY demand 남음 → WS 유지.
        router.onSymbolDetailExited("005930")
        assertTrue(router.current().contains("005930"))
        assertFalse(mockKisWsPort.orderbookUnsubscribed.contains("005930"))
    }

    // ── DETAIL idle 안전망 (EXIT 누락 대비) ────────────────────────────────────

    @Test
    fun `DETAIL idle 안전망 — EXIT 누락 시 timeout 경과 후 회수`() {
        // timeout -1ms 주입 → enter 직후에도 (now - lastSeen) > -1 항상 true (즉시 stale 판정).
        val idleRouter = OrderbookSubscriptionRouter(mockKisWsPort, mockk(relaxed = true), detailIdleTimeoutMs = -1L)
        idleRouter.onSymbolDetailEntered("005930")
        assertTrue(idleRouter.current().contains("005930"))

        idleRouter.evictIdleDetailSubscriptions()

        assertFalse(idleRouter.current().contains("005930"))
        assertTrue(mockKisWsPort.orderbookUnsubscribed.contains("005930"))
    }

    @Test
    fun `DETAIL idle 안전망은 STRATEGY demand를 끊지 않는다`() {
        val idleRouter = OrderbookSubscriptionRouter(mockKisWsPort, mockk(relaxed = true), detailIdleTimeoutMs = -1L)
        idleRouter.onStrategyActivated("093370", "strategy:86")
        idleRouter.onSymbolDetailEntered("093370")

        idleRouter.evictIdleDetailSubscriptions()

        // DETAIL은 안전망으로 빠졌어도 STRATEGY demand가 남아 WS 유지.
        assertTrue(idleRouter.current().contains("093370"))
        assertFalse(mockKisWsPort.orderbookUnsubscribed.contains("093370"))
    }

    @Test
    fun `DETAIL idle 안전망 — default timeout(15분) 미경과 종목은 유지`() {
        // default 15분 timeout — 방금 enter한 종목은 안전망에 걸리지 않는다.
        router.onSymbolDetailEntered("005930")

        router.evictIdleDetailSubscriptions()

        assertTrue(router.current().contains("005930"))
        assertFalse(mockKisWsPort.orderbookUnsubscribed.contains("005930"))
    }

    // ── snapshotDemands owner 라벨 — 체결가 라우터와 단일 prefix 표기 대칭 ────────

    @Test
    fun `snapshotDemands — STRATEGY bare ownerId에 strategy prefix 1회, DETAIL은 detail`() {
        router.onStrategyActivated("005930", "86") // bare
        router.onSymbolDetailEntered("005930")

        val owners = router.snapshotDemands().first { it.code == "005930" }.owners
        assertTrue(owners.contains("strategy:86")) // strategy: prefix 1회 부여
        assertTrue(owners.contains("detail")) // DETAIL은 ownerId 미추적
    }

    @Test
    fun `snapshotDemands — legacy strategy prefix ownerId여도 이중 prefix 방어`() {
        router.onStrategyActivated("005930", "strategy:86") // 이미 prefix 포함(롤링 배포 잔존)

        val owners = router.snapshotDemands().first { it.code == "005930" }.owners
        assertTrue(owners.contains("strategy:86")) // strategy:strategy:86 아님
        assertTrue(owners.none { it.startsWith("strategy:strategy:") })
    }

    @Test
    fun `snapshotDemands — 세션 식별자 ownerId(daytrade-paper)는 보존 + strategy prefix 1회`() {
        router.onStrategyActivated("005930", "daytrade-paper:5")

        val owners = router.snapshotDemands().first { it.code == "005930" }.owners
        assertTrue(owners.contains("strategy:daytrade-paper:5")) // 세션 식별자 보존 + 단일 strategy prefix
    }

    /**
     * 테스트용 KisWsPort mock (Mockito 대신 직접 구현 — 단순 추적)
     */
    class TestKisWsPort : KisWsPort {
        val orderbookSubscribed: MutableSet<String> = CopyOnWriteArraySet()
        val orderbookUnsubscribed: MutableSet<String> = CopyOnWriteArraySet()
        var subscribeOrderbookCount = 0

        override fun connect() {} // no-op stub — hotfix: KisWsPort.connect() 시그니처 추가

        override fun subscribe(codes: List<String>) {}

        override fun unsubscribe(codes: List<String>) {}

        override fun subscribedCodes(): Set<String> = emptySet()

        override fun subscribeOrderbook(codes: List<String>) {
            orderbookSubscribed.addAll(codes)
            subscribeOrderbookCount += codes.size
        }

        override fun unsubscribeOrderbook(codes: List<String>) {
            orderbookSubscribed.removeAll(codes.toSet())
            orderbookUnsubscribed.addAll(codes)
        }

        override fun subscribedOrderbookCodes(): Set<String> = orderbookSubscribed.toSet()

        override fun subscribeMarketStatus(codes: List<String>) {}

        override fun unsubscribeMarketStatus(codes: List<String>) {}

        override fun subscribedMarketStatusCodes(): Set<String> = emptySet()
    }
}
