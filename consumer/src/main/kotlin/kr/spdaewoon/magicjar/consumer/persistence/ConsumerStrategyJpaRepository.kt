package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * strategy repository for consumer-side realtime signal evaluation.
 */
interface ConsumerStrategyJpaRepository : JpaRepository<ConsumerStrategyJpaEntity, Long> {
    @Query(
        value =
            "SELECT * FROM strategy " +
                "WHERE status IN ('ACTIVE', 'BUY_BLOCKED') " +
                "AND codes @> CAST(:codeJson AS jsonb) " +
                "AND intervals @> CAST(:intervalJson AS jsonb)",
        nativeQuery = true,
    )
    fun findActiveByCodeAndInterval(
        @Param("codeJson") codeJson: String,
        @Param("intervalJson") intervalJson: String,
    ): List<ConsumerStrategyJpaEntity>

    @Query(
        value =
            "SELECT * FROM strategy " +
                "WHERE status IN ('ACTIVE', 'BUY_BLOCKED') " +
                "AND codes IS NOT NULL",
        nativeQuery = true,
    )
    fun findActiveWithExplicitCodes(): List<ConsumerStrategyJpaEntity>

    /**
     * ADR 0114 §4-2 P2 — 청산(SELL) 평가용 position-driven 폴백: **DISABLED여도 strategy_position 보유분이 있는** 전략 조회.
     *
     * BUY 게이트([findActiveByCodeAndInterval] ACTIVE/BUY_BLOCKED만)와 분리 — "전략이 살아 있어야 청산"이 아니라
     * "포지션이 있으면 청산"으로 불변식 전환. DELETED는 제외(전략 정의 자체 삭제 — §9 U-6은 KillSwitch alert).
     * strategy_position(quantity>0) 조인으로 보유 없는 DISABLED는 평가 대상 0(과도 평가 회피).
     *
     * 헌법 §6 — 명시 @Query(native). default-fun 함정 회피.
     */
    @Query(
        value =
            "SELECT DISTINCT s.* FROM strategy s " +
                "JOIN strategy_position sp ON sp.strategy_id = s.id AND sp.quantity > 0 " +
                "WHERE s.status = 'DISABLED' " +
                "AND s.codes @> CAST(:codeJson AS jsonb) " +
                "AND s.intervals @> CAST(:intervalJson AS jsonb)",
        nativeQuery = true,
    )
    fun findDisabledWithPositionByCodeAndInterval(
        @Param("codeJson") codeJson: String,
        @Param("intervalJson") intervalJson: String,
    ): List<ConsumerStrategyJpaEntity>

    @Modifying
    @Query(
        value =
            "UPDATE strategy " +
                "SET status = 'DISABLED', " +
                "disabled_reason = :reason, " +
                "last_disabled_at = CURRENT_TIMESTAMP, " +
                "disabled_at = CURRENT_TIMESTAMP, " +
                "updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = :id AND status <> 'DELETED'",
        nativeQuery = true,
    )
    fun autoDisable(
        @Param("id") id: Long,
        @Param("reason") reason: String,
    ): Int

    @Modifying
    @Query(
        value =
            "UPDATE strategy " +
                "SET status = 'BUY_BLOCKED', " +
                "disabled_reason = :reason, " +
                "last_disabled_at = CURRENT_TIMESTAMP, " +
                "updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = :id AND status <> 'DELETED'",
        nativeQuery = true,
    )
    fun autoBuyBlock(
        @Param("id") id: Long,
        @Param("reason") reason: String,
    ): Int
}
