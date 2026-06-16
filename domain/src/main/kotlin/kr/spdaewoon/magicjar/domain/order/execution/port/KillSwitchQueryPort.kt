package kr.spdaewoon.magicjar.domain.order.execution.port

/**
 * KillSwitch 조회 포트 (ADR 0024·0043 + ADR 0098 + ADR 0068 §14-2 step 4 LOCK).
 *
 * OrderRouterKafkaListener step 4 — `isOn(userId)` true 시 즉시 REJECTED + audit KILLSWITCH_DENIED.
 *
 * 기존 [kr.spdaewoon.magicjar.domain.risk.port.KillSwitchRepository] 와 구분 — 본 포트는 단일 boolean 검사 책임.
 * 자본 경로 권위 포트이므로 구현은 row 없음·DB 예외·알 수 없는 예외를 fail-closed ON으로 처리한다.
 *
 * 정상 등록 사용자는 Flyway/온보딩에서 `kill_switch_state.enabled=false` row를 명시 보유한다.
 * 따라서 row 없음은 "OFF"가 아니라 "상태 확인 불가"로 간주한다.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface KillSwitchQueryPort {
    /**
     * @return true = KillSwitch ON 또는 상태 확인 불가(fail-closed), false = 명시적 OFF row 확인.
     */
    fun isOn(userId: String): Boolean
}
