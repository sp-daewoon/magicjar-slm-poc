package kr.spdaewoon.magicjar.api.controller.market

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kr.spdaewoon.magicjar.api.controller.ErrorResponse
import kr.spdaewoon.magicjar.api.persistence.MarketCalendarEventJpaEntity
import kr.spdaewoon.magicjar.api.persistence.MarketCalendarEventJpaRepository
import kr.spdaewoon.magicjar.api.persistence.MarketCalendarJpaRepository
import kr.spdaewoon.magicjar.api.service.trend.TrendUserResolver
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 웹 시장 캘린더 REST 표면.
 *
 * 휴장/영업일 기준은 market_calendar 를 사용하고, IPO·실적·배당·거시 이벤트는 market_calendar_event 로 분리한다.
 */
@Tag(
    name = "시장 캘린더 API",
    description = "KRX 영업일/휴장일 + 사용자 등록 시장 일정 조회·관리",
)
@RestController
@RequestMapping("/api/market/calendar")
class MarketCalendarController(
    private val marketCalendarJpaRepository: MarketCalendarJpaRepository,
    private val marketCalendarEventJpaRepository: MarketCalendarEventJpaRepository,
    private val trendUserResolver: TrendUserResolver,
) {
    @Operation(
        summary = "시장 캘린더 조회",
        description =
            "from~to 범위의 날짜별 KRX 영업/휴장 상태와 사용자 등록 시장 이벤트를 반환합니다. " +
                "market_calendar row 가 없는 날짜는 주말 기준으로 fallback 합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(
            responseCode = "400",
            description = "날짜 범위 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완료",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping
    fun calendar(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate?,
    ): MarketCalendarResponse {
        val userId = trendUserResolver.currentUserId()
        val (rangeFrom, rangeTo) = normalizeRange(from, to)
        val calendarRows =
            marketCalendarJpaRepository
                .findByDateBetween(rangeFrom, rangeTo)
                .associateBy { it.date }
        val eventsByDate =
            marketCalendarEventJpaRepository
                .findByUserIdAndEventDateBetweenOrderByEventDateAscCreatedAtAsc(
                    userId = userId,
                    from = rangeFrom,
                    to = rangeTo,
                ).groupBy { it.eventDate }

        val days =
            generateSequence(rangeFrom) { it.plusDays(1) }
                .takeWhile { !it.isAfter(rangeTo) }
                .map { date ->
                    val row = calendarRows[date]
                    MarketCalendarDayResponse(
                        date = date,
                        isBusinessDay = row?.isBusinessDay ?: date.isWeekday(),
                        isHoliday = row?.isHoliday ?: false,
                        holidayName = row?.holidayName,
                        source = row?.source,
                        events = eventsByDate[date]?.map { it.toResponse() }.orEmpty(),
                    )
                }.toList()

        return MarketCalendarResponse(
            from = rangeFrom,
            to = rangeTo,
            days = days,
        )
    }

    @Operation(
        summary = "시장 일정 등록",
        description = "IPO·실적·배당·거시 이벤트 등 사용자 시장 일정을 등록합니다. eventType 은 임의 문자열로 확장 가능합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "등록 성공"),
        ApiResponse(
            responseCode = "400",
            description = "입력값 위반",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완료",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    fun createEvent(
        @Valid @RequestBody request: MarketCalendarEventRequest,
    ): MarketCalendarEventResponse {
        val userId = trendUserResolver.currentUserId()
        val entity =
            MarketCalendarEventJpaEntity(
                userId = userId,
                eventDate = request.date,
                market = normalizeCode(request.market, "KRX", 16),
                eventType = normalizeCode(request.eventType, "CUSTOM", 32),
                title = request.title.trim(),
                description = request.description?.trimToNull(),
                instrumentCode = request.instrumentCode?.trimToNull()?.uppercase(Locale.ROOT),
                source = "MANUAL",
            )
        return marketCalendarEventJpaRepository.save(entity).toResponse()
    }

    @Operation(
        summary = "시장 일정 수정",
        description = "사용자 등록 시장 일정의 날짜·유형·제목·종목·메모를 수정합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "입력값 위반",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "일정 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/events/{id}")
    fun updateEvent(
        @PathVariable id: Long,
        @Valid @RequestBody request: MarketCalendarEventRequest,
    ): MarketCalendarEventResponse {
        val userId = trendUserResolver.currentUserId()
        val entity = findUserEvent(id = id, userId = userId)
        entity.eventDate = request.date
        entity.market = normalizeCode(request.market, "KRX", 16)
        entity.eventType = normalizeCode(request.eventType, "CUSTOM", 32)
        entity.title = request.title.trim()
        entity.description = request.description?.trimToNull()
        entity.instrumentCode = request.instrumentCode?.trimToNull()?.uppercase(Locale.ROOT)
        entity.updatedAt = Instant.now()
        return marketCalendarEventJpaRepository.save(entity).toResponse()
    }

    @Operation(
        summary = "시장 일정 삭제",
        description = "사용자 등록 시장 일정을 삭제합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(
            responseCode = "404",
            description = "일정 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @DeleteMapping("/events/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteEvent(
        @PathVariable id: Long,
    ) {
        val userId = trendUserResolver.currentUserId()
        val entity = findUserEvent(id = id, userId = userId)
        marketCalendarEventJpaRepository.delete(entity)
    }

    private fun normalizeRange(
        from: LocalDate?,
        to: LocalDate?,
    ): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(SEOUL_ZONE)
        val rangeFrom = from ?: LocalDate.of(today.year, today.month, 1)
        val rangeTo = to ?: rangeFrom.plusMonths(1).minusDays(1)
        if (rangeFrom.isAfter(rangeTo)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from 은 to 보다 이후일 수 없습니다")
        }
        if (ChronoUnit.DAYS.between(rangeFrom, rangeTo) > 400) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "캘린더 조회 범위는 최대 400일입니다")
        }
        return rangeFrom to rangeTo
    }

    private fun findUserEvent(
        id: Long,
        userId: String,
    ): MarketCalendarEventJpaEntity =
        marketCalendarEventJpaRepository.findByIdAndUserId(id, userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "시장 일정을 찾을 수 없습니다")
        }
}

@Schema(description = "시장 캘린더 응답")
data class MarketCalendarResponse(
    @Schema(description = "조회 시작일", example = "2026-05-01")
    val from: LocalDate,
    @Schema(description = "조회 종료일", example = "2026-05-31")
    val to: LocalDate,
    @Schema(description = "날짜별 캘린더 정보")
    val days: List<MarketCalendarDayResponse>,
)

@Schema(description = "시장 캘린더 날짜 row")
data class MarketCalendarDayResponse(
    @Schema(description = "날짜", example = "2026-05-25")
    val date: LocalDate,
    @Schema(description = "KRX 영업일 여부", example = "false")
    val isBusinessDay: Boolean,
    @Schema(description = "휴일 여부", example = "true")
    val isHoliday: Boolean,
    @Schema(description = "휴일명", example = "부처님오신날")
    val holidayName: String?,
    @Schema(description = "휴장일 데이터 출처", example = "STATIC_SEED")
    val source: String?,
    @Schema(description = "해당 날짜 사용자 등록 시장 일정")
    val events: List<MarketCalendarEventResponse>,
)

@Schema(description = "시장 일정 등록/수정 요청")
data class MarketCalendarEventRequest(
    @field:Schema(description = "일정 날짜", example = "2026-05-25")
    val date: LocalDate,
    @field:Size(max = 16, message = "market 은 최대 16자입니다")
    @field:Schema(description = "시장 구분", example = "KRX")
    val market: String = "KRX",
    @field:NotBlank(message = "eventType 은 필수입니다")
    @field:Size(max = 32, message = "eventType 은 최대 32자입니다")
    @field:Schema(description = "이벤트 유형", example = "IPO")
    val eventType: String,
    @field:NotBlank(message = "title 은 필수입니다")
    @field:Size(max = 120, message = "title 은 최대 120자입니다")
    @field:Schema(description = "일정 제목", example = "신규 상장")
    val title: String,
    @field:Size(max = 16, message = "instrumentCode 는 최대 16자입니다")
    @field:Schema(description = "관련 종목 코드", example = "005930")
    val instrumentCode: String? = null,
    @field:Size(max = 2000, message = "description 은 최대 2000자입니다")
    @field:Schema(description = "메모", example = "청약/상장 일정 확인")
    val description: String? = null,
)

@Schema(description = "시장 일정 응답")
data class MarketCalendarEventResponse(
    @Schema(description = "일정 ID", example = "1")
    val id: Long,
    @Schema(description = "일정 날짜", example = "2026-05-25")
    val date: LocalDate,
    @Schema(description = "시장 구분", example = "KRX")
    val market: String,
    @Schema(description = "이벤트 유형", example = "IPO")
    val eventType: String,
    @Schema(description = "일정 제목", example = "신규 상장")
    val title: String,
    @Schema(description = "관련 종목 코드", example = "005930")
    val instrumentCode: String?,
    @Schema(description = "메모", example = "청약/상장 일정 확인")
    val description: String?,
    @Schema(description = "출처", example = "MANUAL")
    val source: String,
    @Schema(description = "생성 시각")
    val createdAt: Instant,
    @Schema(description = "수정 시각")
    val updatedAt: Instant,
)

private fun MarketCalendarEventJpaEntity.toResponse(): MarketCalendarEventResponse =
    MarketCalendarEventResponse(
        id = id ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "시장 일정 ID 생성 실패"),
        date = eventDate,
        market = market,
        eventType = eventType,
        title = title,
        instrumentCode = instrumentCode,
        description = description,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun LocalDate.isWeekday(): Boolean = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY

private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

private fun normalizeCode(
    raw: String?,
    fallback: String,
    maxLength: Int,
): String =
    raw
        ?.trimToNull()
        ?.uppercase(Locale.ROOT)
        ?.take(maxLength)
        ?: fallback

private val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
