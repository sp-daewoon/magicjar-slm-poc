package kr.spdaewoon.magicjar.domain.risk

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 사용자별 risk 설정 도메인 (ADR 0067 §5-1 + H-1 채택).
 *
 * ### 사용자 결정 정합 (ADR 0067 §3 채택)
 * - **A-1**: `maxPositionAmount`(절대) + `maxPositionPercent`(자본 비율) 둘 다 + min() 적용
 * - **B-1**: `dailyLossLimitAmount` OR `dailyLossLimitPercent` xor 택1 (또는 둘 다 NULL=비활성) + KST 자정 reset
 * - **C-1**: `maxConcurrentPositions` default 5 (1~50)
 * - **D-1**: `maxTradesPerMinute`(default 3) + `maxTradesPerDay`(default 50) strategy당
 * - **F-1**: `autoPauseThreshold` default 10 (연속 BLOCKED N회 시 strategy 신규 BUY 차단)
 * - **H-1**: 사용자별 (`userId` PK) + 부재 시 `defaultFor` seed
 *
 * spec 인용: ADR 0067 §5-1.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
data class RiskConfig(
    val userId: String,
    val maxPositionAmount: BigDecimal,
    val maxPositionPercent: BigDecimal,
    val dailyLossLimitAmount: BigDecimal? = null,
    val dailyLossLimitPercent: BigDecimal? = null,
    val maxConcurrentPositions: Int,
    val maxTradesPerMinute: Int,
    val maxTradesPerDay: Int,
    val autoPauseThreshold: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(maxPositionAmount > BigDecimal.ZERO) { "maxPositionAmount must be positive" }
        require(maxPositionPercent in BigDecimal("0.01")..BigDecimal("100.00")) {
            "maxPositionPercent must be 0.01~100.00"
        }
        require(maxConcurrentPositions in 1..50) { "maxConcurrentPositions must be 1~50" }
        require(maxTradesPerMinute in 1..60) { "maxTradesPerMinute must be 1~60" }
        require(maxTradesPerDay in 1..1000) { "maxTradesPerDay must be 1~1000" }
        require(autoPauseThreshold in 1..100) { "autoPauseThreshold must be 1~100" }
        dailyLossLimitAmount?.let { require(it > BigDecimal.ZERO) { "dailyLossLimitAmount must be positive" } }
        dailyLossLimitPercent?.let {
            require(it in BigDecimal("0.01")..BigDecimal("100.00")) {
                "dailyLossLimitPercent must be 0.01~100.00"
            }
        }
        // B-1 정합 — amount XOR percent (둘 다 null OK = DLL 비활성)
        require(!(dailyLossLimitAmount != null && dailyLossLimitPercent != null)) {
            "dailyLossLimit: amount or percent (xor) — both non-null is invalid"
        }
    }

    /** 일일 손실 한도 활성 여부 — 둘 다 NULL이면 DLL 비활성. */
    val isDailyLossLimitActive: Boolean
        get() = dailyLossLimitAmount != null || dailyLossLimitPercent != null

    companion object {
        /**
         * H-1 전역 default seed — risk_config 부재 시 신규 사용자에게 자동 적용 (ADR 0067 §5-1).
         */
        fun defaultFor(
            userId: String,
            now: Instant,
        ): RiskConfig =
            RiskConfig(
                userId = userId,
                maxPositionAmount = BigDecimal("1000000"),
                maxPositionPercent = BigDecimal("10.00"),
                dailyLossLimitAmount = null,
                dailyLossLimitPercent = BigDecimal("3.00"),
                maxConcurrentPositions = 5,
                maxTradesPerMinute = 3,
                maxTradesPerDay = 50,
                autoPauseThreshold = 10,
                createdAt = now,
                updatedAt = now,
            )
    }
}
