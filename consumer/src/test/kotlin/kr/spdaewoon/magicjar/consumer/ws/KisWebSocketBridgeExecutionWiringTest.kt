package kr.spdaewoon.magicjar.consumer.ws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisApprovalAdapter
import kr.spdaewoon.magicjar.consumer.order.KisExecutionWsListener
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.port.QuoteCachePort
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import java.net.URI

/**
 * T-O Phase 1 B-1 — KisWebSocketBridge H0STCNI0/H0STCNI9 → KisExecutionWsListener wiring 단위 테스트.
 *
 * ADR 0068 §14-6 LOCK + spec §2.1 B-1 정합.
 *
 * 4건 검증 매트릭스:
 * - test 1: H0STCNI0 (REAL) body 수신 → KisExecutionWsListener.onWebSocketMessage 호출
 * - test 2: H0STCNI9 (MOCK) body 수신 → KisExecutionWsListener.onWebSocketMessage 호출
 * - test 3: ObjectProvider 미합류(getIfAvailable=null) → log.warn + skip (예외 X)
 * - test 4: listener dispatch 예외 → log.error + 종료 (예외 전파 X)
 *
 * KIS-first 출처:
 * - ccnl_notice tr_id 분기 (real→H0STCNI0 / demo→H0STCNI9):
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_notice/ccnl_notice.py
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
class KisWebSocketBridgeExecutionWiringTest {
    private val approvalAdapter = mockk<KisApprovalAdapter>(relaxed = true)
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val quoteCachePort = mockk<QuoteCachePort>(relaxed = true)
    private val wsClient = mockk<ReactorNettyWebSocketClient>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)

    init {
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        every { wsClient.execute(any<URI>(), any()) } returns Mono.never()
    }

    /**
     * test 1: H0STCNI0 (REAL 체결통보) raw body 수신 → KisExecutionWsListener.onWebSocketMessage 호출 검증.
     *
     * ADR 0068 §14-6 wiring fix evidence:
     * - 이전 결함(L489): `"H0STCNI0" -> log.info(...)` — listener 미호출
     * - fix: `"H0STCNI0", "H0STCNI9" -> handleExecutionNotice(parsed.trId, parsed.rawData)`
     * - 검증: handleMessage("0|H0STCNI0|001|...") → listener.onWebSocketMessage("...") 호출 1회
     *
     * KIS WS raw 메시지 포맷: "{타입}|{TR_ID}|{N}|{^delimiter body}"
     *   타입 "0" = 실시간 데이터 / TR_ID H0STCNI0 = 실전 체결통보
     */
    @Test
    fun `H0STCNI0 REAL body 수신 시 listener onWebSocketMessage 호출`() {
        // given: ObjectProvider — listener mock present
        val listener = mockk<KisExecutionWsListener>(relaxed = true)
        val provider = mockk<ObjectProvider<KisExecutionWsListener>>()
        every { provider.getIfAvailable() } returns listener

        val bridge = buildBridge(executionListenerProvider = provider)

        // when: H0STCNI0 raw 메시지 (caret delimiter body 예시 — 23개 컬럼 정합)
        // 컬럼: CUST_ID^ACNT_NO^ODER_NO^...^ODER_PRC
        val executionBody =
            "user001^12345678-01^KO0001234567^^02^^01^^005930^10^70000^113000^N^2^1^001^10^홍길동^삼성전자^^0^^삼성전자^70000"
        val rawMessage = "0|H0STCNI0|001|$executionBody"

        // handleMessage는 private — reflection 호출
        val handleMessage = KisWebSocketBridge::class.java.getDeclaredMethod("handleMessage", String::class.java)
        handleMessage.isAccessible = true
        handleMessage.invoke(bridge, rawMessage)

        // then: listener.onWebSocketMessage 가 raw body 정확히 받음
        verify(exactly = 1) { listener.onWebSocketMessage(executionBody) }
    }

    /**
     * test 2: H0STCNI9 (MOCK 체결통보) raw body 수신 → 동일 listener.onWebSocketMessage 호출 검증.
     *
     * KIS-first ccnl_notice.py env_dv 분기:
     *   real → H0STCNI0 / demo → H0STCNI9
     * listener 내부 분기는 cntgYn/acptYn/rfusYn 기준이므로 tr_id 분기 불필요.
     *   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_notice/ccnl_notice.py
     */
    @Test
    fun `H0STCNI9 MOCK body 수신 시 listener onWebSocketMessage 호출`() {
        val listener = mockk<KisExecutionWsListener>(relaxed = true)
        val provider = mockk<ObjectProvider<KisExecutionWsListener>>()
        every { provider.getIfAvailable() } returns listener

        val bridge = buildBridge(executionListenerProvider = provider, magicjarMode = "MOCK")

        val executionBody =
            "user001^12345678-01^KOMOCK0001^^02^^01^^005930^5^69500^113500^N^2^1^001^5^홍길동^삼성전자^^0^^삼성전자^69500"
        val rawMessage = "0|H0STCNI9|001|$executionBody"

        val handleMessage = KisWebSocketBridge::class.java.getDeclaredMethod("handleMessage", String::class.java)
        handleMessage.isAccessible = true
        handleMessage.invoke(bridge, rawMessage)

        verify(exactly = 1) { listener.onWebSocketMessage(executionBody) }
    }

    /**
     * test 3: ObjectProvider.getIfAvailable() null → graceful skip (예외 X).
     *
     * 헌법 §6 + ADR 0066 ObjectProvider 패턴 정합:
     *   - qa fixture 등 KisExecutionWsListener 빈 미합류 환경에서도 KisWebSocketBridge 부팅 보장
     *   - log.warn 후 skip — 예외 전파 시 KIS WS 수신 흐름 자체 중단 risk
     */
    @Test
    fun `KisExecutionWsListener 미합류 시 log warn 후 skip 예외 없음`() {
        val provider = mockk<ObjectProvider<KisExecutionWsListener>>()
        every { provider.getIfAvailable() } returns null // 빈 미합류 시뮬레이션

        val bridge = buildBridge(executionListenerProvider = provider)

        val rawMessage = "0|H0STCNI0|001|user001^12345678-01^KO0001^^02^^01^^005930^1^70000^113000^N^2^1^001^1^^^^^^^"

        val handleMessage = KisWebSocketBridge::class.java.getDeclaredMethod("handleMessage", String::class.java)
        handleMessage.isAccessible = true
        // 예외 전파 없이 정상 종료해야 함 (handleMessage가 종료해야 KIS WS 수신 흐름 유지)
        handleMessage.invoke(bridge, rawMessage)
        // getIfAvailable이 1회 호출되어 null 분기 진입 검증
        verify(atLeast = 1) { provider.getIfAvailable() }
    }

    /**
     * test 4: listener.onWebSocketMessage가 예외 throw → log.error + 종료 (예외 전파 X).
     *
     * 자본 보호 의무 (§14-7):
     *   - listener 내부 파싱 실패 등으로 예외 throw 시 KIS WS 수신 흐름 자체 중단 시 다음 체결통보 0건 risk
     *   - handleExecutionNotice가 try/catch로 흡수하여 다음 메시지 수신 정상 유지
     */
    @Test
    fun `listener dispatch 예외 시 log error 후 다음 메시지 수신 정상 유지`() {
        val listener = mockk<KisExecutionWsListener>()
        every { listener.onWebSocketMessage(any()) } throws RuntimeException("simulated parse error")
        val provider = mockk<ObjectProvider<KisExecutionWsListener>>()
        every { provider.getIfAvailable() } returns listener

        val bridge = buildBridge(executionListenerProvider = provider)

        val rawMessage = "0|H0STCNI0|001|user001^12345678-01^KO0001^^02^^01^^005930^1^70000^113000^N^2^1^001^1^^^^^^^"

        val handleMessage = KisWebSocketBridge::class.java.getDeclaredMethod("handleMessage", String::class.java)
        handleMessage.isAccessible = true
        // 예외 전파 없이 정상 종료해야 함 — handleExecutionNotice가 try/catch로 흡수
        handleMessage.invoke(bridge, rawMessage)

        verify(exactly = 1) { listener.onWebSocketMessage(any()) }
    }

    // ===== 헬퍼 =====

    private fun buildBridge(
        defaultUserId: String = "user001",
        magicjarMode: String = "REAL",
        executionListenerProvider: ObjectProvider<KisExecutionWsListener>,
    ): KisWebSocketBridge {
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
            magicjarMode = magicjarMode,
            defaultUserId = defaultUserId,
            executionListenerProvider = executionListenerProvider,
            systemEventLogger = systemEventLogger,
            priceMetricsPortProvider = priceMetricsPortProvider,
            credentialRepositoryProvider =
                mockk<ObjectProvider<ConsumerUserKisCredentialJpaRepository>>(
                    relaxed = true,
                ).also {
                    every {
                        it.ifAvailable
                    } returns null
                },
            slotLedgerProvider =
                mockk<ObjectProvider<kr.spdaewoon.magicjar.consumer.ingestion.WsSlotBudgetLedger>>(
                    relaxed = true,
                ).also {
                    every { it.ifAvailable } returns null
                },
        )
    }
}
