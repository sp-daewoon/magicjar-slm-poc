package kr.spdaewoon.magicjar.domain.reconcile

import kr.spdaewoon.magicjar.domain.user.AccountType
import java.math.BigDecimal

/**
 * Reconcile 전용 KIS 보유 종목 (ADR 0070 §2-2 KisHolding).
 *
 * 기존 `domain.position.port.KisHolding`(ADR 0042 Bootstrap baseline)과 별도로 분리한 사유:
 * - reconcile은 평균단가 0.5% 임계 비교 의무 → BigDecimal precision 보존 의무 (소수점 round-down 방지)
 * - 페이징 흡수 (실전 50건/모의 20건 × N페이지) → reconcile 전용 어댑터 출력 타입
 *
 * @param symbol KIS pdno 6자리 종목 코드
 * @param quantity KIS hldg_qty 보유 수량 (정수)
 * @param avgPrice KIS pchs_avg_pric 평균 매수 단가 (BigDecimal)
 */
data class ReconcileHolding(
    val symbol: String,
    val quantity: Int,
    val avgPrice: BigDecimal,
    val accountType: AccountType = AccountType.REAL,
)

/**
 * 내부 DB position 스냅샷 (ADR 0070 §2-2 PositionUpsertPort 입력 형태).
 *
 * Phase 2 backend-core가 PositionUpsertPort.upsertFromKis(userId, holding) 시 사용 가능.
 */
data class InternalHolding(
    val symbol: String,
    val quantity: Int,
    val avgPrice: BigDecimal,
)
