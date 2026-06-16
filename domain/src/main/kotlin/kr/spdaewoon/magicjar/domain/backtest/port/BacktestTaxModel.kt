package kr.spdaewoon.magicjar.domain.backtest.port

import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import java.math.BigDecimal

/**
 * 백테스트 세금 모델 포트 (ADR 0056 §2-3 LOCK).
 *
 * 구현은 api 모듈 [kr.spdaewoon.magicjar.api.service.backtest.tax.KoreanStockTaxModel].
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 *
 * ### 모델 키
 * - "korean_default" → KoreanStockTaxModel (거래세 0.18% + 농특세 0.15% KOSPI + VAT 10%)
 * - "none" → NoOpTaxModel (0 반환)
 */
interface BacktestTaxModel {
    /** 모델 식별 키 */
    val name: String

    /**
     * 세금을 계산한다.
     *
     * @param side 매매 방향 (BUY 시 세금 0, SELL 시 세금 발생)
     * @param price 체결 단가 (슬리피지 적용 후)
     * @param quantity 거래 수량
     * @param market 시장 구분 ("KOSPI" · "KOSDAQ" · "KONEX")
     * @return 세금 합계 (KRW)
     */
    fun calculate(
        side: TradeSide,
        price: BigDecimal,
        quantity: Long,
        market: String,
    ): BigDecimal
}
