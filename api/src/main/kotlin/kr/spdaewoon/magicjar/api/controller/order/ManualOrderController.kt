package kr.spdaewoon.magicjar.api.controller.order

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.spdaewoon.magicjar.api.controller.order.dto.ManualOrderRequestDto
import kr.spdaewoon.magicjar.api.controller.order.dto.ManualOrderResponseDto
import kr.spdaewoon.magicjar.api.service.order.ManualOrderService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 사용자 수동 주문 REST 컨트롤러 (ADR 0082 T-MAN B-2-extended LOCK · spec §3-1 본문).
 *
 * 자동 path는 `OrderRouterKafkaListener`가 권위 — 본 endpoint는 사용자 수동 트리거 진입점.
 * source=MANUAL 마커가 분기 검출 핵심 (consumer step 2 deserialize → step 5 INSERT 영속).
 *
 * 3중 게이트 fast precheck — UX 가속 한정 (권위는 consumer OrderRouterKafkaListener step 3·4).
 * api precheck PASS 후 → 동일 Kafka topic `order.intent.v1` publish → consumer step 12 재검증.
 *
 * Swagger 4단 (사용자 메모리 feedback_swagger_pattern 정합):
 *   1. Controller: @Tag (한국어 + ADR 인용)
 *   2. Method: @Operation summary + description (한국어)
 *   3. Parameter: @RequestBody @Valid + @Schema (DTO 클래스)
 *   4. DTO: 각 필드 @Schema (description + example)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082)
 */
@Tag(
    name = "사용자 수동 주문 (T-MAN)",
    description =
        "사용자 수동 주문 발행 API (ADR 0082 T-MAN B-2-extended). " +
            "자동매매 시그널 path와 동일 Kafka topic order.intent.v1을 공유하되 " +
            "payload \"source\"=\"MANUAL\" 마커로 분기 영속.",
)
@RestController
@RequestMapping("/api/orders")
class ManualOrderController(
    private val manualOrderService: ManualOrderService,
) {
    private val log = LoggerFactory.getLogger(ManualOrderController::class.java)

    // ─── POST /api/orders/manual ──────────────────────────────────────────────

    @Operation(
        summary = "사용자 수동 주문 발행",
        description =
            "사용자가 UI에서 직접 발행하는 수동 주문. " +
                "Bean Validation + 3중 게이트 fast precheck(권위는 consumer 재검증) → " +
                "Kafka order.intent.v1 publish (source=MANUAL) → consumer OrderRouter 12 step 흡수. " +
                "응답은 202 Accepted — orderId는 consumer step 5 INSERT 시점 채번이라 null 반환.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "202",
            description = "주문 접수 성공 — Kafka publish OK. /orders 페이지에서 상태 추적.",
            content = [
                Content(
                    schema = Schema(implementation = ManualOrderResponseDto::class),
                    examples = [
                        ExampleObject(
                            name = "정상 응답",
                            value =
                                "{" +
                                    "\"orderId\":null," +
                                    "\"status\":\"ACCEPTED\"," +
                                    "\"idempotencyKey\":\"manual:74683223:1716073800\"," +
                                    "\"message\":\"수동 주문이 접수되었습니다. /orders 페이지에서 상태를 확인하세요.\"" +
                                    "}",
                        ),
                    ],
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Bean Validation 위반 — quantity ≤ 0 / limitPrice 형식 / code 6자리 위반 / side·priceType enum 위반",
        ),
        ApiResponse(
            responseCode = "403",
            description = "REAL_GATE_DENIED:ALLOW_REAL / REAL_GATE_DENIED:CONSENT / KILLSWITCH_ON 차단",
        ),
        ApiResponse(
            responseCode = "503",
            description = "Kafka publish 실패 — 재시도 권장 (consumer 미진입이므로 자본 영향 0)",
        ),
    )
    @PostMapping("/manual")
    fun submitManualOrder(
        @RequestBody @Valid request: ManualOrderRequestDto,
    ): ResponseEntity<ManualOrderResponseDto> {
        log.info(
            "POST /api/orders/manual 진입: userId={} code={} side={} priceType={} qty={} limitPrice={}",
            request.userId,
            request.code,
            request.side,
            request.priceType,
            request.quantity,
            request.limitPrice,
        )
        val result = manualOrderService.submit(request)
        val response =
            ManualOrderResponseDto(
                orderId = null,
                status = "ACCEPTED",
                idempotencyKey = result.idempotencyKey,
                message = "수동 주문이 접수되었습니다. /orders 페이지에서 상태를 확인하세요.",
            )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response)
    }
}
