package kr.spdaewoon.magicjar.api.ai.dsl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * DslTranslateController 슬라이스 테스트.
 *
 * 시나리오:
 * 1. POST + AI enabled + 정상 변환 → 200 + dsl 추출
 * 2. POST + AI enabled + untranslatable → 200 + dsl=null + reasoning
 * 3. POST + AI disabled → 503 + AI_DISABLED
 * 4. POST + 빈 입력 → 400 (Bean Validation)
 *
 * OnboardingGuardFilter mock 의무 (IndicatorControllerTest 패턴 정합).
 */
@WebMvcTest(controllers = [DslTranslateController::class])
@TestPropertySource(properties = ["magicjar.ai.enabled=true"])
class DslTranslateControllerTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockBean lateinit var translator: NaturalLanguageDslTranslator

    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    private fun stubOnboarding() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
    }

    @Test
    fun `POST translate + 정상 변환 → 200 OK`() {
        stubOnboarding()
        whenever(translator.translate(eq("RSI 14가 30 미만"))).thenReturn(
            DslTranslateResult.success(
                dsl = "rsi(14) < 30",
                mode = DslTranslateResult.TranslateMode.MOCK,
                reasoning = "Mock 룰 기반 변환 결과입니다.",
            ),
        )
        val body = objectMapper.writeValueAsString(DslTranslateRequest("RSI 14가 30 미만"))

        mockMvc
            .post("/api/dsl/translate-from-natural") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.dsl") { value("rsi(14) < 30") }
                jsonPath("$.untranslatable") { value(false) }
                jsonPath("$.mode") { value("MOCK") }
            }
    }

    @Test
    fun `POST translate + untranslatable → 200 OK + dsl null`() {
        stubOnboarding()
        whenever(translator.translate(eq("회장님이 좋아하는 종목"))).thenReturn(
            DslTranslateResult.untranslatable(
                mode = DslTranslateResult.TranslateMode.REAL,
                reasoning = "LLM이 변환 불가로 판단했습니다.",
            ),
        )
        val body = objectMapper.writeValueAsString(DslTranslateRequest("회장님이 좋아하는 종목"))

        mockMvc
            .post("/api/dsl/translate-from-natural") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.untranslatable") { value(true) }
                jsonPath("$.dsl") { isEmpty() }
                jsonPath("$.mode") { value("REAL") }
            }
    }

    @Test
    fun `POST translate + 빈 입력 → 400`() {
        stubOnboarding()
        val body = """{"naturalLanguage":""}"""
        mockMvc
            .post("/api/dsl/translate-from-natural") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
        verifyNoInteractions(translator)
    }
}

/**
 * AI disabled 시나리오 — 별도 클래스로 `magicjar.ai.enabled=false` 분리.
 */
@WebMvcTest(controllers = [DslTranslateController::class])
@TestPropertySource(properties = ["magicjar.ai.enabled=false"])
class DslTranslateControllerDisabledTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockBean lateinit var translator: NaturalLanguageDslTranslator

    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    @Test
    fun `POST translate + AI disabled → 503 + AI_DISABLED`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        val body = objectMapper.writeValueAsString(DslTranslateRequest("RSI 30 미만"))
        mockMvc
            .post("/api/dsl/translate-from-natural") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.error") { value("AI_DISABLED") }
            }
        verify(translator, org.mockito.kotlin.never()).translate(org.mockito.kotlin.any())
    }
}
