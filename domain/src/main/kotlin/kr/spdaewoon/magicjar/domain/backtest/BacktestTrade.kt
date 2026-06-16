package kr.spdaewoon.magicjar.domain.backtest

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 백테스트 개별 매매 기록 (ADR 0056 §2-1 LOCK).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 *
 * @param code 종목코드
 * @param side 매매 방향 (BUY · SELL)
 * @param quantity 거래 수량
 * @param executedPrice 슬리피지 적용 후 체결가
 * @param rawPrice 슬리피지 적용 전 캔들 종가
 * @param executedAt 체결 시각
 * @param commission 수수료 (VAT 포함)
 * @param tax 세금 (매도 시 거래세 + 농특세)
 * @param slippage 슬리피지 금액
 * @param signalReason DSL evaluator가 반환한 reason 맵
 */
data class BacktestTrade(
    val code: String,
    val side: TradeSide,
    val quantity: Long,
    val executedPrice: BigDecimal,
    val rawPrice: BigDecimal,
    val executedAt: Instant,
    val commission: BigDecimal,
    val tax: BigDecimal,
    val slippage: BigDecimal,
    val signalReason: Map<String, Any>,
)

/** 매매 방향 */
enum class TradeSide {
    BUY,
    SELL,
}
