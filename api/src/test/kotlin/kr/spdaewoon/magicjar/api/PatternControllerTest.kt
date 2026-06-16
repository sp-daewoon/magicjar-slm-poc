package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.PatternController
import kr.spdaewoon.magicjar.api.controller.dto.PatternResponseDto
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.PatternMatch
import kr.spdaewoon.magicjar.domain.pattern.port.PatternRecognizer
import kr.spdaewoon.magicjar.pattern.cache.RedisPatternCacheAdapter
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import kotlinx.datetime.Instant as KotlinInstant

/**
 * PatternController 슬라이스 테스트 (spec §3-2 Done 정의 + 룰 4 정합).
 * DB·Redis·KIS 없이 @MockBean으로 검증.
 * IndicatorControllerTest 패턴 정합 — OnboardingGuardFilter mock 의무.
 *
 * 검증 1: R1 10 active 인식 — hammer 단일 패턴 200 OK + matches 1건
 * 검증 2: 미지원 패턴 → 200 OK + matches=[] (graceful, KIS L52 정합)
 * 검증 3: R2 stub 패턴 → 200 OK + matches=[] (NEUTRAL 미포함)
 * 검증 4: 캐시 hit → 200 OK + matches 반환 (PatternRecognizer 호출 0건)
 * 검증 5: 종목코드 형식 오류 → 400
 *
 * PR-43: @TestPropertySource(magicjar.pattern.enabled=true) 제거 — @ConditionalOnProperty 임시 게이트 자연 정상화.
 */
@WebMvcTest(controllers = [PatternController::class])
class PatternControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var patternRecognizer: PatternRecognizer

    @MockBean
    lateinit var candleJpaRepository: CandleJpaRepository

    @MockBean
    lateinit var cacheAdapter: RedisPatternCacheAdapter

    // OnboardingGuardFilter mock 의무 (IndicatorControllerTest 패턴 정합)
    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    // ── helper ───────────────────────────────────────────────────────────────

    private fun stubOnboarding() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
    }

    private fun stubCacheMiss() {
        whenever(
            cacheAdapter.get(any(), any(), any(), anyOrNull(), anyOrNull()),
        ).thenReturn(null)
    }

    private fun stubCacheHit(response: PatternResponseDto) {
        val json = objectMapper.writeValueAsString(response)
        whenever(
            cacheAdapter.get(any(), any(), any(), anyOrNull(), anyOrNull()),
        ).thenReturn(json)
    }

    private fun stubEmptyCandles() {
        whenever(
            candleJpaRepository.findByCodeAndIntervalLatest(any(), any(), any()),
        ).thenReturn(emptyList())
        whenever(
            candleJpaRepository.findByCodeAndIntervalAndTsBefore(any(), any(), any(), any()),
        ).thenReturn(emptyList())
    }

    private fun makeHammerMatch(): PatternMatch =
        PatternMatch(
            pattern = "hammer",
            startTime = KotlinInstant.parse("2024-01-02T00:00:00Z"),
            endTime = KotlinInstant.parse("2024-01-02T00:00:00Z"),
            direction = Direction.BULL,
            confidence = BigDecimal.ONE,
        )

    // ── 검증 1: R1 active hammer 인식 — matches 1건 ─────────────────────────

    @Test
    fun `GET patterns hammer 200 OK matches 1건`() {
        stubOnboarding()
        stubCacheMiss()
        stubEmptyCandles()
        whenever(
            patternRecognizer.recognize(any<List<kr.spdaewoon.magicjar.domain.market.candle.Candle>>(), any()),
        ).thenReturn(listOf(makeHammerMatch()))

        mockMvc
            .get("/api/patterns?code=005930&interval=1d&patterns=hammer")
            .andExpect {
                status { isOk() }
                jsonPath("$.matches.length()") { value(1) }
                jsonPath("$.matches[0].pattern") { value("hammer") }
                jsonPath("$.matches[0].direction") { value("BULL") }
                jsonPath("$.matches[0].confidence") { value("1") }
            }
    }

    // ── 검증 2: 미지원 패턴 → matches=[] graceful ────────────────────────────

    @Test
    fun `GET patterns 미지원 패턴 200 OK matches empty`() {
        stubOnboarding()
        stubCacheMiss()
        stubEmptyCandles()
        // 미지원 패턴은 PatternRecognizer에서 graceful empty 반환 (KIS L52 정합)
        whenever(
            patternRecognizer.recognize(any<List<kr.spdaewoon.magicjar.domain.market.candle.Candle>>(), any()),
        ).thenReturn(emptyList())

        mockMvc
            .get("/api/patterns?code=005930&interval=1d&patterns=unknown_pattern_xyz")
            .andExpect {
                status { isOk() }
                jsonPath("$.matches.length()") { value(0) }
            }
    }

    // ── 검증 3: R2 stub 패턴 (NEUTRAL) → matches=[] ──────────────────────────

    @Test
    fun `GET patterns R2 stub 패턴 200 OK matches empty`() {
        stubOnboarding()
        stubCacheMiss()
        stubEmptyCandles()
        // R2 stub은 NEUTRAL 반환 → JvmPatternRecognizer에서 미포함 → empty list
        whenever(
            patternRecognizer.recognize(any<List<kr.spdaewoon.magicjar.domain.market.candle.Candle>>(), any()),
        ).thenReturn(emptyList())

        mockMvc
            .get("/api/patterns?code=005930&interval=1d&patterns=tristar")
            .andExpect {
                status { isOk() }
                jsonPath("$.matches.length()") { value(0) }
            }
    }

    // ── 검증 4: 캐시 hit — PatternRecognizer 호출 0건 ─────────────────────────

    @Test
    fun `GET patterns 캐시 hit 200 OK PatternRecognizer 미호출`() {
        stubOnboarding()
        val cachedResponse =
            PatternResponseDto(
                matches =
                    listOf(
                        PatternResponseDto.PatternMatchDto(
                            pattern = "hammer",
                            startTime = "2024-01-02T00:00:00Z",
                            endTime = "2024-01-02T00:00:00Z",
                            direction = "BULL",
                            confidence = "1",
                        ),
                    ),
            )
        stubCacheHit(cachedResponse)

        mockMvc
            .get("/api/patterns?code=005930&interval=1d&patterns=hammer")
            .andExpect {
                status { isOk() }
                jsonPath("$.matches.length()") { value(1) }
                jsonPath("$.matches[0].pattern") { value("hammer") }
            }

        // PatternRecognizer는 캐시 hit 시 호출되지 않아야 함
        org.mockito.kotlin.verifyNoInteractions(patternRecognizer)
    }

    // ── 검증 5: 종목코드 형식 오류 → 400 ─────────────────────────────────────

    @Test
    fun `GET patterns 종목코드 형식 오류 400`() {
        stubOnboarding()

        mockMvc
            .get("/api/patterns?code=abc&interval=1d&patterns=hammer")
            .andExpect {
                status { isBadRequest() }
            }
    }
}
