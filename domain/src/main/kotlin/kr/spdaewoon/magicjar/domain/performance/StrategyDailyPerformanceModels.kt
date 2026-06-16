package kr.spdaewoon.magicjar.domain.performance

import kr.spdaewoon.magicjar.domain.order.OrderSide
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/*
 * 전략별 일자별 성과 도메인 모델 묶음 — spec `2026-06-16-strategy-daily-performance.md` §2·§4 정합.
 *
 * 본 파일의 타입들은 모두 순수 도메인 값(POKO)이다. Spring·JPA·외부 I/O 의존 0.
 * R1 라운드 산출물: 영속(JPA entity)·배치(잡)·API(DTO)는 다른 라운드가 이 타입을 입력/출력으로 매핑한다.
 *
 * 거래 단위 (spec §2):
 * - 1 거래 = 1 왕복(round-trip): 진입(매수 체결) → 완전 청산(전략·종목 포지션 수량 0 복귀).
 * - 귀속 날짜 = 왕복이 완전 청산된 날(close date, KST).
 * - 승 = 왕복 손익 > 0, 패 = 왕복 손익 < 0, break-even(= 0)은 win/loss 어디에도 안 넣되 round_trip_count 엔 포함.
 *
 * AI 호출 0 — 순수 계산 도메인 (spec §4-1, ADR 0072 §2-8 정합)
 */

/**
 * 시장 레짐 — 지수 일봉 기준 룰 분류 (spec §4-4).
 *
 * 본 enum 은 라벨 정의만 제공한다. 실제 분류(임계치 적용)는 지수 일봉 owner(market-data)가 수행하며,
 * 전략 일자행에는 배치가 지수 레짐을 합쳐 채운다. strategy-engine 도메인 계산기는 이 값을 산출하지 않는다.
 */
enum class MarketRegime {
    /** 추세 상승 (change_pct ≥ +0.6%). */
    UPTREND,

    /** 추세 하락 (change_pct ≤ −0.6%). */
    DOWNTREND,

    /** 횡보 (±0.6% 이내). */
    SIDEWAYS,

    /** 고변동성 (range_pct ≥ 2.5%, 방향 무관 우선). */
    HIGH_VOL,
}

/**
 * 체결 1건 — 왕복 집계의 입력(불변 체결 로그 1행 매핑).
 *
 * 배치/영속 라운드가 `order_execution` + `order_request.strategy_id` 를 이 타입으로 매핑해
 * [StrategyDailyPerformanceCalculator.aggregate] 에 넘긴다.
 *
 * @param strategyId  전략 귀속 ID (order_request.strategy_id).
 * @param code        종목 코드.
 * @param side        매수/매도 (도메인 [OrderSide] 재사용).
 * @param quantity    체결 수량 (> 0).
 * @param price       체결 단가 (원). 수수료·세금은 v1 산식에서 분리(spec §4-1 진입금액=평균단가×수량).
 * @param executedAt  체결 시각 (UTC Instant). KST 영업일 귀속은 계산기가 zone 으로 변환.
 */
data class ExecutionRecord(
    val strategyId: Long,
    val code: String,
    val side: OrderSide,
    val quantity: Long,
    val price: BigDecimal,
    val executedAt: Instant,
)

/**
 * 왕복 1건 결과 — 포지션 수량이 0 으로 복귀한 순간 마감된 거래 1건.
 *
 * @param strategyId   전략 ID.
 * @param code         종목 코드.
 * @param closedDate   완전 청산된 날 (KST). 일자 집계 귀속 기준 (spec §2).
 * @param realizedPnl  실현손익 = Σ (매도가 − 평균단가) × 청산수량 (원).
 * @param costBasis    진입금액 = Σ 평균단가 × 청산수량 (원, > 0). 수익률 분모.
 */
data class RoundTrip(
    val strategyId: Long,
    val code: String,
    val closedDate: LocalDate,
    val realizedPnl: BigDecimal,
    val costBasis: BigDecimal,
)

/**
 * 전략×일자 성과 집계 1행 — spec §3 V85 `strategy_daily_performance` 의 순수 도메인 사영.
 *
 * benchmark·alpha·regime 은 지수 데이터와 합쳐 배치가 채우므로 본 도메인 행에는 미포함이다
 * (이 계산기는 왕복 집계까지만 책임 — spec §4-1).
 *
 * @param strategyId       전략 ID.
 * @param tradeDate        영업일 (KST). 왕복 closeDate 기준 그룹.
 * @param roundTripCount   그날 청산된 왕복 수 (break-even 포함).
 * @param winCount         왕복 손익 > 0.
 * @param lossCount        왕복 손익 < 0.
 * @param realizedPnl      청산 왕복 손익 합 (원).
 * @param totalCostBasis   청산 왕복 진입금액 합 (원) — 수익률 분모.
 * @param returnPct        realizedPnl / totalCostBasis × 100. totalCostBasis = 0 이면 null.
 */
data class StrategyDailyPerformance(
    val strategyId: Long,
    val tradeDate: LocalDate,
    val roundTripCount: Int,
    val winCount: Int,
    val lossCount: Int,
    val realizedPnl: BigDecimal,
    val totalCostBasis: BigDecimal,
    val returnPct: BigDecimal?,
)

/**
 * 신뢰도 라벨 — 표본수·일관성 기준 (spec §4-5).
 */
enum class ConfidenceLabel {
    /** 누적 왕복 < 10 또는 거래일 < 5 — "신뢰도 낮음 N건". */
    LOW,

    /** 누적 왕복 < 30. */
    MEDIUM,

    /** 그 외. */
    HIGH,
}

/**
 * 전략 신뢰도 산출 결과 — 누적 일자행에서 읽기 시 계산(저장 안 함, spec §4-5).
 *
 * @param label              신뢰도 라벨.
 * @param sampleRoundTrips   누적 왕복 수 (Σ roundTripCount).
 * @param tradingDays        거래 발생 일수 (roundTripCount > 0 인 일자 수).
 * @param meanReturnPct      일별 returnPct 평균. 표본 없으면 null.
 * @param stdDevReturnPct    일별 returnPct 표준편차(모표준편차). 표본 < 2 면 null.
 * @param sharpe             mean / std. std = 0 또는 null 이면 null.
 */
data class StrategyConfidence(
    val label: ConfidenceLabel,
    val sampleRoundTrips: Int,
    val tradingDays: Int,
    val meanReturnPct: BigDecimal?,
    val stdDevReturnPct: BigDecimal?,
    val sharpe: BigDecimal?,
)
