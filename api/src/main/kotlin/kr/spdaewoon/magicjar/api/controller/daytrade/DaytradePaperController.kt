package kr.spdaewoon.magicjar.api.controller.daytrade

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.spdaewoon.magicjar.api.service.daytrade.DaytradePaperService
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

/**
 * 발굴 → MOCK 페이퍼 트레이딩 REST 컨트롤러 (ADR 0122 R2 · spec §3-R2 · 사용자 메모리 feedback_swagger_pattern).
 *
 * Swagger 4단 LOCK: @Tag(컨트롤러) · @Operation(메서드) · @Parameter(파라미터) · DTO @Schema(필드).
 *
 * ### 4 endpoint
 * - POST   /api/daytrade/paper/promote     — 발굴 setup → MOCK 페이퍼 전략 일괄 승격(사용자 opt-in)
 * - DELETE /api/daytrade/paper/{strategyId} — 페이퍼 전략 중단(soft delete)
 * - GET    /api/daytrade/paper/strategies   — 활성 페이퍼 전략 목록
 * - GET    /api/daytrade/paper/positions     — 페이퍼 포지션·실현/평가손익
 *
 * ### LOCK
 * - 자동 진입 0 — promote 는 사용자 명시 액션에서만 호출(ADR 0122 §1-2). 발굴 응답/스코어카드 cron 진입 경로 없음.
 * - 실 KIS 주문 경로 변경 0 — 페이퍼는 TradingModeGate MOCK 강제(ADR 0122 §1-1 C). 본 컨트롤러는 전략 생성/조회만.
 * - watchlist write 0 — 감시 종목은 STRATEGY owner WS 수요로만(DaytradePaperService).
 *
 * // AI 호출 0 — 페이퍼는 룰 기반 시그널 경로 100% (ADR 0122 §4-1)
 */
@Tag(
    name = "DaytradePaper",
    description = "발굴 → MOCK 페이퍼(모의) 트레이딩 — 발굴 후보를 실거래 전 실시간 모의 검증 (ADR 0122 · 실거래 아님)",
)
@RestController
@RequestMapping("/api/daytrade/paper")
class DaytradePaperController(
    private val daytradePaperService: DaytradePaperService,
) {
    /**
     * 발굴 setup → MOCK 페이퍼 전략 일괄 승격 (사용자 명시 opt-in · 다중선택 일괄).
     */
    @Operation(
        summary = "페이퍼 승격 (발굴 setup → MOCK 전략)",
        description = """
            발굴 빌트인 setup(presetKey)의 entry_dsl 을 사용자가 선택한 종목(codes)에 묶어
            origin=PAPER_DISCOVERY·status=ACTIVE 페이퍼 전략 1개로 만듭니다 (다중선택 일괄 승격).
            생성된 전략은 기존 signal→risk→order 경로를 그대로 타되 주문은 항상 MOCK(가상 자본 기준)입니다.
            가상 자본은 ENV default(1천만원) 또는 virtualCapitalKrw override. 실거래 결정권 0 — 실거래 아님(모의).
            ★ 자동 진입 금지: 이 API 호출(사용자 명시 액션)로만 페이퍼가 시작됩니다(발굴 결과 표시·cron 자동 승격 없음).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "페이퍼 전략 생성 성공 — strategyId + 가상 자본 반환"),
            ApiResponse(responseCode = "400", description = "요청 검증 실패 (userId·presetKey·codes 누락, DSL 파싱 실패)"),
            ApiResponse(responseCode = "404", description = "발굴 setup(presetKey) 없음"),
            ApiResponse(responseCode = "409", description = "페이퍼 전략 상한 도달 (MAGICJAR_DAYTRADE_PAPER_MAX_INSTANCES)"),
            ApiResponse(responseCode = "503", description = "페이퍼 트레이딩 비활성 (MAGICJAR_DAYTRADE_PAPER_ENABLED=false)"),
        ],
    )
    @PostMapping("/promote")
    fun promote(
        @Valid @RequestBody request: PaperPromoteRequest,
    ): ResponseEntity<PaperStrategyDto> = ResponseEntity.ok(daytradePaperService.promote(request))

    /**
     * 페이퍼 전략 중단 (soft delete · status=DELETED). 페이퍼 전략만 대상.
     */
    @Operation(
        summary = "페이퍼 전략 중단",
        description =
            "페이퍼 전략을 중단(soft delete · status=DELETED)합니다. 페이퍼 전략(origin=PAPER_DISCOVERY)만 대상이며 " +
                "실 전략에는 적용되지 않습니다. 중단 시 STRATEGY owner WS 수요도 해제합니다(보유는 별도 청산).",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "중단 성공"),
            ApiResponse(responseCode = "400", description = "페이퍼 전략이 아님 (실 전략 오삭제 가드)"),
            ApiResponse(responseCode = "404", description = "페이퍼 전략 없음"),
        ],
    )
    @DeleteMapping("/{strategyId}")
    fun stop(
        @Parameter(description = "페이퍼 전략 ID", example = "42", required = true)
        @PathVariable strategyId: Long,
        @Parameter(description = "소유자 검증용 사용자 ID(옵션)", example = "1")
        @RequestParam(required = false) userId: Long?,
    ): ResponseEntity<Void> {
        daytradePaperService.stop(strategyId, userId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    /**
     * 활성 페이퍼 전략 목록.
     */
    @Operation(
        summary = "활성 페이퍼 전략 목록",
        description = "사용자의 활성 페이퍼(origin=PAPER_DISCOVERY) 전략 목록을 반환합니다(DELETED 제외).",
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "페이퍼 전략 목록(없으면 빈 배열)")])
    @GetMapping("/strategies")
    fun strategies(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: Long,
    ): List<PaperStrategyDto> = daytradePaperService.listStrategies(userId)

    /**
     * 페이퍼 포지션·손익 조회.
     */
    @Operation(
        summary = "페이퍼 포지션 조회",
        description =
            "페이퍼 전략이 가상 자본으로 보유한 포지션을 조회합니다(전략별 귀속 strategy_position 재사용 — ADR 0110). " +
                "수량·평단·실현손익(가상 ₩)을 반환합니다. 보유가 없으면 빈 배열입니다(mock 합성 0건). 실거래 아님(모의).",
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "페이퍼 포지션 목록(없으면 빈 배열)")])
    @GetMapping("/positions")
    fun positions(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: Long,
    ): List<PaperPositionDto> = daytradePaperService.listPositions(userId)
}
