package kr.spdaewoon.magicjar.consumer.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Consumer-side StrategyPositionPort 어댑터 (ADR 0110, 분할매수 R2-2).
 *
 * [ConsumerRiskPositionAdapter] 패턴 1:1 — 자동매매 경로(consumer JVM)에 전략별 lot ledger를 wiring.
 * RealtimeSignalGenerator(scale-in/exit 평가) + 체결 listener(upsert via [ConsumerExecutionStrategyPositionAdapter]
 * 의 위임)가 이 어댑터를 통해 strategy_position 을 조회·영속한다.
 *
 * - JSONB `executed_exit_stages` ↔ Set<Int> 매핑은 [JdbcTypeCode] JSON String 을 ObjectMapper 로 양방향 변환.
 * - upsert 는 UQ(user_id, strategy_id, code) 기준 find-then-save(같은 transaction boundary — 체결 listener 가 보장).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 어댑터 (ADR 0023 §2-4 정합)
 */
@Component
class ConsumerStrategyPositionAdapter(
    private val strategyPositionJpaRepository: StrategyPositionJpaRepository,
    private val objectMapper: ObjectMapper,
) : StrategyPositionPort {
    private val log = LoggerFactory.getLogger(ConsumerStrategyPositionAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findByUserStrategyCode(
        userId: String,
        strategyId: Long,
        code: String,
    ): StrategyPosition? =
        strategyPositionJpaRepository
            .findByUserIdAndStrategyIdAndCode(userId, strategyId, code)
            ?.let { toDomain(it) }

    @Transactional(readOnly = true)
    override fun findByUserCode(
        userId: String,
        code: String,
    ): List<StrategyPosition> =
        strategyPositionJpaRepository
            .findByUserIdAndCode(userId, code)
            .map { toDomain(it) }

    @Transactional
    override fun upsert(strategyPosition: StrategyPosition): StrategyPosition {
        val existing =
            strategyPositionJpaRepository.findByUserIdAndStrategyIdAndCode(
                strategyPosition.userId,
                strategyPosition.strategyId,
                strategyPosition.code,
            )
        val entity = toEntity(strategyPosition, existing)
        return toDomain(strategyPositionJpaRepository.save(entity))
    }

    private fun toEntity(
        domain: StrategyPosition,
        existing: StrategyPositionJpaEntity?,
    ): StrategyPositionJpaEntity =
        StrategyPositionJpaEntity(
            id = existing?.id ?: domain.id,
            userId = domain.userId,
            strategyId = domain.strategyId,
            code = domain.code,
            quantity = domain.quantity,
            avgPrice = domain.avgPrice,
            filledLots = domain.filledLots,
            realizedPnl = domain.realizedPnl,
            executedExitStages = serializeStages(domain.executedExitStages),
            // 신규 행만 openedAt 채움 — 기존 행은 보존(재진입 사이클에서도 최초 진입 시각 유지).
            openedAt = existing?.openedAt ?: (domain.openedAt.toJavaInstant()),
            updatedAt = Instant.now(),
        )

    private fun toDomain(entity: StrategyPositionJpaEntity): StrategyPosition =
        StrategyPosition(
            id = entity.id,
            userId = entity.userId,
            strategyId = entity.strategyId,
            code = entity.code,
            quantity = entity.quantity,
            avgPrice = entity.avgPrice,
            filledLots = entity.filledLots,
            realizedPnl = entity.realizedPnl,
            executedExitStages = deserializeStages(entity.executedExitStages),
            openedAt = entity.openedAt.toKotlinInstant(),
            updatedAt = entity.updatedAt.toKotlinInstant(),
        )

    private fun serializeStages(stages: Set<Int>): String = objectMapper.writeValueAsString(stages.sorted())

    private fun deserializeStages(json: String?): Set<Int> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            objectMapper.readValue(json, Array<Int>::class.java).toSet()
        } catch (e: Exception) {
            log.warn("strategy_position executed_exit_stages 역직렬화 실패 — 빈 집합 fallback: json={} err={}", json, e.message)
            emptySet()
        }
    }
}
