package kr.spdaewoon.magicjar.domain.signal

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import java.math.BigDecimal

/**
 * 시그널 도메인 (ADR 0066 §5-2 채택 + ADR 0023 §2-1 baseline 확장).
 *
 * BoundaryClosingKafkaListener가 수신한 `market.candle.{interval}.closed.v1` payload를
 * RealtimeSignalGenerator가 평가하여 발행하는 BUY/SELL/HOLD 시그널.
 *
 * ### 멱등성 (ADR 0066 §2-5 E-1)
 * `(strategyId, code, interval, ts)` UQ — Phase 2 backend-core가 DB constraint 정의.
 * Phase 1 SignalDedupeGuard가 Redis SETNX 30s 보조.
 *
 * ### Pure Kotlin
 * Spring/JPA 의존 0. kotlinx.datetime.Instant 사용.
 *
 * @param id DB auto-generated (Phase 2 영속)
 * @param strategyId 시그널 발행 전략 ID
 * @param userId 정합 cache (signal table denormalized — 페이징 조회 효율)
 * @param code 종목 코드 (KIS 6자리)
 * @param interval 평가 인터벌 (1m·3m·5m·15m·30m·90m·1h·4h·12h 등)
 * @param ts boundary closing ts (deterministic — ADR 0064 §4-2 정합)
 * @param side BUY · SELL · HOLD ([SignalSide])
 * @param strength 0~100 — 룰 매칭 강도 (ADR 0023 §2-1 baseline)
 * @param reason 룰 매칭 사유 (예: "MA(5) > MA(20) at 09:05")
 * @param priceAtSignal 평가 시점 종가 (boundary close raw)
 * @param contextJson 평가 context JSON (indicator values · pattern matched · …)
 * @param createdAt 영속 시각
 *
 * spec 인용: ADR 0066 §5-2.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
data class Signal(
    val id: Long? = null,
    val strategyId: Long,
    val userId: String,
    val code: String,
    val interval: CandleInterval,
    val ts: Instant,
    val side: SignalSide,
    val strength: Int,
    val reason: String,
    val priceAtSignal: BigDecimal,
    val contextJson: String? = null,
    val createdAt: Instant? = null,
)
