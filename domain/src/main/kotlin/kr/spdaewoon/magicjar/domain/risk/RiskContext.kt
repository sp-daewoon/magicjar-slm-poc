package kr.spdaewoon.magicjar.domain.risk

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import java.math.BigDecimal

/**
 * Risk 평가 context (ADR 0067 §5-6).
 *
 * RiskGatewayEvaluator.evaluate가 6 룰 평가 시 필요한 모든 입력의 immutable snapshot.
 *
 * ## 전략별 포지션 귀속 (ADR 0110, 분할매수 R2-2)
 * [strategyPosition] 은 SELL 시그널의 전략(strategyId) lot ledger snapshot 이다. SELL 계열 2룰
 * (`SELL_WITHOUT_POSITION`·`SELL_QUANTITY_UNAVAILABLE`)은 이 값이 **non-null(행 존재)** 이면 전략별 기준으로,
 * **null(분할매수 미사용 전략·과거 보유)** 이면 기존 user+code 합산 기준으로 평가한다(하위호환 fallback — zero-regression).
 * KIS 잔고 하드 게이트·POSITION_LIMIT·MAX_CONCURRENT 는 user+code 합산 유지(KIS·자본 노출은 전략 무관 — spec §6-2 LOCK).
 *
 * spec 인용: ADR 0067 §5-6 · ADR 0110 §2.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
data class RiskContext(
    val userId: String,
    val config: RiskConfig,
    val currentCapital: BigDecimal,
    /**
     * 주문가능 예수금(매수 버짓 기준 — 사용자 결재 2026-06-16).
     *
     * BUY 수량 산정은 **순자산(currentCapital)이 아니라 실제 주문할 현금(예수금)** 을 버짓 상한으로 쓴다.
     * 보유주식이 많아 순자산은 크지만 예수금이 부족하면 KIS 가 "주문가능금액 초과(EGW...)"로 통째 거부하던
     * 결함을 막는다("남은 예수금으로 살 수 있는 만큼만 산다").
     *
     * ## 출처 (consumer 배선 — 새 KIS API 호출 0)
     * 기존 잔고 snapshot 의 `prvs_rcdl_excc_amt`(D+2 실가용 예수금, `AccountBalance.cashD2`)를 재사용한다.
     * cashD2 가 비정상(0/음수)이면 `dnca_tot_amt`(예수금총액, `cashBalance`)로 fallback, 둘 다 불가하면 조회
     * 불가(CapitalOutcome.Unavailable) 경로로 흐른다(0 합성 금지). 페이퍼/레거시 provider 는 가상 자본을 그대로
     * 예수금으로 본다(예수금 개념 없음 — currentCapital 동일값 주입).
     *
     * 매수 사이징에서만 사용하며 SELL·DAILY_LOSS percent(currentCapital 기준)는 영향 0.
     */
    val orderableCash: BigDecimal,
    val currentPositions: List<Position>,
    val openOrders: List<OrderRequest> = emptyList(),
    val dailyRealizedPnl: BigDecimal,
    val recentTradesMinute: Int,
    val recentTradesDay: Int,
    val killSwitchOn: Boolean,
    val nowKst: Instant,
    /**
     * SELL 시그널 전략의 lot ledger snapshot (ADR 0110). non-null 이면 SELL 2룰을 전략별로 평가,
     * null 이면 user+code 합산 fallback(하위호환). BUY·기타 룰과는 무관.
     */
    val strategyPosition: StrategyPosition? = null,
) {
    init {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(currentCapital >= BigDecimal.ZERO) { "currentCapital must be >= 0" }
        require(orderableCash >= BigDecimal.ZERO) { "orderableCash must be >= 0" }
        require(recentTradesMinute >= 0) { "recentTradesMinute must be >= 0" }
        require(recentTradesDay >= 0) { "recentTradesDay must be >= 0" }
    }
}
