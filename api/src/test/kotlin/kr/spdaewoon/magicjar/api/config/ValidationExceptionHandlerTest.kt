package kr.spdaewoon.magicjar.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ValidationExceptionHandler 통합 단위 테스트 (W4 P1 fix · 2026-05-24).
 *
 * 실제 TrendPreferenceController dependency wiring 회피 — stub controller + handler 만 wire.
 * - MethodArgumentNotValidException · IllegalArgumentException 두 경로 모두 검증
 * - 응답 body 구조 (errorCode · message · violations) 1:1 정합
 *
 * ## 시나리오
 * 1. `@Size(1..5)` 위반 (maPeriods 6개) → 400 + violations[0].field=maPeriods
 * 2. `IllegalArgumentException` (domain require 시뮬레이션) → 400 + 한국어 message
 */
class ValidationExceptionHandlerTest {
    private val controller = StubController()
    private val handler = ValidationExceptionHandler()
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(handler)
            .build()
    private val mapper = ObjectMapper()

    @Test
    fun `@Size 위반 - 400 + violations 한국어 message`() {
        val body =
            mapper.writeValueAsString(
                mapOf("maPeriods" to listOf(5, 10, 20, 30, 60, 120)),
            )

        mockMvc
            .perform(
                post("/stub/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("maPeriods")))
            .andExpect(jsonPath("$.violations").isArray)
            .andExpect(jsonPath("$.violations[0].field").value("maPeriods"))
            .andExpect(
                jsonPath("$.violations[0].reason")
                    .value(org.hamcrest.Matchers.containsString("1~5개")),
            )
    }

    @Test
    fun `domain require 위반 (IllegalArgumentException) - 400 + 한국어 echo`() {
        val body =
            mapper.writeValueAsString(
                mapOf("maPeriods" to listOf(5, 20, 60)),
            )

        mockMvc
            .perform(
                post("/stub/domain-throw")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("MA periods는 중복 없이 오름차순"))
            .andExpect(jsonPath("$.violations").isArray)
    }

    @Test
    fun `정상 입력 - 200 path 회귀 0`() {
        val body =
            mapper.writeValueAsString(
                mapOf("maPeriods" to listOf(5, 20, 60)),
            )

        mockMvc
            .perform(
                post("/stub/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isOk)
    }

    // ─── stub controller — TrendPreferenceController 와 동일한 Bean Validation 패턴 ─────────────────

    @RestController
    @RequestMapping("/stub")
    private class StubController {
        @Operation(summary = "stub Bean Validation")
        @PostMapping("/validate")
        fun validate(
            @Valid @RequestBody req: StubRequest,
        ): Map<String, Any> = mapOf("ok" to true, "size" to req.maPeriods.size)

        @Operation(summary = "stub domain throw")
        @PostMapping("/domain-throw")
        fun domainThrow(
            @RequestBody req: StubRequest,
        ): Map<String, Any> {
            // TrendPreference init {} require 위반 시뮬레이션
            throw IllegalArgumentException("MA periods는 중복 없이 오름차순")
        }
    }

    private data class StubRequest(
        @field:Size(min = 1, max = 5, message = "MA periods 는 1~5개 사이여야 합니다")
        val maPeriods: List<
            @Min(value = 1, message = "각 MA 기간은 1 이상이어야 합니다")
            @Max(value = 252, message = "각 MA 기간은 252 이하여야 합니다")
            Int,
        >,
    )
}
