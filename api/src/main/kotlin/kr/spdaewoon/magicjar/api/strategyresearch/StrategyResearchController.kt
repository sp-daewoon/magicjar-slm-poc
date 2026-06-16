package kr.spdaewoon.magicjar.api.strategyresearch

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Strategy Research", description = "전략 후보 리서치 QA API")
@RestController
@RequestMapping("/api/strategy-research")
class StrategyResearchController(
    private val strategyResearchService: StrategyResearchService,
    private val strategyResearchAiDiscoveryService: StrategyResearchAiDiscoveryService,
    private val strategyResearchCandidateService: StrategyResearchCandidateService,
) {
    private val log = LoggerFactory.getLogger(StrategyResearchController::class.java)

    @Operation(
        summary = "리서치 후보 전략 목록",
        description =
            "DB(V74 strategy_research_candidate)에 등록된 후보 전략 목록을 sort_order 순으로 조회합니다. " +
                "리서치 실행은 이 목록을 평가 후보로 사용합니다. includeInactive=true면 비활성 후보도 포함합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "후보 전략 목록 조회 성공"),
    )
    @GetMapping("/candidates")
    fun candidates(
        @RequestParam(required = false, defaultValue = "false") includeInactive: Boolean,
    ): List<StrategyResearchCandidateDefinitionDto> = strategyResearchCandidateService.list(includeInactive)

    @Operation(
        summary = "내 전략을 리서치 후보로 추가",
        description =
            "사용자의 저장된 전략(dsl_entry·손절/익절)을 리서치 후보로 승격합니다(is_builtin=false). " +
                "진입 조건은 전략 dsl_entry를 그대로 후보 entry_dsl로 사용하며, 파싱·화이트리스트 검증을 통과해야 합니다. " +
                "전략 저장·KIS 주문·live order Kafka topic을 호출하지 않습니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "후보 추가 성공"),
        ApiResponse(responseCode = "400", description = "userId/전략 진입 조건 검증 실패"),
        ApiResponse(responseCode = "404", description = "전략 미존재 또는 소유자 불일치"),
        ApiResponse(responseCode = "409", description = "동일 후보 키 중복"),
    )
    @PostMapping("/candidates/from-strategy")
    fun addCandidateFromStrategy(
        @RequestBody @Valid request: StrategyResearchCandidateFromStrategyRequestDto,
    ): StrategyResearchCandidateDefinitionDto {
        log.info(
            "POST /api/strategy-research/candidates/from-strategy: userId={} strategyId={}",
            request.userId,
            request.strategyId,
        )
        return strategyResearchCandidateService.addFromStrategy(request)
    }

    @Operation(
        summary = "리서치 후보 전략 삭제",
        description =
            "후보 전략을 삭제합니다(204). builtin 후보도 삭제할 수 있으며, V74 seed는 ON CONFLICT DO NOTHING " +
                "이라 재배포해도 부활하지 않습니다(영구 삭제). 존재하지 않는 id는 404입니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "후보 삭제 성공"),
        ApiResponse(responseCode = "404", description = "후보 미존재"),
    )
    @DeleteMapping("/candidates/{id}")
    fun deleteCandidate(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        strategyResearchCandidateService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "전략 후보 생성",
        description =
            "저장된 candle만 읽어 하락장 반등·과매도 회복·거래량 투매 반등 후보를 평가합니다. " +
                "전략 저장, KIS 주문, live order Kafka topic을 호출하지 않습니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전략 후보 생성 성공"),
        ApiResponse(responseCode = "400", description = "요청 파라미터 또는 candle 검증 실패"),
    )
    @PostMapping("/proposals")
    fun propose(
        @RequestBody @Valid request: StrategyResearchRequestDto,
    ): StrategyResearchReportDto {
        log.info(
            "POST /api/strategy-research/proposals: userId={} code={} interval={} from={} to={}",
            request.userId,
            request.code,
            request.interval,
            request.from,
            request.to,
        )
        return strategyResearchService.propose(request)
    }

    @Operation(
        summary = "AI 종목발굴",
        description =
            "사용자 자연어를 Claude CLI 기반 후보 목록으로 변환한 뒤, 종목 마스터와 매핑합니다. " +
                "KIS 주문, 전략 저장, live order Kafka topic을 호출하지 않습니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "AI 종목발굴 성공"),
        ApiResponse(responseCode = "400", description = "요청 파라미터 검증 실패"),
        ApiResponse(responseCode = "503", description = "Claude CLI 또는 브릿지 사용 불가"),
    )
    @PostMapping("/ai-discovery")
    fun discoverWithAi(
        @RequestBody @Valid request: StrategyResearchAiDiscoveryRequestDto,
    ): StrategyResearchAiDiscoveryResponseDto {
        log.info(
            "POST /api/strategy-research/ai-discovery: userId={} market={} limit={} presetId={} queryLength={}",
            request.userId,
            request.market,
            request.limit,
            request.presetId,
            request.query?.length ?: 0,
        )
        return strategyResearchAiDiscoveryService.discover(request)
    }

    @Operation(
        summary = "AI 종목발굴 프리셋 목록",
        description = "AI 종목발굴 UI에 노출할 intent preset과 prompt 본문을 조회합니다.",
    )
    @GetMapping("/ai-discovery/presets")
    fun aiDiscoveryPresets(): List<StrategyResearchAiDiscoveryPresetDto> = strategyResearchAiDiscoveryService.presets()

    @Operation(
        summary = "AI 프리셋 추천",
        description =
            "현재 core wrapper·등록된 프리셋 전체·magicJar 실제 시스템 능력(지표/캔들 interval/백테스트 흐름)을 " +
                "메타 프롬프트로 묶어 Claude에게 신규 발굴 프리셋 후보를 받아옵니다. 추천만 반환하며 저장하지 않습니다. " +
                "사용자가 검토 후 프리셋 추가(POST /ai-discovery/presets)로 직접 저장합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "AI 프리셋 추천 성공"),
        ApiResponse(responseCode = "400", description = "요청 파라미터 검증 실패(count 범위 등)"),
        ApiResponse(responseCode = "503", description = "Claude CLI/브릿지 사용 불가 또는 추천 파싱 실패"),
    )
    @PostMapping("/ai-discovery/presets/recommend")
    fun recommendAiDiscoveryPresets(
        @RequestBody @Valid request: StrategyResearchAiDiscoveryPresetRecommendRequestDto,
    ): StrategyResearchAiDiscoveryPresetRecommendResponseDto {
        log.info(
            "POST /api/strategy-research/ai-discovery/presets/recommend: userId={} count={} hintLength={}",
            request.userId,
            request.count,
            request.hint?.length ?: 0,
        )
        return strategyResearchAiDiscoveryService.recommendPresets(request)
    }

    @Operation(
        summary = "AI 종목발굴 프리셋 생성",
        description = "사용자 정의 intent preset을 추가합니다. 정렬값을 비우면 기존 최대 정렬값 + 10으로 생성합니다.",
    )
    @PostMapping("/ai-discovery/presets")
    fun createAiDiscoveryPreset(
        @RequestBody @Valid request: StrategyResearchAiDiscoveryPresetCreateRequestDto,
    ): StrategyResearchAiDiscoveryPresetDto = strategyResearchAiDiscoveryService.createPreset(request)

    @Operation(
        summary = "AI 종목발굴 프리셋 수정",
        description = "프리셋 노출명, 설명, intent prompt, 활성 여부를 수정합니다. Core wrapper는 서비스 코드 권위로 유지됩니다.",
    )
    @PutMapping("/ai-discovery/presets/{presetId}")
    fun updateAiDiscoveryPreset(
        @PathVariable presetId: String,
        @RequestBody @Valid request: StrategyResearchAiDiscoveryPresetUpdateRequestDto,
    ): StrategyResearchAiDiscoveryPresetDto = strategyResearchAiDiscoveryService.updatePreset(presetId, request)

    @Operation(
        summary = "AI 종목발굴 프리셋 삭제",
        description =
            "프리셋을 삭제합니다(204). builtin 프리셋도 삭제할 수 있으며, V70 seed 는 ON CONFLICT DO NOTHING " +
                "이라 재배포해도 부활하지 않습니다(영구 삭제). 존재하지 않는 id 는 404 입니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "프리셋 삭제 성공"),
        ApiResponse(responseCode = "404", description = "프리셋 미존재"),
    )
    @DeleteMapping("/ai-discovery/presets/{presetId}")
    fun deleteAiDiscoveryPreset(
        @PathVariable presetId: String,
    ): ResponseEntity<Void> {
        strategyResearchAiDiscoveryService.deletePreset(presetId)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "AI 종목발굴 core(wrapper) 프롬프트 조회",
        description =
            "발굴 전역 system prompt(core wrapper)를 조회합니다. DB 행이 있으면 그 값이, 없으면 서비스 코드 기본값이 " +
                "반환되며 isDefault 로 사용자 편집 여부를 구분합니다.",
    )
    @GetMapping("/ai-discovery/core-prompt")
    fun aiDiscoveryCorePrompt(): StrategyResearchAiDiscoveryCorePromptDto = strategyResearchAiDiscoveryService.corePrompt()

    @Operation(
        summary = "AI 종목발굴 core(wrapper) 프롬프트 수정",
        description = "발굴 전역 system prompt 를 교체합니다. 공백만으로는 저장할 수 없습니다(400).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "core 프롬프트 갱신 성공"),
        ApiResponse(responseCode = "400", description = "promptText 공백"),
    )
    @PutMapping("/ai-discovery/core-prompt")
    fun updateAiDiscoveryCorePrompt(
        @RequestBody @Valid request: StrategyResearchAiDiscoveryCorePromptUpdateRequestDto,
    ): StrategyResearchAiDiscoveryCorePromptDto = strategyResearchAiDiscoveryService.updateCorePrompt(request)

    @Operation(
        summary = "AI 종목발굴 core(wrapper) 프롬프트 기본값 복원",
        description = "발굴 전역 system prompt 를 서비스 코드 기본값으로 복원합니다.",
    )
    @PostMapping("/ai-discovery/core-prompt/reset")
    fun resetAiDiscoveryCorePrompt(): StrategyResearchAiDiscoveryCorePromptDto = strategyResearchAiDiscoveryService.resetCorePrompt()

    @Operation(
        summary = "AI 종목발굴 프롬프트 미리보기",
        description = "Claude 호출 없이 현재 조건, 선택 프리셋, 유지/제외 후보를 합성한 최종 프롬프트를 반환합니다.",
    )
    @PostMapping("/ai-discovery/prompt-preview")
    fun previewAiDiscoveryPrompt(
        @RequestBody @Valid request: StrategyResearchAiDiscoveryPromptPreviewRequestDto,
    ): StrategyResearchAiDiscoveryPromptPreviewDto = strategyResearchAiDiscoveryService.previewPrompt(request)

    @Operation(
        summary = "최근 AI 종목발굴 상태 조회",
        description = "사용자별 마지막 AI 종목발굴 조건과 후보 상태를 조회합니다.",
    )
    @GetMapping("/ai-discovery/latest")
    fun latestAiDiscovery(
        @RequestParam userId: String,
        @RequestParam(required = false) interval: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): StrategyResearchAiDiscoveryLatestDto =
        StrategyResearchAiDiscoveryLatestDto(
            strategyResearchAiDiscoveryService.latest(
                userId = userId,
                interval = interval,
                from = from,
                to = to,
            ),
        )

    @Operation(
        summary = "AI 종목발굴 후보 상태 변경",
        description = "후보를 NEW·KEPT·DISMISSED 상태로 변경합니다. KEPT는 재발굴 시 유지되고 DISMISSED는 제외됩니다.",
    )
    @PatchMapping("/ai-discovery/item-status")
    fun updateAiDiscoveryItemStatus(
        @RequestBody @Valid request: StrategyResearchAiDiscoveryItemStatusRequestDto,
    ): StrategyResearchAiDiscoveryResponseDto = strategyResearchAiDiscoveryService.updateItemStatus(request)

    @Operation(
        summary = "AI 종목발굴 상태 초기화",
        description = "사용자별 마지막 AI 종목발굴 조건과 후보 상태를 삭제합니다.",
    )
    @DeleteMapping("/ai-discovery/latest")
    fun clearAiDiscovery(
        @RequestParam userId: String,
    ) {
        strategyResearchAiDiscoveryService.clear(userId)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(ex.statusCode).body(
            mapOf(
                "error" to ex.statusCode.toString(),
                "message" to (ex.reason ?: "전략 리서치 처리 중 오류가 발생했습니다."),
            ),
        )
}
