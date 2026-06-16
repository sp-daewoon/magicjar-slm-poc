package kr.spdaewoon.magicjar.batch.job.corpaction

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 캔들 수정주가 보정 전용 Repository (batch 모듈 — T-CA1 PR-29b).
 *
 * ADR 0051 §2 — body fun 금지 적용.
 * V8 컬럼 (adjustment_factor, volume_adjusted) 갱신 nativeQuery 사용.
 *
 * 보정 로직 (ADR 0018 §4-3):
 *   open_adj   = open_raw  * adjustment_factor * priceFactor
 *   close_adj  = close_raw * adjustment_factor * priceFactor
 *   volume_adjusted = volume * (1 / priceFactor) — 분할 시 거래량 증가
 *   adjustment_factor = adjustment_factor * priceFactor (누적)
 */
@Repository
interface CandleAdjustmentRepository : JpaRepository<CandleAdjEntityStub, Long> {
    /**
     * ex_date 이전 candle 전체 소급 보정.
     * priceFactor: 보정 배수 (STOCK_SPLIT 50:1 → 0.02, REVERSE_SPLIT 50:1 → 50.0)
     * 거래량 보정: volume_adjusted = volume * (1 / priceFactor)
     *   분할 시 priceFactor < 1 → volume 증가 (1/0.02 = 50 → 거래량 50배)
     *   병합 시 priceFactor > 1 → volume 감소
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE candle SET
                open_adj  = COALESCE(open_raw,  open_adj)  * COALESCE(adjustment_factor, 1.0) * :priceFactor,
                high_adj  = COALESCE(high_raw,  high_adj)  * COALESCE(adjustment_factor, 1.0) * :priceFactor,
                low_adj   = COALESCE(low_raw,   low_adj)   * COALESCE(adjustment_factor, 1.0) * :priceFactor,
                close_adj = COALESCE(close_raw, close_adj) * COALESCE(adjustment_factor, 1.0) * :priceFactor,
                volume_adjusted   = CASE
                    WHEN :priceFactor > 0 THEN CAST(COALESCE(volume, 0) * (1.0 / :priceFactor) AS BIGINT)
                    ELSE COALESCE(volume, 0) END,
                adjustment_factor = COALESCE(adjustment_factor, 1.0) * :priceFactor
            WHERE code = :code AND ts < CAST(:exDate AS timestamptz)
        """,
        nativeQuery = true,
    )
    fun adjustCandles(
        @Param("code") code: String,
        @Param("exDate") exDate: LocalDate,
        @Param("priceFactor") priceFactor: BigDecimal,
    ): Int
}

/**
 * CandleAdjustmentRepository JpaRepository 타입 파라미터용 stub 엔티티.
 * 실제 candle 엔티티는 api 모듈 소유 — batch 모듈에서 타입 충돌 방지.
 * adjustCandles()는 nativeQuery 전용 — save/findById 호출 0.
 */
@Entity
@Table(name = "candle")
class CandleAdjEntityStub {
    @Id
    var id: Long = 0L

    protected constructor()
}
