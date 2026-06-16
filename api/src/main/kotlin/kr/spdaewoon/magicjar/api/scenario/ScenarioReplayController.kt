package kr.spdaewoon.magicjar.api.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Scenario Replay", description = "T-SIM 시나리오 리플레이 QA API")
@RestController
@RequestMapping("/api/scenario-replay/runs")
class ScenarioReplayController(
    private val scenarioReplayService: ScenarioReplayService,
    private val queryService: ScenarioReplayQueryService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ScenarioReplayController::class.java)

    @Operation(
        summary = "시나리오 리플레이 실행",
        description =
            "과거 candle을 virtual clock으로 재생해 전략 신호·리스크·가상 주문·가상 체결·오류 이벤트를 저장합니다. " +
                "KIS 주문·live 주문 Kafka topic을 사용하지 않고 scenario_* 테이블에만 기록합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "리플레이 실행 및 저장 성공"),
        ApiResponse(responseCode = "400", description = "요청 파라미터 또는 전략/캔들 검증 실패"),
    )
    @PostMapping
    fun run(
        @RequestBody @Valid request: ScenarioReplayRunRequestDto,
    ): ResponseEntity<ScenarioRunDetailDto> {
        log.info(
            "POST /api/scenario-replay/runs: userId={} strategyId={} code={} interval={} from={} to={}",
            request.userId,
            request.strategyId,
            request.code,
            request.interval,
            request.from,
            request.to,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(scenarioReplayService.runAndPersist(request))
    }

    @Operation(summary = "시나리오 리플레이 목록 조회")
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", required = true)
        @RequestParam userId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ScenarioPageDto<ScenarioRunSummaryDto> {
        val runs = queryService.listRuns(userId, page, size)
        return ScenarioPageDto(
            content = runs.content.map(ScenarioReplayMapper::toSummary),
            page = runs.number,
            size = runs.size,
            totalElements = runs.totalElements,
            totalPages = runs.totalPages,
        )
    }

    @Operation(summary = "시나리오 리플레이 단건 조회")
    @GetMapping("/{runId}")
    fun get(
        @PathVariable runId: Long,
        @RequestParam userId: String,
    ): ScenarioRunDetailDto {
        val run = queryService.getRun(runId, userId)
        val events = queryService.listEvents(runId, userId, page = 0, size = 500)
        val positions = queryService.listPositions(runId, userId)
        return ScenarioRunDetailDto(
            run = ScenarioReplayMapper.toSummary(run),
            events = events.content.map { ScenarioReplayMapper.toEvent(it, objectMapper) },
            positions = positions.map(ScenarioReplayMapper::toPosition),
        )
    }

    @Operation(summary = "시나리오 리플레이 이벤트 조회")
    @GetMapping("/{runId}/events")
    fun events(
        @PathVariable runId: Long,
        @RequestParam userId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "100") size: Int,
    ): ScenarioPageDto<ScenarioEventDto> {
        val events = queryService.listEvents(runId, userId, page, size)
        return ScenarioPageDto(
            content = events.content.map { ScenarioReplayMapper.toEvent(it, objectMapper) },
            page = events.number,
            size = events.size,
            totalElements = events.totalElements,
            totalPages = events.totalPages,
        )
    }

    @Operation(summary = "시나리오 리플레이 삭제")
    @DeleteMapping("/{runId}")
    fun delete(
        @PathVariable runId: Long,
        @RequestParam userId: String,
    ): ResponseEntity<Void> {
        queryService.deleteRun(runId, userId)
        return ResponseEntity.noContent().build()
    }
}
