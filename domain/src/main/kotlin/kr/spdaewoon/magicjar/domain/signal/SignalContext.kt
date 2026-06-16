package kr.spdaewoon.magicjar.domain.signal

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import java.math.BigDecimal

/**
 * 시그널 평가 context (ADR 0066 §5-4).
 *
 * RealtimeSignalGenerator가 boundary 마감 payload 수신 시 구성하여
 * JvmStrategyEvaluator(또는 후속 SignalGenerator 구현체)에 전달.
 *
 * @param code 종목 코드
 * @param interval 평가 인터벌
 * @param boundaryTs boundary closing ts (Kafka payload `ts` deterministic)
 * @param nowKst 현재 시각 (시간 필터 검증용 — KST 기준)
 * @param indicatorCache ADR 0021 IndicatorSource 결과 cache (key=식별자, value=BigDecimal)
 *
 * spec 인용: ADR 0066 §5-4.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
data class SignalContext(
    val code: String,
    val interval: CandleInterval,
    val boundaryTs: Instant,
    val nowKst: Instant,
    val indicatorCache: Map<String, BigDecimal> = emptyMap(),
)
