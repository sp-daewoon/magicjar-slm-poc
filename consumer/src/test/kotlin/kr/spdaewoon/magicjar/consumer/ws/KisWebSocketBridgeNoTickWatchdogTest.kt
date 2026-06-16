package kr.spdaewoon.magicjar.consumer.ws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisApprovalAdapter
import kr.spdaewoon.magicjar.consumer.order.KisExecutionWsListener
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.candle.CandleSession
import kr.spdaewoon.magicjar.domain.market.port.QuoteCachePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * KisWebSocketBridge 종목별 무tick watchdog 단위테스트 (결함 ② — 403870 H0UNCNT0 silent 누락).
 *
 * 검증 매트릭스:
 *   - subscribe(신규 논리 구독) 시 lastTickAtMsByCode seed + noTickWarned reset.
 *   - recordTickReceived(tick 수신) 시 lastTick 갱신 + noTickWarned 재무장(remove).
 *   - unsubscribe 시 watchdog state(lastTick·warned) 정리.
 *   - runNoTickWatchdogCheck — 미연결 시 no-op(상태 변경 0), 비활성(threshold≤0) 시 no-op.
 *   - runNoTickWatchdogCheck — 정규장 세션 중 stale 종목 1회 WARN 마킹(noTickWarned) + 중복 차단.
 *     (시각 의존 가드: 현재 정규장 세션이 아니면 게이트 분기만 검증 — 오탐 spam 방지 설계 정합.)
 *
 * AI 호출 0 — 룰 5.
 */
class KisWebSocketBridgeNoTickWatchdogTest {
    private val approvalAdapter = mockk<KisApprovalAdapter>(relaxed = true)
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val quoteCachePort = mockk<QuoteCachePort>(relaxed = true)
    private val wsClient = mockk<ReactorNettyWebSocketClient>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)

    @BeforeEach
    fun setUp() {
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        every { approvalAdapter.getApprovalKey(any(), any()) } returns "mock-approval-key"
    }

    /** threshold(초)를 주입한 bridge — watchdog 임계 제어용. */
    private fun newBridge(noTickWatchdogSeconds: Long): KisWebSocketBridge {
        val executionListenerProvider = mockk<ObjectProvider<KisExecutionWsListener>>(relaxed = true)
        every { executionListenerProvider.getIfAvailable() } returns null
        val priceMetricsPortProvider =
            mockk<ObjectProvider<kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort>>(relaxed = true)
        every { priceMetricsPortProvider.getIfAvailable() } returns null
        return KisWebSocketBridge(
            approvalAdapter = approvalAdapter,
            redisTemplate = redisTemplate,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            liveCandleUpdater = mockk(relaxed = true),
            quoteCachePort = quoteCachePort,
            wsClient = wsClient,
            wsUrlProd = "ws://localhost:21000",
            wsUrlVps = "ws://localhost:31000",
            magicjarMode = "REAL",
            defaultUserId = "user001",
            noTickWatchdogSeconds = noTickWatchdogSeconds,
            executionListenerProvider = executionListenerProvider,
            systemEventLogger = systemEventLogger,
            priceMetricsPortProvider = priceMetricsPortProvider,
            credentialRepositoryProvider =
                mockk<ObjectProvider<ConsumerUserKisCredentialJpaRepository>>(relaxed = true)
                    .also { every { it.ifAvailable } returns null },
            slotLedgerProvider =
                mockk<ObjectProvider<kr.spdaewoon.magicjar.consumer.ingestion.WsSlotBudgetLedger>>(relaxed = true)
                    .also { every { it.ifAvailable } returns null },
        )
    }

    private fun getSession(bridge: KisWebSocketBridge): KisWebSocketSession {
        val field = KisWebSocketBridge::class.java.getDeclaredField("session")
        field.isAccessible = true
        return field.get(bridge) as KisWebSocketSession
    }

    @Suppress("UNCHECKED_CAST")
    private fun lastTickMap(bridge: KisWebSocketBridge): MutableMap<String, Long> {
        val f = KisWebSocketBridge::class.java.getDeclaredField("lastTickAtMsByCode")
        f.isAccessible = true
        return f.get(bridge) as MutableMap<String, Long>
    }

    @Suppress("UNCHECKED_CAST")
    private fun warnedSet(bridge: KisWebSocketBridge): MutableSet<String> {
        val f = KisWebSocketBridge::class.java.getDeclaredField("noTickWarned")
        f.isAccessible = true
        return f.get(bridge) as MutableSet<String>
    }

    private fun invokeRecordTickReceived(
        bridge: KisWebSocketBridge,
        code: String,
    ) {
        val m = KisWebSocketBridge::class.java.getDeclaredMethod("recordTickReceived", String::class.java)
        m.isAccessible = true
        m.invoke(bridge, code)
    }

    private fun invokeWatchdogCheck(bridge: KisWebSocketBridge) {
        // internal fun — 동일 모듈 테스트에서 직접 호출.
        bridge.runNoTickWatchdogCheck()
    }

    private fun isInSessionNow(): Boolean {
        val now = Instant.now().atZone(ZoneId.of("Asia/Seoul"))
        return CandleSession.isInIntegratedSession(now.hour * 60 + now.minute)
    }

    @Test
    fun `subscribe 신규 구독 시 무tick watchdog seed + warned reset`() {
        val bridge = newBridge(120L)

        bridge.subscribe(listOf("403870"))

        // 구독 시점 lastTick seed (첫 tick 도착 전 stale 카운트 시작점).
        assertThat(lastTickMap(bridge)).containsKey("403870")
        assertThat(warnedSet(bridge)).doesNotContain("403870")
    }

    @Test
    fun `recordTickReceived 는 lastTick 갱신 + warned 재무장(remove)`() {
        val bridge = newBridge(120L)
        bridge.subscribe(listOf("403870"))
        // 이전 stale 윈도우에서 WARN 발화한 상태 시뮬레이션.
        warnedSet(bridge).add("403870")
        val before = lastTickMap(bridge)["403870"]!!

        Thread.sleep(5L)
        invokeRecordTickReceived(bridge, "403870")

        assertThat(lastTickMap(bridge)["403870"]!!).isGreaterThanOrEqualTo(before)
        // tick 복구 → warned 제거(다음 stale 시 1회 다시 WARN 가능).
        assertThat(warnedSet(bridge)).doesNotContain("403870")
    }

    @Test
    fun `unsubscribe 시 watchdog state 정리`() {
        val bridge = newBridge(120L)
        bridge.subscribe(listOf("403870"))
        warnedSet(bridge).add("403870")

        bridge.unsubscribe(listOf("403870"))

        assertThat(lastTickMap(bridge)).doesNotContainKey("403870")
        assertThat(warnedSet(bridge)).doesNotContain("403870")
    }

    @Test
    fun `runNoTickWatchdogCheck — 미연결이면 no-op (상태 변경 0)`() {
        val bridge = newBridge(1L)
        bridge.subscribe(listOf("403870"))
        // session 미연결(connected=false) — 구독만 등록된 상태.
        lastTickMap(bridge)["403870"] = System.currentTimeMillis() - 10_000L // 충분히 stale

        invokeWatchdogCheck(bridge)

        // 미연결 가드로 점검 skip → warned 마킹 0.
        assertThat(warnedSet(bridge)).doesNotContain("403870")
    }

    @Test
    fun `runNoTickWatchdogCheck — 비활성(threshold 0) 이면 no-op`() {
        val bridge = newBridge(0L)
        getSession(bridge).onConnected()
        bridge.subscribe(listOf("403870"))
        lastTickMap(bridge)["403870"] = System.currentTimeMillis() - 10_000L

        invokeWatchdogCheck(bridge)

        assertThat(warnedSet(bridge)).doesNotContain("403870")
    }

    @Test
    fun `runNoTickWatchdogCheck — 연결+정규장+stale 종목 1회 WARN 마킹 + 중복 차단`() {
        val bridge = newBridge(1L) // 1초 임계
        getSession(bridge).onConnected()
        bridge.subscribe(listOf("403870", "005930"))
        val fallbackCalls = AtomicInteger(0)
        bridge.onNoTickDetected = { code, trId, _ ->
            if (code == "403870" && trId == "H0UNCNT0") fallbackCalls.incrementAndGet()
        }
        val now = System.currentTimeMillis()
        // 403870 = stale(3초 전 마지막 tick), 005930 = fresh(방금 tick).
        lastTickMap(bridge)["403870"] = now - 3_000L
        lastTickMap(bridge)["005930"] = now

        invokeWatchdogCheck(bridge)

        if (isInSessionNow()) {
            // 정규장 세션 중 — stale 종목만 WARN 마킹, fresh 종목은 미마킹.
            assertThat(warnedSet(bridge)).contains("403870")
            assertThat(warnedSet(bridge)).doesNotContain("005930")
            assertThat(fallbackCalls.get()).isEqualTo(1)

            // 2차 점검 — 이미 마킹된 종목은 중복 WARN 차단(set 유지, 추가 0).
            val sizeAfterFirst = warnedSet(bridge).size
            invokeWatchdogCheck(bridge)
            assertThat(warnedSet(bridge).size).isEqualTo(sizeAfterFirst)
            // WARN은 중복 차단되지만 fallback hook은 service dedupe에 맡기기 위해 계속 호출한다.
            assertThat(fallbackCalls.get()).isEqualTo(2)

            // tick 복구 → 재무장 → 다시 stale 시 1회 WARN 가능.
            invokeRecordTickReceived(bridge, "403870")
            assertThat(warnedSet(bridge)).doesNotContain("403870")
        } else {
            // 정규장 외 — 세션 게이트로 점검 skip(자연 무거래 오탐 방지). 마킹 0이어야 함.
            assertThat(warnedSet(bridge)).doesNotContain("403870")
            assertThat(fallbackCalls.get()).isZero()
        }
    }
}
