package kr.spdaewoon.magicjar.domain.risk.port

import kr.spdaewoon.magicjar.domain.risk.RiskEvent

/**
 * RiskEvent 영속 포트 (ADR 0067 §5-5 + G-1). 헌법 §6 abstract만.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface RiskEventRepository {
    fun save(event: RiskEvent): RiskEvent

    /**
     * strategy당 최근 N분 내 BLOCKED 카운트 (AutoPauseGuard 임계 검사용 — F-1 · F9).
     *
     * [excludedRules] 에 담긴 rule 은 카운트에서 제외한다 — 무해 가드(보유 0 SELL 차단·중복 매수 차단 등
     * 정상 거동)가 자동정지 임계에 기여하지 않게 한다(F9 hotfix). 빈 set 이면 전체 BLOCKED 카운트(기존 거동).
     */
    fun countRecentBlockedByStrategy(
        strategyId: Long,
        windowMinutes: Long,
        excludedRules: Set<String> = emptySet(),
    ): Int
}
