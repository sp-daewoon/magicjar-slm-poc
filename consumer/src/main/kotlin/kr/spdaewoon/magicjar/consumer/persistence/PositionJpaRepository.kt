package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/**
 * position Spring Data JPA Repository (consumer 모듈 — ADR 0042).
 */
interface PositionJpaRepository : JpaRepository<PositionJpaEntity, Long> {
    @Query("SELECT DISTINCT p.code FROM PositionJpaEntity p WHERE p.quantity > 0")
    fun findDistinctCodesWithPositiveQuantity(): List<String>

    fun findByUserId(userId: String): List<PositionJpaEntity>

    fun findByUserIdAndQuantityGreaterThan(
        userId: String,
        minQuantity: Int,
    ): List<PositionJpaEntity>

    fun findByUserIdAndSourceAndQuantityGreaterThan(
        userId: String,
        source: String,
        minQuantity: Int,
    ): List<PositionJpaEntity>

    fun countByUserIdAndQuantityGreaterThan(
        userId: String,
        minQuantity: Int,
    ): Int

    fun countByUserIdAndSourceAndQuantityGreaterThan(
        userId: String,
        source: String,
        minQuantity: Int,
    ): Int

    fun findByUserIdAndCode(
        userId: String,
        code: String,
    ): PositionJpaEntity?

    fun findByUserIdAndCodeAndSource(
        userId: String,
        code: String,
        source: String,
    ): PositionJpaEntity?

    /**
     * upsert — ON CONFLICT (user_id, code, source) DO UPDATE.
     * idempotency 보장 (ADR 0042 §5-6 — 부팅 N회 반복 안전).
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO position (user_id, code, quantity, qty, avg_price, source, entry_ts, updated_at, created_at)
            VALUES (:userId, :code, :quantity, :quantity, :avgPrice, 'REAL', now(), now(), now())
            ON CONFLICT (user_id, code, source) DO UPDATE
                SET quantity = EXCLUDED.quantity,
                    qty = EXCLUDED.quantity,
                    source = 'REAL',
                    avg_price = EXCLUDED.avg_price,
                    updated_at = now()
        """,
        nativeQuery = true,
    )
    fun upsert(
        userId: String,
        code: String,
        quantity: Int,
        avgPrice: java.math.BigDecimal,
    )

    /**
     * Reconcile upsert — KIS 계좌 유형(REAL/MOCK)을 source에 보존하고 quantity·qty 동시 갱신.
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO position (user_id, code, quantity, qty, avg_price, source, entry_ts, updated_at, created_at)
            VALUES (:userId, :code, :quantity, :quantity, :avgPrice, :source, now(), now(), now())
            ON CONFLICT (user_id, code, source) DO UPDATE
                SET quantity = EXCLUDED.quantity,
                    qty = EXCLUDED.qty,
                    avg_price = EXCLUDED.avg_price,
                    source = EXCLUDED.source,
                    entry_ts = now(),
                    updated_at = now()
        """,
        nativeQuery = true,
    )
    fun upsertReconciled(
        userId: String,
        code: String,
        quantity: Int,
        avgPrice: java.math.BigDecimal,
        source: String,
    )
}
