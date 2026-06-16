package kr.spdaewoon.magicjar.api.controller.order.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * 수동 주문 요청 DTO (ADR 0082 T-MAN B-2-extended LOCK · spec §3-1 본문).
 *
 * Bean Validation 4 layer (FIX B-2 — service parseSide/parsePriceType DTO 이전):
 *   1. userId — KIS credential identity (NotBlank)
 *   2. code   — 종목코드 6자리 LOCK (@Pattern)
 *   3. quantity — 양수 LOCK (@Min(1))
 *   4. side·priceType — enum 매핑 (DTO @Pattern 1차 검증 — Service runCatching boilerplate 제거)
 *
 * limitPrice — LIMIT 시 not null 검증은 service 측에서 수행 (priceType 동반 검증).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082)
 */
@Schema(description = "사용자 수동 주문 요청 DTO (POST /api/orders/manual)")
data class ManualOrderRequestDto(
    @field:Schema(
        description = "사용자 ID (KIS credential identity — onboarding 시 발급된 userId)",
        example = "74683223",
        required = true,
    )
    @field:NotBlank(message = "userId는 필수입니다.")
    val userId: String,
    @field:Schema(
        description = "종목코드 6자리 (KRX KOSPI/KOSDAQ 표준)",
        example = "005930",
        required = true,
    )
    @field:NotBlank(message = "code는 필수입니다.")
    @field:Pattern(regexp = "^\\d{6}$", message = "code는 6자리 숫자여야 합니다.")
    val code: String,
    @field:Schema(
        description = "매수/매도 — BUY(매수) / SELL(매도)",
        example = "BUY",
        allowableValues = ["BUY", "SELL"],
        required = true,
    )
    @field:NotBlank(message = "side는 필수입니다.")
    @field:Pattern(regexp = "^(BUY|SELL)$", message = "side는 BUY 또는 SELL이어야 합니다.")
    val side: String,
    @field:Schema(
        description = "가격 유형 — MARKET(시장가) / LIMIT(지정가)",
        example = "MARKET",
        allowableValues = ["MARKET", "LIMIT"],
        required = true,
    )
    @field:NotBlank(message = "priceType는 필수입니다.")
    @field:Pattern(regexp = "^(MARKET|LIMIT)$", message = "priceType는 MARKET 또는 LIMIT이어야 합니다.")
    val priceType: String,
    @field:Schema(
        description = "주문 수량 — 양수 LOCK",
        example = "1",
        required = true,
    )
    @field:Min(value = 1, message = "quantity는 1 이상이어야 합니다.")
    val quantity: Int,
    @field:Schema(
        description =
            "지정가 (priceType=LIMIT 시 not null, MARKET 시 null). " +
                "BigDecimal 정밀도 보존을 위해 String 전송 권장.",
        example = "70500",
        required = false,
        nullable = true,
    )
    val limitPrice: String? = null,
)
