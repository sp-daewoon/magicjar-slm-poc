package kr.spdaewoon.magicjar.domain.strategy.port

import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason

/**
 * 전략 영속 포트 — api 모듈 [StrategyJpaAdapter] 구현.
 *
 * 헥사고날 아키텍처 — domain이 port 정의, api가 adapter 구현.
 * ADR 0051 함정 회피: 구현 클래스에서만 helper 메서드 허용.
 */
interface StrategyRepositoryPort {
    fun save(strategy: Strategy): Strategy

    fun findById(id: Long): Strategy?

    fun findAllByUserId(userId: Long): List<Strategy>

    fun deleteById(id: Long)

    fun autoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    )

    fun enable(strategyId: Long)
}
