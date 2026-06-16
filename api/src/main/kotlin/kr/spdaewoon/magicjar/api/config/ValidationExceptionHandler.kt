package kr.spdaewoon.magicjar.api.config

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

/**
 * Bean Validation · domain require 위반 → HTTP 400 graceful 매핑 (W4 P1 fix · 2026-05-24).
 *
 * ## 책임 (ADR 0102 §invariant 한국어 require + brainstorming spec §2-2-D 정합)
 * - `@Valid` request body 위반 (예: `TrendPreferenceUpdateRequest.maPeriods @Size 1..5`) → 400
 * - `List<@Min @Max Int>` element 위반 (`HandlerMethodValidationException`·`ConstraintViolationException`) → 400
 * - 도메인 invariant `require` 위반 (예: maPeriods 중복·정렬 어긋남·crossPair 위반) → 400
 *
 * ## 응답 body 구조 (frontend toast 즉시 사용 가능 의무)
 * ```json
 * { "errorCode": "VALIDATION_ERROR", "message": "...", "violations": [{"field": "...", "reason": "..."}] }
 * ```
 *
 * ## DRY · SRP (페르소나 진보 · 코드 리뷰 관점)
 * - 전략 DSL 예외는 [StrategyExceptionHandler] 단독 책임 — 본 핸들러는 일반 Bean Validation·require 정합만
 * - `@Order(HIGHEST_PRECEDENCE)` — 다른 핸들러보다 우선 (특정 도메인 핸들러가 catch 못한 일반 케이스 default)
 *
 * ## ResponseStatusException 회피 (기존 [kr.spdaewoon.magicjar.api.controller.BacktestController] 패턴 보존)
 * 기존 controller 가 `throw ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` 한 케이스는 Spring 기본 처리 그대로 — 본 핸들러는 신규 자동 매핑만.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ValidationExceptionHandler {
    private val log = LoggerFactory.getLogger(ValidationExceptionHandler::class.java)

    /**
     * `@Valid @RequestBody` body 위반 — Jackson 바인딩 후 Bean Validation 실패.
     *
     * 위반 field 별 한국어 메시지 + violations array — frontend 입력 폼 inline error 매핑 가능.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException): ResponseEntity<ValidationErrorResponse> {
        val violations =
            ex.bindingResult.fieldErrors.map { fe ->
                ValidationViolation(
                    field = fe.field,
                    reason = fe.defaultMessage ?: "유효하지 않은 값",
                    rejectedValue = fe.rejectedValue?.toString(),
                )
            }
        val message = violations.firstOrNull()?.let { "${it.field}: ${it.reason}" } ?: "입력값이 올바르지 않습니다"
        log.warn("Bean Validation 실패 (MethodArgumentNotValid): violations={}", violations)
        return ResponseEntity.badRequest().body(
            ValidationErrorResponse(
                errorCode = ERROR_CODE,
                message = message,
                violations = violations,
            ),
        )
    }

    /**
     * `List<@Min @Max Int>` 같은 element-level 검증 실패 (Spring 6.1+ method validation path).
     *
     * `TrendPreferenceUpdateRequest.maPeriods` 의 element `@Min 1 @Max 252` 위반이 본 경로로 들어옴.
     */
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleHandlerMethodValidation(ex: HandlerMethodValidationException): ResponseEntity<ValidationErrorResponse> {
        val violations =
            ex.allValidationResults.flatMap { result ->
                result.resolvableErrors.map { err ->
                    ValidationViolation(
                        field = result.methodParameter.parameterName ?: "param",
                        reason = err.defaultMessage ?: "유효하지 않은 값",
                        rejectedValue = null,
                    )
                }
            }
        val message = violations.firstOrNull()?.let { "${it.field}: ${it.reason}" } ?: "입력값이 올바르지 않습니다"
        log.warn("Bean Validation 실패 (HandlerMethodValidation): violations={}", violations)
        return ResponseEntity.badRequest().body(
            ValidationErrorResponse(
                errorCode = ERROR_CODE,
                message = message,
                violations = violations,
            ),
        )
    }

    /**
     * `@Validated` controller·service 의 `ConstraintViolationException` (예: `@PathVariable @Min`).
     *
     * MVP 단계에선 사용 빈도 낮지만 add-only 핸들러 — 향후 method-level constraint 등장 시 자동 흡수.
     */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ValidationErrorResponse> {
        val violations =
            ex.constraintViolations.map { v ->
                ValidationViolation(
                    field = v.propertyPath.toString(),
                    reason = v.message ?: "유효하지 않은 값",
                    rejectedValue = v.invalidValue?.toString(),
                )
            }
        val message = violations.firstOrNull()?.let { "${it.field}: ${it.reason}" } ?: "입력값이 올바르지 않습니다"
        log.warn("Bean Validation 실패 (ConstraintViolation): violations={}", violations)
        return ResponseEntity.badRequest().body(
            ValidationErrorResponse(
                errorCode = ERROR_CODE,
                message = message,
                violations = violations,
            ),
        )
    }

    /**
     * 도메인 invariant `require` 위반 — `IllegalArgumentException`.
     *
     * 예: [kr.spdaewoon.magicjar.domain.trend.TrendPreference] init {} require 6건 (중복·정렬·crossPair 등).
     * Bean Validation 으로 잡히지 않는 cross-field invariant 가 본 경로로 들어옴.
     *
     * controller 단에서 명시적 `throw ResponseStatusException(...)` 한 케이스는 Spring 이 별도 처리하므로 본 핸들러 미경유.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ValidationErrorResponse> {
        val message = ex.message ?: "잘못된 입력값"
        log.warn("도메인 invariant 위반: {}", message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ValidationErrorResponse(
                errorCode = ERROR_CODE,
                message = message,
                violations = emptyList(),
            ),
        )
    }

    private companion object {
        const val ERROR_CODE = "VALIDATION_ERROR"
    }
}

/**
 * 검증 실패 공통 응답 body (W4 P1 graceful — 2026-05-24).
 *
 * frontend toast 즉시 사용 가능 형태 — `message` 1줄 + `violations` 다중.
 */
data class ValidationErrorResponse(
    val errorCode: String,
    val message: String,
    val violations: List<ValidationViolation>,
)

data class ValidationViolation(
    val field: String,
    val reason: String,
    val rejectedValue: String?,
)
