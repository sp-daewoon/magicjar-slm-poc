package kr.spdaewoon.magicjar.domain.position.port

/**
 * strategy_position replay TOCTOU 직렬화 port (WS_PREINSERT race 보강).
 *
 * ## 왜 필요한가 (TOCTOU)
 * [kr.spdaewoon.magicjar.consumer.order.StrategyPositionExecutionReplayService.replayIfMissing] 는
 * `findByUserStrategyCode`(존재확인) → `applyExecution`(수량 가산) 의 read-modify-write 구간을 가진다.
 * ACK 병합 경로([kr.spdaewoon.magicjar.consumer.order.OrderRouterKafkaListener])와 REST backfill 경로
 * ([kr.spdaewoon.magicjar.consumer.order.KisExecutionBackfillService] 3 지점)가 같은 주문에 동시 진입하면
 * 둘 다 `existing == null` 을 통과해 양쪽이 `applyExecution` 을 호출 → 같은 execution 이 이중 가산(over-count)될 수 있다.
 * `applyExecution` 은 execution 단위 멱등이 아니라 quantity 누적이므로, UQ(user,strategy,code) 제약만으로는
 * read-modify-write 경합을 막지 못한다(행 1개 안에서 두 increment 가 누적됨).
 *
 * ## 보장
 * `replayIfMissing` 전체를 단일 트랜잭션으로 묶고, 그 시작에서 (user, strategyId, code) 키에 대해 본 lock 을 선취한다.
 * Postgres `pg_advisory_xact_lock` 기반 — 같은 키의 동시 replay 를 직렬화하고 트랜잭션 종료(commit/rollback) 시 자동 해제한다.
 * 따라서 한쪽이 row 를 만든 뒤에야 다른 쪽이 존재확인을 수행 → 두 번째는 `existing != null` 로 skip(멱등).
 * over-count(유령 물량 → 실손실) 를 0 으로 닫는다. under-count(다른 주문이 이미 보유 → skip)는 의도된 동작이며
 * KIS 권위·2차 ledger·drift WARN 으로 회복한다(수정 대상 아님).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 직렬화 port (ADR 0023 §2-4 정합)
 */
interface StrategyPositionReplayLockPort {
    /**
     * (user, strategyId, code) 키에 대해 현재 트랜잭션 범위의 advisory lock 을 선취한다.
     * 이미 같은 키를 다른 트랜잭션이 보유 중이면 해제될 때까지 블로킹한다(짧은 read-modify-write 구간).
     * 트랜잭션이 없거나 lock 획득에 실패하면 [LockResult.SKIPPED] 를 반환해 호출측이 안전하게 우회하도록 한다.
     */
    fun lockForReplay(
        userId: String,
        strategyId: Long,
        code: String,
    ): LockResult

    enum class LockResult {
        /** advisory lock 선취 완료 — read-modify-write 직렬화 보장. */
        ACQUIRED,

        /** lock 획득 불가(트랜잭션 부재·DB 오류 등) — 호출측은 replay 를 skip 한다(이중계상 방지 우선). */
        SKIPPED,
    }
}
