package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.strategy.EvaluatorAutoDisableCallback
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Consumer-side strategy auto-disable callback.
 *
 * 실시간 시그널 평가는 consumer JVM에서 실행되므로 api 모듈의 StrategyService 콜백을 볼 수 없다.
 * sandbox timeout/CPU budget 위반은 여기서 strategy 테이블을 직접 DISABLED로 전환한다.
 */
@Primary
@Component
class ConsumerStrategyAutoDisableCallback(
    private val strategyJpaRepository: ConsumerStrategyJpaRepository,
) : EvaluatorAutoDisableCallback {
    private val log = LoggerFactory.getLogger(ConsumerStrategyAutoDisableCallback::class.java)

    @Transactional
    override fun onAutoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    ) {
        val updated =
            if (reason == StrategyDisabledReason.RISK_VIOLATION) {
                strategyJpaRepository.autoBuyBlock(strategyId, reason.name)
            } else {
                strategyJpaRepository.autoDisable(strategyId, reason.name)
            }
        if (updated == 0) {
            log.warn("consumer strategy auto-disable 대상 없음: strategyId={} reason={}", strategyId, reason)
            return
        }
        if (reason == StrategyDisabledReason.RISK_VIOLATION) {
            log.warn("consumer strategy 신규 BUY 차단 완료: strategyId={} reason={}", strategyId, reason)
        } else {
            log.warn("consumer strategy auto-disable 완료: strategyId={} reason={}", strategyId, reason)
        }
    }
}
