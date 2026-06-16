package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionReplayLockPort
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionReplayLockPort.LockResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * [StrategyPositionReplayLockPort] consumer 어댑터 — Postgres advisory xact lock 으로 replay TOCTOU 직렬화.
 *
 * ## 트랜잭션 전제 (필수)
 * `pg_advisory_xact_lock` 은 **현재 트랜잭션 종료 시 자동 해제**된다. 따라서 호출 시점에 활성 트랜잭션이 없으면
 * lock 은 한 statement 만큼만 잡혔다 즉시 풀려 직렬화 효과가 사라진다. 이 경우 [LockResult.SKIPPED] 를 반환해
 * 호출측([StrategyPositionExecutionReplayService])이 replay 자체를 우회하게 한다(이중계상 위험을 안고 진행하지 않음).
 * 정상 경로에서는 replay 서비스가 `@Transactional` 로 진입하므로 항상 활성 트랜잭션 안에서 호출된다.
 *
 * DB 오류(드라이버·연결 등)로 lock 획득에 실패해도 [LockResult.SKIPPED] — over-count 회피를 일관 우선한다.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 직렬화 어댑터 (ADR 0023 §2-4 정합)
 */
@Component
class ConsumerStrategyPositionReplayLockAdapter(
    private val strategyPositionJpaRepository: StrategyPositionJpaRepository,
) : StrategyPositionReplayLockPort {
    private val log = LoggerFactory.getLogger(ConsumerStrategyPositionReplayLockAdapter::class.java)

    override fun lockForReplay(
        userId: String,
        strategyId: Long,
        code: String,
    ): LockResult {
        // xact lock 은 트랜잭션 종료 시에만 해제 — 활성 트랜잭션이 없으면 직렬화 무효 → skip.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn(
                "strategy_position replay advisory lock skip — 활성 트랜잭션 없음(xact-scope 무효): strategyId={} code={}",
                strategyId,
                code,
            )
            return LockResult.SKIPPED
        }
        return try {
            strategyPositionJpaRepository.acquireReplayAdvisoryLock(userId, strategyId, code)
            LockResult.ACQUIRED
        } catch (e: Exception) {
            log.warn(
                "strategy_position replay advisory lock 획득 실패 — replay skip: strategyId={} code={} err={}",
                strategyId,
                code,
                e.message,
            )
            LockResult.SKIPPED
        }
    }
}
