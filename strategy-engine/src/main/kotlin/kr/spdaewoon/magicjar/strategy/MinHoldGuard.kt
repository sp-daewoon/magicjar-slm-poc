package kr.spdaewoon.magicjar.strategy

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval

/**
 * 잔익절 방어 B안 — 최소 보유 캔들(minHoldBars) 게이트 (순수 함수).
 *
 * ## 의미론 (사용자 철학 정합 LOCK)
 * 진입 체결 후 N캔들(전략 interval 기준) 동안 **DSL 매도 시그널만 무시**한다.
 * **손절 라인(tick watcher · 1m ScaleInExit stop)·익절 라인(TP 선주문)은 절대 차단하지 않는다** — 안전망 우선.
 * 따라서 본 게이트는 [RealtimeSignalGenerator]에서 "DSL exit가 SELL일 때"에만 적용하고,
 * STOP_LOSS·TAKE_PROFIT·PARTIAL_TAKE_PROFIT 결정 경로에는 호출하지 않는다.
 *
 * ## 기준 시점 (LOCK)
 * 보유 시작 시점은 **최초 진입 ts**(StrategyPosition.openedAt)다. scale-in 추가 매수로 리셋하지 않는다
 * (단순·예측 가능 — 어댑터 upsert가 openedAt을 신규 행에만 채우고 재진입 사이클에서 보존).
 *
 * ## 경과 판정 (결정론)
 * 경과시간 = boundaryTs - openedAt. 필요시간 = interval 분 × minHoldBars.
 * `경과시간 >= 필요시간`이면 보유 충족(=DSL SELL 허용). 미만이면 skip.
 * 영업일·휴장 캘린더를 고려하지 않는 단순 벽시계 비교다(잔익절 방어는 진입 직후 짧은 구간이 대상이라
 * 캘린더 보정 없이도 의도를 충족 — 결정론·예측 가능 우선).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 가드 (ADR 0023 §2-4 정합)
 */
object MinHoldGuard {
    /**
     * DSL 매도 시그널을 minHoldBars 보유 미충족으로 무시해야 하는지 판정.
     *
     * @param minHoldBars 전략 설정값. null·0·음수면 비활성(false 반환 — 항상 SELL 허용).
     * @param interval 전략 평가 인터벌 (bar 길이 산정 기준).
     * @param openedAt 최초 진입 체결 ts (StrategyPosition.openedAt). null이면 진입 기록 없음 → false(게이트 미적용).
     * @param boundaryTs 현재 평가 캔들 마감 ts.
     * @return true면 보유 미충족 → DSL SELL skip. false면 충족(또는 비활성) → DSL SELL 진행.
     */
    fun shouldSkipDslSell(
        minHoldBars: Int?,
        interval: CandleInterval,
        openedAt: Instant?,
        boundaryTs: Instant,
    ): Boolean {
        val bars = minHoldBars ?: return false
        if (bars <= 0) return false
        if (openedAt == null) return false
        val requiredSeconds = intervalSeconds(interval) * bars
        val elapsedSeconds = boundaryTs.epochSeconds - openedAt.epochSeconds
        // 진입 ts가 미래(데이터 이상)면 elapsed 음수 → 보수적으로 skip(보유 미충족 취급).
        return elapsedSeconds < requiredSeconds
    }

    /** interval → bar 길이(초). [CandleInterval] 전체 매핑(DataReadinessService.minutes() 정합). */
    fun intervalSeconds(interval: CandleInterval): Long = intervalMinutes(interval) * 60L

    private fun intervalMinutes(interval: CandleInterval): Long =
        when (interval) {
            CandleInterval.ONE_MIN -> 1L
            CandleInterval.TWO_MIN -> 2L
            CandleInterval.THREE_MIN -> 3L
            CandleInterval.FIVE_MIN -> 5L
            CandleInterval.FIFTEEN_MIN -> 15L
            CandleInterval.THIRTY_MIN -> 30L
            CandleInterval.NINETY_MIN -> 90L
            CandleInterval.ONE_HOUR -> 60L
            CandleInterval.FOUR_HOUR -> 240L
            CandleInterval.TWELVE_HOUR -> 720L
            CandleInterval.ONE_DAY -> 1_440L
            CandleInterval.ONE_WEEK -> 10_080L
            CandleInterval.ONE_MONTH -> 43_200L
            CandleInterval.ONE_YEAR -> 525_600L
        }
}
