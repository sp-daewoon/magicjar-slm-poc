package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.trendline.TrendlineService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@Tag(name = "Trendlines", description = "종목 차트 추세선 저장·자동 생성")
@RestController
@RequestMapping("/api/symbols/{code}/trendlines")
class TrendlineController(
    private val trendlineService: TrendlineService,
) {
    @Operation(summary = "추세선 목록 조회")
    @GetMapping
    fun list(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): List<TrendlineResponse> = trendlineService.list(code)

    @Operation(summary = "수동 추세선 저장")
    @PostMapping
    fun createManual(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @RequestBody request: TrendlineCreateRequest,
    ): TrendlineResponse = trendlineService.createManual(code, request)

    @Operation(summary = "최근 캔들 피벗 기준 자동 추세선 생성")
    @PostMapping("/auto")
    fun createAuto(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @RequestParam(defaultValue = "1d") interval: String,
        @RequestParam(defaultValue = "240") lookback: Int,
    ): TrendlineAutoCreateResponse =
        TrendlineAutoCreateResponse(
            trendlines = trendlineService.createAuto(code, interval, lookback),
        )

    @Operation(summary = "추세선 삭제")
    @DeleteMapping("/{id}")
    fun delete(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @PathVariable id: Long,
    ) {
        trendlineService.delete(code, id)
    }

    @Operation(summary = "추세선 좌표·스타일 수정")
    @PutMapping("/{id}")
    fun update(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @PathVariable id: Long,
        @RequestBody request: TrendlineUpdateRequest,
    ): TrendlineResponse = trendlineService.update(code, id, request)
}

data class TrendlineCreateRequest(
    val sourceInterval: String,
    val type: String,
    val anchor1Ts: Instant,
    val anchor1Price: BigDecimal,
    val anchor2Ts: Instant,
    val anchor2Price: BigDecimal,
    val extendRight: Boolean = true,
    val extendLeft: Boolean = false,
    val label: String? = null,
    val color: String? = null,
)

data class TrendlineUpdateRequest(
    val type: String,
    val anchor1Ts: Instant,
    val anchor1Price: BigDecimal,
    val anchor2Ts: Instant,
    val anchor2Price: BigDecimal,
    val extendRight: Boolean = true,
    val extendLeft: Boolean = false,
    val label: String? = null,
    val color: String? = null,
)

data class TrendlineAutoCreateResponse(
    val trendlines: List<TrendlineResponse>,
)

data class TrendlineResponse(
    val id: Long,
    val code: String,
    val sourceInterval: String,
    val type: String,
    val source: String,
    val anchor1Ts: Instant,
    val anchor1Price: String,
    val anchor2Ts: Instant,
    val anchor2Price: String,
    val extendRight: Boolean,
    val extendLeft: Boolean,
    val label: String?,
    val color: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
