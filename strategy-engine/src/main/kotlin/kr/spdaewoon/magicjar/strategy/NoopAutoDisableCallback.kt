package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [EvaluatorAutoDisableCallback] default no-op 구현.
 *
 * 앱별 `EvaluatorAutoDisableCallback` 구현이 없을 때 graceful 동작 보장.
 * api 모듈에서는 `@Primary StrategyService : EvaluatorAutoDisableCallback`이 우선한다.
 *
 * spec §6-3 LOCK.
 */
@Component
class NoopAutoDisableCallback : EvaluatorAutoDisableCallback {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAutoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    ) {
        log.warn(
            "NoopAutoDisableCallback.onAutoDisable(strategyId={}, reason={}) — app callback 미합류, no-op.",
            strategyId,
            reason,
        )
    }
}
