package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.strategy.EvaluatorAutoDisableCallback
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Auto-pause 가드 — 연속 BLOCKED N회 시 strategy 신규 BUY 차단(BUY_BLOCKED).
 *
 * 임계 도달 시 [EvaluatorAutoDisableCallback.onAutoDisable] 호출. RISK_VIOLATION은 전체 평가 중지가 아니라
 * BUY_BLOCKED 상태로 전환되어 SELL/익절/손절 평가는 계속 유지한다.
 *
 * spec 인용: ADR 0067 §7-1 AutoPauseGuard + §7-2 단계 5 + F-1 보호 처리.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class AutoPauseGuard(
    private val riskEventRepositoryProvider: ObjectProvider<RiskEventRepository>,
    private val autoDisableCallback: EvaluatorAutoDisableCallback,
) {
    private val log = LoggerFactory.getLogger(AutoPauseGuard::class.java)

    companion object {
        /** BLOCKED 누적 검사 window (분) — ADR 0067 §2-6 threshold 산출 사유 정합. */
        const val DEFAULT_WINDOW_MINUTES = 10L

        /**
         * 자동정지 임계에서 제외하는 **무해 가드 rule** set (F9 — 2026-06-12 최고 관리자 결재).
         *
         * 진보 페르소나 (ADR 0087):
         *   - 💡 [문제] threshold(10) 도달 자동정지는 "자본 위험 신호의 연속 누적"을 가정한다. 그런데 기존
         *     count 는 **verdict=BLOCKED 전수**라, flat 전략이 매분 발생시키는 정상 가드(보유 0 SELL 차단 등)가
         *     10분이면 임계를 채워 모든 상태형 exit 전략을 자동정지시키는 구조 결함이었다(10:34 전략 85·14:22 전략 86).
         *   - 🔄 [리팩토링] 무해 rule 을 SQL 카운트 단계에서 제외 — 임계 산식 자체는 무손상(threshold·전환 메커니즘
         *     변경 0). `NotificationDispatcher.SLACK_MUTED_RISK_RULES` 의 상수 set 패턴과 정합(한 곳만 보강).
         *   - ✨ [모던 패턴] enum.name 문자열 set — DB 에 저장된 rule 문자열과 직접 매칭(adapter 변환 0).
         *
         * 제외 분류 근거:
         *   - [RiskRule.SELL_WITHOUT_POSITION]     : 보유 0 의 SELL 차단 = 정상 가드 (long-only-trading.md §4 권위).
         *   - [RiskRule.MAX_CONCURRENT_POSITIONS]  : 보유 중 중복 매수 차단 = 정상 거동(이미 진입한 자본 보호 X).
         *   - [RiskRule.SELL_QUANTITY_UNAVAILABLE] : 익절/손절 선주문 예약 중 SELL 가용 0 = 정상 거동.
         *
         * **유지(임계 카운트)**: DAILY_LOSS_LIMIT·POSITION_LIMIT·TRADE_FREQUENCY_MINUTE/DAY·KILL_SWITCH_ON·
         * INVALID_SIGNAL_PRICE·RISK_PLAN_REQUIRED·INVALID_ORDER_INTENT·KIS_EXECUTION_SYNC_STALE·USER_DISABLED
         * — 실제 자본 위험·예산 초과 시도·데이터/운영 무결성 신호.
         */
        val BENIGN_AUTO_PAUSE_EXCLUDED_RULES: Set<String> =
            setOf(
                RiskRule.SELL_WITHOUT_POSITION,
                RiskRule.MAX_CONCURRENT_POSITIONS,
                RiskRule.SELL_QUANTITY_UNAVAILABLE,
            ).map { it.name }.toSet()
    }

    /**
     * strategy 연속 BLOCKED 임계 검사 + 도달 시 신규 BUY 차단 callback 호출.
     *
     * @return true — AUTO_PAUSE 발동 · false — 미발동
     */
    fun checkAndPauseIfNeeded(
        strategyId: Long,
        autoPauseThreshold: Int,
    ): Boolean {
        val repo = riskEventRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.debug(
                "AutoPauseGuard RiskEventRepository 미합류 — skip (strategyId={})",
                strategyId,
            )
            return false
        }

        val recentBlocked =
            try {
                // F9 — 무해 가드 rule 제외: 정상 거동(보유 0 SELL 차단 등)이 임계에 기여하지 않게 한다.
                repo.countRecentBlockedByStrategy(
                    strategyId,
                    DEFAULT_WINDOW_MINUTES,
                    BENIGN_AUTO_PAUSE_EXCLUDED_RULES,
                )
            } catch (e: Exception) {
                log.warn(
                    "AutoPauseGuard countRecentBlockedByStrategy 실패 (skip): strategyId={} err={}",
                    strategyId,
                    e.message,
                )
                return false
            }

        if (recentBlocked >= autoPauseThreshold) {
            log.warn(
                "AutoPauseGuard threshold 도달 — 신규 BUY 차단 발동: strategyId={} recentBlocked={} threshold={}",
                strategyId,
                recentBlocked,
                autoPauseThreshold,
            )
            try {
                // ADR 0067 F-1 정합 — risk 차단 누적은 사용자 화면에서 구분 가능한 사유로 남긴다.
                autoDisableCallback.onAutoDisable(strategyId, StrategyDisabledReason.RISK_VIOLATION)
            } catch (e: Exception) {
                log.error(
                    "AutoPauseGuard onAutoDisable 호출 실패: strategyId={} err={}",
                    strategyId,
                    e.message,
                )
            }
            return true
        }
        return false
    }
}
