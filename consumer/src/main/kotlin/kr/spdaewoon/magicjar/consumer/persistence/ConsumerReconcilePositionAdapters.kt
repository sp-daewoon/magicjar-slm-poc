package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.reconcile.InternalHolding
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileHolding
import kr.spdaewoon.magicjar.domain.reconcile.port.InternalPositionQueryPort
import kr.spdaewoon.magicjar.domain.reconcile.port.PositionUpsertPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Consumer-side position adapters used by BalanceReconcileService.
 */
@Component
class ConsumerInternalPositionQueryAdapter(
    private val positionJpaRepository: PositionJpaRepository,
) : InternalPositionQueryPort {
    private val log = LoggerFactory.getLogger(ConsumerInternalPositionQueryAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findCurrentByUser(userId: String): List<InternalHolding> {
        val holdings =
            positionJpaRepository
                .findByUserId(userId)
                .filter { it.quantity > 0 }
                .map {
                    InternalHolding(
                        symbol = it.code,
                        quantity = it.quantity,
                        avgPrice = it.avgPrice,
                    )
                }
        log.debug("consumer reconcile internal positions: userId={} holdings={}", maskUserId(userId), holdings.size)
        return holdings
    }
}

@Component
class ConsumerPositionUpsertAdapter(
    private val positionJpaRepository: PositionJpaRepository,
) : PositionUpsertPort {
    private val log = LoggerFactory.getLogger(ConsumerPositionUpsertAdapter::class.java)

    @Transactional
    override fun upsertFromKis(
        userId: String,
        holding: ReconcileHolding,
    ) {
        positionJpaRepository.upsertReconciled(
            userId = userId,
            code = holding.symbol,
            quantity = holding.quantity,
            avgPrice = holding.avgPrice,
            source = holding.accountType.name,
        )
        log.info(
            "consumer reconcile position upsert: userId={} code={} qty={} source={}",
            maskUserId(userId),
            holding.symbol,
            holding.quantity,
            holding.accountType.name,
        )
    }
}

private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
