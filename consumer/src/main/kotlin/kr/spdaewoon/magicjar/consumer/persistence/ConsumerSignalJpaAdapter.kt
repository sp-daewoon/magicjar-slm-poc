package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.signal.port.SignalRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * Consumer-side SignalRepository implementation.
 *
 * 저장된 signal id가 Kafka payload에 포함되어야 risk listener가 signal.generated.v1을 정상 소비한다.
 */
@Component
class ConsumerSignalJpaAdapter(
    private val signalJpaRepository: ConsumerSignalJpaRepository,
) : SignalRepository {
    private val log = LoggerFactory.getLogger(ConsumerSignalJpaAdapter::class.java)

    @Transactional
    override fun save(signal: Signal): Signal =
        try {
            val saved = signalJpaRepository.save(toEntity(signal))
            toDomain(saved)
        } catch (e: DataIntegrityViolationException) {
            log.debug(
                "consumer duplicate signal skipped: strategyId={} code={} interval={} ts={}",
                signal.strategyId,
                signal.code,
                signal.interval.code,
                signal.ts,
            )
            findExisting(signal)?.let { toDomain(it) } ?: signal
        }

    @Transactional(readOnly = true)
    override fun findByDedupeKey(
        strategyId: Long,
        code: String,
        interval: CandleInterval,
        ts: kotlinx.datetime.Instant,
    ): Signal? =
        signalJpaRepository
            .findByStrategyIdAndCodeAndIntervalAndTs(strategyId, code, interval.code, ts.toJavaInstant())
            ?.let { toDomain(it) }

    private fun findExisting(signal: Signal): ConsumerSignalJpaEntity? =
        signalJpaRepository.findByStrategyIdAndCodeAndIntervalAndTs(
            signal.strategyId,
            signal.code,
            signal.interval.code,
            signal.ts.toJavaInstant(),
        )

    private fun toEntity(signal: Signal): ConsumerSignalJpaEntity =
        ConsumerSignalJpaEntity(
            id = signal.id,
            strategyId = signal.strategyId,
            userId = signal.userId,
            code = signal.code,
            interval = signal.interval.code,
            ts = signal.ts.toJavaInstant(),
            side = signal.side.name,
            strength = signal.strength,
            reason = signal.reason.takeIf { it.isNotBlank() },
            price = signal.priceAtSignal,
            contextJson = signal.contextJson,
        )

    private fun toDomain(entity: ConsumerSignalJpaEntity): Signal =
        Signal(
            id = entity.id,
            strategyId = entity.strategyId,
            userId = entity.userId,
            code = entity.code,
            interval = safeInterval(entity.interval),
            ts = entity.ts.toKotlinInstant(),
            side = safeSide(entity.side),
            strength = entity.strength,
            reason = entity.reason ?: "",
            priceAtSignal = entity.price ?: BigDecimal.ZERO,
            contextJson = entity.contextJson,
            createdAt = entity.createdAt.toKotlinInstant(),
        )

    private fun safeInterval(code: String): CandleInterval =
        runCatching {
            CandleInterval.fromCode(code)
        }.getOrDefault(CandleInterval.ONE_DAY)

    private fun safeSide(value: String): SignalSide =
        runCatching {
            SignalSide.valueOf(value)
        }.getOrDefault(SignalSide.HOLD)
}
