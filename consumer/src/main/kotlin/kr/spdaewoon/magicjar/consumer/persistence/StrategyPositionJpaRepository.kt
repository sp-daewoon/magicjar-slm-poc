package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * strategy_position Spring Data JPA Repository (consumer 모듈 — ADR 0110, 분할매수 R2-2).
 *
 * ### 헌법 §6 정합
 * abstract fun + 명시 `@Query` 만 — body 가진 default fun 0건 (Spring Data query inference 함정 회피).
 * 평단/실현손익/exit stage 갱신 수식은 어댑터([ConsumerExecutionStrategyPositionUpdateAdapter])의 helper 책임.
 */
interface StrategyPositionJpaRepository : JpaRepository<StrategyPositionJpaEntity, Long> {
    fun findByUserIdAndStrategyIdAndCode(
        userId: String,
        strategyId: Long,
        code: String,
    ): StrategyPositionJpaEntity?

    fun findByUserIdAndCode(
        userId: String,
        code: String,
    ): List<StrategyPositionJpaEntity>

    /**
     * 주기 reconcile union 측 — 특정 user 의 strategy_position 보유(quantity>0) 종목 distinct code.
     *
     * ADR 0115 §P1-1 amend (2026-06-10 over-count incident). [StrategyPositionReconcileWorker] 가
     * KIS 권위(position quantity>0) 종목과 **합집합**으로 순회 범위를 넓힐 때 사용한다. KIS 가 0주가 된 유령
     * over-count 종목(strategy_position 만 잔존)도 reconcileStrategyPositionDrift 에 도달시켜 [PositionDriftCorrection]
     * Adjust(초과 내림)가 작동하게 한다.
     *
     * strategy_position 에 mode 컬럼이 없으므로 user 기준 code 만 뽑고, 권위 비교(REAL/MOCK 분기)는 worker 가
     * position 측 source 키로 수행한다. @Query 명시 — 헌법 §6 default-fun 추론 함정 회피.
     */
    @Query(
        "SELECT DISTINCT sp.code FROM StrategyPositionJpaEntity sp " +
            "WHERE sp.userId = :userId AND sp.quantity > 0",
    )
    fun findDistinctCodesByUserIdAndQuantityPositive(
        @Param("userId") userId: String,
    ): List<String>

    /** drift 검증용 — user+code 전략별 보유 수량 합(spec §3-1 불변식: == position.quantity). */
    @Query(
        "SELECT COALESCE(SUM(sp.quantity), 0) FROM StrategyPositionJpaEntity sp " +
            "WHERE sp.userId = :userId AND sp.code = :code",
    )
    fun sumQuantityByUserAndCode(
        @Param("userId") userId: String,
        @Param("code") code: String,
    ): Int

    /**
     * 보유 중(quantity>0) 전 strategy_position — ADR 0114 R2-C2b 인메모리 보유 인덱스 60초 refresh·HOLDING owner 등록.
     * @Query 명시 — 헌법 §6 default-fun 함정 회피(method-name 추론 대신 명시 JPQL).
     */
    @Query("SELECT sp FROM StrategyPositionJpaEntity sp WHERE sp.quantity > 0")
    fun findAllHolding(): List<StrategyPositionJpaEntity>

    /**
     * 특정 종목 보유(quantity>0) lot — ADR 0114 R2-C2b 인덱스 miss 시 즉시 재확인·drift 가드용.
     * tick hot path는 인메모리 인덱스를 1차로 쓰고 본 쿼리는 등록/refresh·검증 경로에서만 호출(매 tick DB 조회 금지).
     */
    @Query("SELECT sp FROM StrategyPositionJpaEntity sp WHERE sp.code = :code AND sp.quantity > 0")
    fun findHoldingByCode(
        @Param("code") code: String,
    ): List<StrategyPositionJpaEntity>

    /**
     * ADR 0122 §2-C — 페이퍼(가상 자본) 실현손익 누적 합.
     *
     * 해당 user 의 발굴 페이퍼 전략(`strategy.origin = 'PAPER_DISCOVERY'`)에 귀속된 strategy_position 행의
     * realized_pnl 합. [kr.spdaewoon.magicjar.consumer.risk.PaperCapitalSnapshotProvider] 가
     * `가상 자본 잔액 = 초기 default + 페이퍼 실현손익 합(부호 그대로)` 으로 산출하는 입력.
     *
     * 실 전략(USER_DSL) row 는 join 조건에서 제외 → 실 잔고·실 리스크 판정 오염 0(spec §1-3 격리 LOCK).
     * native query(strategy join · origin enum 컬럼은 V81 add). 행 부재 시 COALESCE 0.
     * @Query 명시 — 헌법 §6 default-fun 추론 함정 회피.
     */
    @Query(
        value =
            "SELECT COALESCE(SUM(sp.realized_pnl), 0) FROM strategy_position sp " +
                "JOIN strategy s ON s.id = sp.strategy_id " +
                "WHERE sp.user_id = :userId AND s.origin = 'PAPER_DISCOVERY'",
        nativeQuery = true,
    )
    fun sumPaperRealizedPnlByUser(
        @Param("userId") userId: String,
    ): java.math.BigDecimal

    /**
     * replay TOCTOU 직렬화용 advisory xact lock — (user, strategyId, code) 2개 정수 키.
     *
     * `pg_advisory_xact_lock(key1, key2)` 는 현재 트랜잭션 범위 lock 으로, commit/rollback 시 자동 해제(leak 0).
     * 같은 키의 동시 replay 트랜잭션을 직렬화해 [StrategyPositionExecutionReplayService] 의
     * 존재확인→가산 read-modify-write 이중 진입(over-count)을 차단한다.
     *
     * - key1 = (user_id, strategy_id) 결합 해시 — `('x' || md5(...))::bit(32)::int`.
     * - key2 = code 해시 — 동.
     * 32bit int 충돌은 advisory lock 의 한 키에 한해 (다른 종목이 같은 lock 을 공유) 약간의 직렬화만 유발할 뿐
     * 정합성을 깨지 않는다(보수적 over-lock).
     *
     * @Query nativeQuery — JPQL 로 표현 불가한 Postgres 함수. 반드시 활성 트랜잭션 안에서만 호출(xact-scope).
     */
    @Query(
        value =
            "SELECT pg_advisory_xact_lock(" +
                "('x' || md5(:userId || ':' || cast(:strategyId as text)))::bit(32)::int, " +
                "('x' || md5(:code))::bit(32)::int)",
        nativeQuery = true,
    )
    fun acquireReplayAdvisoryLock(
        @Param("userId") userId: String,
        @Param("strategyId") strategyId: Long,
        @Param("code") code: String,
    )
}
