package kr.spdaewoon.magicjar.domain.backtest.port

import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import java.math.BigDecimal

/**
 * 백테스트 수수료 모델 포트 (ADR 0056 §2-3 LOCK).
 *
 * 구현은 api 모듈 [kr.spdaewoon.magicjar.api.service.backtest.commission.KisDirectCommissionModel].
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 *
 * ### 모델 키
 * - "kis_direct" → KisDirectCommissionModel (위탁 0.015% + 유관기관 0.0036% + VAT 10%)
 * - "none" → NoOpCommissionModel (0 반환)
 */
interface BacktestCommissionModel {
    /** 모델 식별 키 */
    val name: String

    /**
     * 수수료를 계산한다.
     *
     * @param side 매매 방향 (매수·매도 모두 수수료 발생)
     * @param notional 거래 대금 = executedPrice × quantity (슬리피지 적용 후)
     * @return 수수료 합계 (KRW, VAT 포함)
     */
    fun calculate(
        side: TradeSide,
        notional: BigDecimal,
    ): BigDecimal
}
