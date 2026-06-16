package kr.spdaewoon.magicjar.domain.backtest

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 백테스트 포지션 (ADR 0056 §2-1 LOCK).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 *
 * @param code 종목코드
 * @param quantity 보유 수량 (음수=공매도 — 1차 미지원)
 * @param avgEntryPrice 평균 매수가
 * @param openedAt 포지션 오픈 시각
 * @param closedAt 포지션 종료 시각 (null=현재 보유 중)
 * @param realizedPnl 실현 손익 (closedAt != null인 경우만 의미 있음)
 */
data class BacktestPosition(
    val code: String,
    val quantity: Long,
    val avgEntryPrice: BigDecimal,
    val openedAt: Instant,
    val closedAt: Instant? = null,
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
)
