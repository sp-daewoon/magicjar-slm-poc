package kr.spdaewoon.magicjar.domain.performance.port

import kr.spdaewoon.magicjar.domain.performance.EquityPoint
import kr.spdaewoon.magicjar.domain.performance.PnlDaily
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Performance 계산 port — 헌법 §6 (ADR 0051) abstract fun only LOCK.
 *
 * 5 지표 계산 인터페이스 (ADR 0072 §2-3 C-1 LOCK):
 * - totalPnl, winRate, profitLossRatio, mdd, equityCurve
 *
 * 구현체: PerformanceCalculatorImpl (api 모듈 — pure math, KIS/AI 호출 0).
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface PerformanceCalculator {
    /** 총 실현 손익 합산. */
    fun totalPnl(pnlDailyList: List<PnlDaily>): BigDecimal

    /** 승률 계산 (수익 거래 / 전체 체결 거래). 체결 0건 시 0.0 반환. */
    fun winRate(pnlDailyList: List<PnlDaily>): BigDecimal

    /**
     * 평균 손익비 avg(profit) / abs(avg(loss)).
     * 손실 거래 0건 시 null 반환.
     */
    fun profitLossRatio(pnlDailyList: List<PnlDaily>): BigDecimal?

    /**
     * 최대 낙폭 (MDD) 계산 — ADR 0072 §3-2 알고리즘 LOCK.
     * equityCurve 기반 peak-trough 방식.
     * 반환: % (NUMERIC(8,4), HALF_UP).
     */
    fun maxDrawdown(equityCurve: List<EquityPoint>): BigDecimal

    /**
     * Equity Curve 시계열 생성.
     * pnlDailyList → [EquityPoint] 리스트 (누적 P&L 순서 정합).
     * from~to 범위 내 pnlDaily가 없는 날짜는 포함 안 함 (거래 없는 날 생략).
     */
    fun buildEquityCurve(
        pnlDailyList: List<PnlDaily>,
        from: LocalDate,
        to: LocalDate,
    ): List<EquityPoint>
}
