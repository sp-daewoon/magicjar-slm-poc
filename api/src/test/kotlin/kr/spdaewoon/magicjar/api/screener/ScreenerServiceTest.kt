package kr.spdaewoon.magicjar.api.screener

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerEvaluateRequest
import kr.spdaewoon.magicjar.api.persistence.screener.ScreeningRunRepositoryAdapter
import kr.spdaewoon.magicjar.api.service.screener.ScreenerService
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.screener.ScreenerCriteria
import kr.spdaewoon.magicjar.domain.screener.ScreenerMatch
import kr.spdaewoon.magicjar.domain.screener.ScreenerRunResult
import kr.spdaewoon.magicjar.domain.screener.ScreenerScoreBreakdown
import kr.spdaewoon.magicjar.domain.screener.port.ScreenerCachePort
import kr.spdaewoon.magicjar.domain.screener.port.ScreenerEngine
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * ScreenerService 단위 테스트 (ADR 0071 §2-4 D-1 + §5 + spec §2-3).
 *
 * 케이스:
 * 1. 캐시 HIT — ScreenerEngine 호출 없음 + cacheHit=true
 * 2. 캐시 MISS + inline eval — ScreenerEngine 호출 + 결과 캐시 PUT
 * 3. 분산락 충돌 + 캐시 HIT — 409 없음 + cacheHit=true
 * 4. 분산락 충돌 + 캐시 MISS — 409 Conflict 예외
 * 5. 옵션 영속 ON — persist 호출
 * 6. userId 격리 — ScreenerEngine에 criteria.userId 전달 확인
 * 7. request.interval 기준봉 — ScreenerCriteria.interval 전달 확인
 * 8. 지원하지 않는 interval — 400 + engine/cache 호출 없음
 */
class ScreenerServiceTest {
    private val screenerEngine: ScreenerEngine = mock()
    private val screenerCachePort: ScreenerCachePort = mock()
    private val screeningRunRepositoryAdapter: ScreeningRunRepositoryAdapter = mock()
    private val kafkaTemplate: KafkaTemplate<String, String> = mock()
    private val objectMapper = ObjectMapper()

    private lateinit var service: ScreenerService

    @BeforeEach
    fun setUp() {
        service =
            ScreenerService(
                screenerEngine = screenerEngine,
                screenerCachePort = screenerCachePort,
                screeningRunRepositoryAdapter = screeningRunRepositoryAdapter,
                kafkaTemplate = kafkaTemplate,
                objectMapper = objectMapper,
            )
    }

    // ── 픽스처 ────────────────────────────────────────────────────────

    private fun buildResult(cacheHit: Boolean = false): ScreenerRunResult =
        ScreenerRunResult(
            universeSize = 2847,
            evaluatedCount = 2814,
            matchedCount = 3,
            skippedCount = 33,
            skippedReasons = mapOf("캔들 미수집" to 33),
            matches =
                listOf(
                    ScreenerMatch(
                        instrumentCode = "005930",
                        instrumentName = "삼성전자",
                        rank = 1,
                        score = BigDecimal("0.854321"),
                        breakdown =
                            ScreenerScoreBreakdown(
                                signal = BigDecimal("0.4"),
                                volume = BigDecimal("0.3"),
                                atr = BigDecimal("0.18"),
                                cap = BigDecimal("0.10"),
                                total = BigDecimal("0.854321"),
                            ),
                        reason = "RSI(14) < 30",
                    ),
                ),
            elapsedMs = 4521L,
            cacheHit = cacheHit,
            cacheKey = "",
        )

    private fun buildRequest(
        userId: Long = 1L,
        strategyId: Long = 42L,
        interval: String = "1d",
    ): ScreenerEvaluateRequest =
        ScreenerEvaluateRequest(
            userId = userId,
            strategyId = strategyId,
            interval = interval,
            limit = 50,
        )

    // ── 1. 캐시 HIT ───────────────────────────────────────────────────

    @Test
    fun `캐시 HIT — ScreenerEngine 호출 없음 + cacheHit=true`() {
        val request = buildRequest()
        val cachedResult = buildResult(cacheHit = true).copy(cacheKey = "screener:result:dsl:x:universe:y")
        whenever(screenerCachePort.tryLock(any(), any(), any())).thenReturn(true)
        whenever(screenerCachePort.get(any())).thenReturn(cachedResult)

        val dto = service.evaluate(request)

        assertThat(dto.cacheHit).isTrue()
        assertThat(dto.matchedCount).isEqualTo(3)
        verify(screenerEngine, never()).screen(any())
        verify(screenerCachePort, never()).put(any(), any(), any())
    }

    // ── 2. 캐시 MISS + inline eval ────────────────────────────────────

    @Test
    fun `캐시 MISS + inline eval — ScreenerEngine 호출 + 결과 캐시 PUT`() {
        val request = buildRequest()
        val engineResult = buildResult()
        whenever(screenerCachePort.tryLock(any(), any(), any())).thenReturn(true)
        whenever(screenerCachePort.get(any())).thenReturn(null)
        whenever(screenerEngine.screen(any<ScreenerCriteria>())).thenReturn(engineResult)
        whenever(screeningRunRepositoryAdapter.persist(any(), any(), any(), any(), any())).thenReturn(null)

        val dto = service.evaluate(request)

        assertThat(dto.cacheHit).isFalse()
        assertThat(dto.matchedCount).isEqualTo(3)
        assertThat(dto.universeSize).isEqualTo(2847)
        verify(screenerEngine).screen(any<ScreenerCriteria>())
        verify(screenerCachePort).put(any(), any(), any())
    }

    // ── 3. 분산락 충돌 + 캐시 HIT ────────────────────────────────────

    @Test
    fun `분산락 충돌 + 캐시 HIT — 409 없음 + cacheHit=true`() {
        val request = buildRequest()
        val cachedResult = buildResult(cacheHit = true).copy(cacheKey = "screener:result:dsl:x:universe:y")
        whenever(screenerCachePort.tryLock(any(), any(), any())).thenReturn(false)
        whenever(screenerCachePort.get(any())).thenReturn(cachedResult)

        val dto = service.evaluate(request)

        assertThat(dto.cacheHit).isTrue()
        verify(screenerEngine, never()).screen(any())
    }

    // ── 4. 분산락 충돌 + 캐시 MISS — 409 ────────────────────────────

    @Test
    fun `분산락 충돌 + 캐시 MISS — 409 Conflict`() {
        val request = buildRequest()
        whenever(screenerCachePort.tryLock(any(), any(), any())).thenReturn(false)
        whenever(screenerCachePort.get(any())).thenReturn(null)

        val thrown =
            assertThatThrownBy { service.evaluate(request) }
                .isInstanceOf(ResponseStatusException::class.java)
        thrown
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.CONFLICT)
    }

    // ── 5. 옵션 영속 ON — persist 호출 ───────────────────────────────

    @Test
    fun `옵션 영속 ON — persist 호출 (screeningRunRepositoryAdapter)`() {
        val request = buildRequest()
        val engineResult = buildResult()
        whenever(screenerCachePort.tryLock(any(), any(), any())).thenReturn(true)
        whenever(screenerCachePort.get(any())).thenReturn(null)
        whenever(screenerEngine.screen(any<ScreenerCriteria>())).thenReturn(engineResult)
        whenever(screeningRunRepositoryAdapter.persist(any(), any(), any(), any(), any())).thenReturn(1001L)

        service.evaluate(request)

        verify(screeningRunRepositoryAdapter).persist(any(), eq(1L), eq(42L), any(), any())
    }

    // ── 6. userId 격리 ────────────────────────────────────────────────

    @Test
    fun `userId 격리 — ScreenerEngine criteria에 userId 정합 전달`() {
        val request = buildRequest(userId = 99999L, strategyId = 7L)
        val engineResult = buildResult()
        whenever(screenerCachePort.tryLock(any(), any(), any())).thenReturn(true)
        whenever(screenerCachePort.get(any())).thenReturn(null)
        whenever(screenerEngine.screen(any<ScreenerCriteria>())).thenReturn(engineResult)
        whenever(screeningRunRepositoryAdapter.persist(any(), any(), any(), any(), any())).thenReturn(null)

        service.evaluate(request)

        val captor = argumentCaptor<ScreenerCriteria>()
        verify(screenerEngine).screen(captor.capture())
        assertThat(captor.firstValue.userId).isEqualTo(99999L)
        assertThat(captor.firstValue.strategyId).isEqualTo(7L)
    }

    // ── 7. 기준봉 전달 ────────────────────────────────────────────────

    @Test
    fun `request interval 3m — ScreenerEngine criteria interval THREE_MIN 전달`() {
        val request = buildRequest(interval = "3m")
        val engineResult = buildResult()
        whenever(screenerCachePort.tryLock(any(), any(), any())).thenReturn(true)
        whenever(screenerCachePort.get(any())).thenReturn(null)
        whenever(screenerEngine.screen(any<ScreenerCriteria>())).thenReturn(engineResult)
        whenever(screeningRunRepositoryAdapter.persist(any(), any(), any(), any(), any())).thenReturn(null)

        service.evaluate(request)

        val captor = argumentCaptor<ScreenerCriteria>()
        verify(screenerEngine).screen(captor.capture())
        assertThat(captor.firstValue.interval).isEqualTo(CandleInterval.THREE_MIN)
    }

    // ── 8. 지원하지 않는 interval ────────────────────────────────────

    @Test
    fun `지원하지 않는 interval — 400 Bad Request + engine cache 호출 없음`() {
        val request = buildRequest(interval = "2h")

        val thrown =
            assertThatThrownBy { service.evaluate(request) }
                .isInstanceOf(ResponseStatusException::class.java)
        thrown
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.BAD_REQUEST)
        verify(screenerCachePort, never()).tryLock(any(), any(), any())
        verify(screenerEngine, never()).screen(any())
    }
}
