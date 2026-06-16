package kr.spdaewoon.magicjar.api.strategybuilder

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Strategy Builder AI", description = "전략빌더 AI 전략 생성 API (Live-Context Meta-Prompt)")
@RestController
@RequestMapping("/api/strategies")
class StrategyBuilderAiGenerateController(
    private val strategyBuilderAiGenerateService: StrategyBuilderAiGenerateService,
) {
    private val log = LoggerFactory.getLogger(StrategyBuilderAiGenerateController::class.java)

    @Operation(
        summary = "AI 전략 생성",
        description =
            "사용자 전략 의도(prompt)를 magicJar DSL 문법·지표 카탈로그·기존 전략·RiskPlan 스키마와 함께 메타 프롬프트로 묶어 " +
                "Claude에게 빌더 draft 후보를 받아옵니다. 생성된 entryDsl/exitDsl은 실제 DSL 파서로 검증하여 dslValid/dslError를 채우고, " +
                "검증 실패 후보는 1회 자동 재생성으로 정정을 시도합니다. 전략을 저장하지 않으며(AI 결정권 0 · ADR 0112 정합), " +
                "빌더 draft 로드용 제안만 반환합니다. 저장은 기존 전략 저장 API가 담당합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 생성 성공(invalid 후보 포함 가능)"),
        ApiResponse(responseCode = "400", description = "prompt 빈 값 등 요청 파라미터 검증 실패"),
        ApiResponse(responseCode = "503", description = "Claude CLI/브릿지 사용 불가 또는 응답 파싱 실패"),
    )
    @PostMapping("/ai-generate")
    fun generate(
        @RequestBody @Valid request: StrategyBuilderAiGenerateRequestDto,
    ): StrategyBuilderAiGenerateResponseDto {
        log.info(
            "POST /api/strategies/ai-generate: userId={} promptLength={} interval={} count={}",
            request.userId,
            request.prompt.length,
            request.interval,
            request.count,
        )
        return strategyBuilderAiGenerateService.generate(request)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(ex.statusCode).body(
            mapOf(
                "error" to ex.statusCode.toString(),
                "message" to (ex.reason ?: "AI 전략 생성 중 오류가 발생했습니다."),
            ),
        )
}
