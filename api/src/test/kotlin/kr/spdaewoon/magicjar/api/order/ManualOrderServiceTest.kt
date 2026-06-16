package kr.spdaewoon.magicjar.api.order

import kr.spdaewoon.magicjar.api.controller.order.dto.ManualOrderRequestDto
import kr.spdaewoon.magicjar.api.messaging.order.ManualOrderIntentKafkaProducer
import kr.spdaewoon.magicjar.api.service.order.ManualOrderService
import kr.spdaewoon.magicjar.api.service.order.RealTradingConsentService
import kr.spdaewoon.magicjar.domain.order.execution.OrderIntent
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.KafkaException
import org.springframework.web.server.ResponseStatusException

/**
 * ManualOrderServiceTest — FIX B 검증 (T-MAN idempotencyKey 강화 + DTO Bean Validation 이전 + catch 좁힘).
 *
 * 케이스:
 *   (a) FIX B-1 P0 — 같은 사용자·같은 종목·같은 side 2 회 호출 시 idempotencyKey unique (UUID 도입 효과)
 *   (b) FIX B-1 P0 — 같은 사용자·같은 초·다른 종목 시 idempotencyKey 충돌 0건
 *   (c) FIX B-1 P0 — idempotencyKey 포맷 검증 ("manual:{userId}:{code}:{side}:{UUID}" prefix)
 *   (d) FIX B-2 P1 — DTO @Pattern validation 통과 후 service valueOf 정상 (controller 통과 시나리오)
 *   (e) FIX B-3 P2 — KafkaException 시 503 SERVICE_UNAVAILABLE 매핑
 *   (f) FIX B-3 P2 — IllegalArgumentException(producer require fail)은 503 매핑 X (catch 좁힘 효과)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082)
 */
class ManualOrderServiceTest {
    private val manualProducer: ManualOrderIntentKafkaProducer =
        Mockito.mock(ManualOrderIntentKafkaProducer::class.java)
    private val consentService: RealTradingConsentService =
        Mockito.mock(RealTradingConsentService::class.java)

    @Suppress("UNCHECKED_CAST")
    private val killSwitchProvider: ObjectProvider<KillSwitchQueryPort> =
        Mockito.mock(ObjectProvider::class.java) as ObjectProvider<KillSwitchQueryPort>

    private val sut =
        ManualOrderService(
            manualProducer = manualProducer,
            consentService = consentService,
            killSwitchProvider = killSwitchProvider,
            magicjarMode = "MOCK", // 3중 게이트 fast precheck skip (MOCK fallback path)
            magicjarAllowReal = false,
        )

    private fun makeRequest(
        userId: String = "74683223",
        code: String = "005930",
        side: String = "BUY",
        priceType: String = "MARKET",
        quantity: Int = 1,
        limitPrice: String? = null,
    ) = ManualOrderRequestDto(
        userId = userId,
        code = code,
        side = side,
        priceType = priceType,
        quantity = quantity,
        limitPrice = limitPrice,
    )

    @Test
    fun `(a) FIX B-1 — 같은 사용자·같은 종목·같은 side 2 회 호출 시 idempotencyKey unique (UUID 도입)`() {
        Mockito.`when`(killSwitchProvider.ifAvailable).thenReturn(null) // KillSwitch 부재 graceful skip

        val req = makeRequest()
        val result1 = sut.submit(req)
        val result2 = sut.submit(req)

        assertNotEquals(
            result1.idempotencyKey,
            result2.idempotencyKey,
            "같은 사용자·같은 종목·같은 side 2 회 호출 시 idempotencyKey unique 의무 (UUID 도입 효과)",
        )
    }

    @Test
    fun `(b) FIX B-1 — 같은 사용자·같은 초·다른 종목 시 idempotencyKey 충돌 0건`() {
        Mockito.`when`(killSwitchProvider.ifAvailable).thenReturn(null)

        // 같은 초 내 가능한 빠르게 2 회 호출 (epochSecond 동일 가정 — 다른 종목)
        val req1 = makeRequest(code = "005930")
        val req2 = makeRequest(code = "000660")
        val result1 = sut.submit(req1)
        val result2 = sut.submit(req2)

        assertNotEquals(
            result1.idempotencyKey,
            result2.idempotencyKey,
            "같은 사용자·같은 초·다른 종목 시 idempotencyKey 충돌 0건 의무 (코덱스 review P2 결함 해소)",
        )
        assertTrue(result1.idempotencyKey.contains(":005930:"), "code는 idempotencyKey에 포함")
        assertTrue(result2.idempotencyKey.contains(":000660:"), "code는 idempotencyKey에 포함")
    }

    @Test
    fun `(c) FIX B-1 — idempotencyKey 포맷 검증 ('manual_userId_code_side_UUID' prefix)`() {
        Mockito.`when`(killSwitchProvider.ifAvailable).thenReturn(null)

        val req = makeRequest(userId = "74683223", code = "005930", side = "BUY")
        val result = sut.submit(req)

        // 포맷: manual:74683223:005930:BUY:{UUID}
        val parts = result.idempotencyKey.split(":")
        assertEquals(5, parts.size, "idempotencyKey 5 segment 구성 (manual·userId·code·side·UUID)")
        assertEquals("manual", parts[0])
        assertEquals("74683223", parts[1])
        assertEquals("005930", parts[2])
        assertEquals("BUY", parts[3])
        // 5번째 segment는 UUID — 길이 36 (8-4-4-4-12 hyphenated)
        assertEquals(36, parts[4].length, "UUID 36자(8-4-4-4-12) 의무")
    }

    @Test
    fun `(d) FIX B-2 — 정상 BUY MARKET 시 OrderIntent source=MANUAL 채움 (controller 통과 시나리오)`() {
        Mockito.`when`(killSwitchProvider.ifAvailable).thenReturn(null)

        val req = makeRequest(side = "BUY", priceType = "MARKET")
        sut.submit(req)

        val captor = argumentCaptor<OrderIntent>()
        verify(manualProducer).publish(captor.capture())
        val captured = captor.firstValue
        assertEquals(OrderSource.MANUAL, captured.source, "source=MANUAL LOCK")
        assertEquals(0L, captured.signalId, "MANUAL signalId=0L sentinel")
        assertEquals(0L, captured.strategyId, "MANUAL strategyId=0L sentinel")
        assertTrue(captured.idempotencyKey.startsWith("manual:"), "MANUAL idempotencyKey prefix")
    }

    @Test
    fun `(e) FIX B-3 — KafkaException 시 503 SERVICE_UNAVAILABLE 매핑`() {
        Mockito.`when`(killSwitchProvider.ifAvailable).thenReturn(null)
        Mockito.doThrow(KafkaException("broker down")).`when`(manualProducer).publish(any())

        val req = makeRequest()
        val ex = assertThrows<ResponseStatusException> { sut.submit(req) }
        assertEquals(503, ex.statusCode.value(), "KafkaException → 503 KAFKA_PUBLISH_FAILED")
        assertTrue(ex.reason!!.contains("KAFKA_PUBLISH_FAILED"))
    }

    @Test
    fun `(f) FIX B-3 — IllegalArgumentException(producer require fail)은 503 매핑 X (catch 좁힘 효과)`() {
        Mockito.`when`(killSwitchProvider.ifAvailable).thenReturn(null)
        // producer require(source=MANUAL) 위반 시 IllegalArgumentException — 503 분류 부적합
        Mockito.doThrow(IllegalArgumentException("invariant violation")).`when`(manualProducer).publish(any())

        val req = makeRequest()
        // 기존 catch RuntimeException 광범위 → 503 매핑 (잘못된 분류).
        // 신규 catch KafkaException/ExecutionException specific → IllegalArgumentException 그대로 propagate (5xx 일반).
        val ex = assertThrows<IllegalArgumentException> { sut.submit(req) }
        assertEquals("invariant violation", ex.message, "IllegalArgumentException 그대로 propagate 의무")
    }
}
