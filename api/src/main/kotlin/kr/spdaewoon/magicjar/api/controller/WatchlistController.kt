package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.spdaewoon.magicjar.api.service.WatchlistService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

/**
 * 관심종목 CRUD REST 컨트롤러 (spec §4-1 LOCK).
 * - ADR 0040 §2-4 신규 컴포넌트 정합 — magicJar 1순위 진입점
 * - ADR 0028 Watchlist 기반 Ingestion Worker (인터페이스 — Worker는 T-W2)
 * - OnboardingGuardFilter 통과 의무 (셋업 미완 시 401 ONBOARDING_REQUIRED)
 * - user_id: user_account 첫 row 자동 resolve (1차 single-tenant 단순화)
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Tag(name = "Watchlist", description = "관심종목 CRUD — magicJar 1순위 진입점 (ADR 0040)")
@RestController
@RequestMapping("/api/watchlists")
class WatchlistController(
    private val watchlistService: WatchlistService,
) {
    @Operation(
        summary = "관심종목 목록 조회",
        description = "등록된 관심종목 목록을 priority 오름차순 + created_at 내림차순으로 반환합니다. 빈 목록이면 [] 반환.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완 — 온보딩 마법사 완료 후 사용 가능",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping
    fun list(): List<WatchlistEntryDto> {
        val userId = watchlistService.currentUserId()
        return watchlistService.list(userId)
    }

    @Operation(
        summary = "관심종목 등록",
        description =
            "종목 코드 6자리(KOSPI·KOSDAQ·ETF·ETN — 예: 005930 삼성전자)를 등록합니다. " +
                "UQ(user_id, code) 위반 시 409. instrument 테이블에 stub row 자동 INSERT (T-IM1 진입 시 정식 데이터 갱신). " +
                "Kafka watchlist.changed.v1 topic에 ADDED 이벤트 발행 (T-W2 listener 후속).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "등록 성공"),
        ApiResponse(
            responseCode = "400",
            description = "입력값 오류 — 6자리 숫자 형식 위반",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완 — OnboardingGuardFilter 차단",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 등록된 종목 — WATCHLIST_DUPLICATE",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping
    fun add(
        @Parameter(description = "관심종목 등록 요청")
        @Valid
        @RequestBody request: WatchlistAddRequest,
        uriBuilder: UriComponentsBuilder,
    ): ResponseEntity<WatchlistEntryDto> {
        val userId = watchlistService.currentUserId()
        val dto = watchlistService.add(userId, request)
        val location =
            uriBuilder
                .path("/api/watchlists/{code}")
                .buildAndExpand(dto.code)
                .toUri()
        return ResponseEntity.created(location).body(dto)
    }

    @Operation(
        summary = "관심종목 삭제",
        description =
            "종목 코드로 관심종목을 삭제합니다. " +
                "204 No Content 반환. 미등록 종목이면 404. " +
                "Kafka watchlist.changed.v1 topic에 REMOVED 이벤트 발행 (T-W2 unsubscribe 신호).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(
            responseCode = "400",
            description = "입력값 오류 — 6자리 숫자 형식 위반",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완 — OnboardingGuardFilter 차단",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "등록된 종목 없음 — WATCHLIST_NOT_FOUND",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @DeleteMapping("/{code}")
    fun remove(
        @Parameter(description = "삭제할 KIS 종목 코드 6자리", example = "005930")
        @PathVariable code: String,
    ): ResponseEntity<Void> {
        // path variable 형식 검증 (6자리 숫자)
        if (!code.matches(Regex("^\\d{6}$"))) {
            throw org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "종목 코드는 6자리 숫자여야 합니다 (예: 005930)",
            )
        }
        val userId = watchlistService.currentUserId()
        watchlistService.remove(userId, code)
        return ResponseEntity.noContent().build()
    }
}
