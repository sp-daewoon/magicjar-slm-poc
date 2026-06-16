package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason

/**
 * 평가기 → StrategyService 비동기 콜백 인터페이스.
 *
 * Source: spec §6-3 (evaluator 책임 분리).
 *
 * - JvmStrategyEvaluator는 timeout / CPU budget 초과 시 본 콜백 호출
 * - api 모듈 StrategyService가 @Primary 구현체로 전략 auto-disable DB update 수행
 * - 앱별 구현체 미합류 시 [NoopAutoDisableCallback]가 default bean (graceful no-op)
 *
 * spec §6-3 LOCK.
 */
fun interface EvaluatorAutoDisableCallback {
    fun onAutoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    )
}
