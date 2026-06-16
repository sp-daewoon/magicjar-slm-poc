package kr.spdaewoon.magicjar.domain.backtest.port

import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import java.math.BigDecimal

/**
 * 백테스트 슬리피지 모델 포트 (ADR 0056 §2-3 LOCK).
 *
 * 구현은 api 모듈 [kr.spdaewoon.magicjar.api.service.backtest.slippage.FixedRateSlippageModel].
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 *
 * ### 모델 키
 * - "fixed_0.05" → FixedRateSlippageModel (매수 +0.05%, 매도 -0.05%)
 * - "none" → NoOpSlippageModel (rawPrice 그대로 반환)
 */
interface BacktestSlippageModel {
    /** 모델 식별 키 */
    val name: String

    /**
     * 슬리피지를 적용한 체결가를 반환한다.
     *
     * @param side 매매 방향 (BUY 시 rawPrice + slippage, SELL 시 rawPrice - slippage)
     * @param rawPrice 슬리피지 적용 전 캔들 종가
     * @return 슬리피지 적용 후 체결가
     */
    fun apply(
        side: TradeSide,
        rawPrice: BigDecimal,
    ): BigDecimal
}
