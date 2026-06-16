package kr.spdaewoon.magicjar.api.ai.explain

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * AI 비용 폭주 차단 KillSwitch (ADR 0094 §4-4-B amend · ADR 0026 §2-5 amend).
 *
 * 분기:
 * - `MAGICJAR_AI_KILLSWITCH=true` → 모든 AI 호출 즉시 차단 (graceful refuse 한국어)
 * - `MAGICJAR_AI_ENABLED=false` (default) → 사용자 명시 활성 전 차단
 *
 * 본 가드는 dispatcher 호출 전 (Controller 단)에서 첫 번째 게이트 — Kafka publish 자체를 차단해 비용 0 보장.
 *
 * 차단 우선순위 (P0 → P1):
 * 1. KillSwitch (운영자 긴급) — 모든 사용자 일괄 차단
 * 2. enabled=false (기본 설정) — 사용자 명시 활성 전
 * 3. 토큰 한도 (1일 1사용자) → `AiTokenLimitGuard`
 */
@Component
class AiKillSwitchGuard(
    @Value("\${magicjar.ai.enabled:false}") private val enabled: Boolean,
    @Value("\${magicjar.ai.killswitch:false}") private val killswitch: Boolean,
) {
    /**
     * AI 호출 허용 여부 검사.
     *
     * @return RefuseReason — null 이면 통과 / null 이 아니면 refuse 사유 (한국어 사용자 메시지 포함)
     */
    fun check(): RefuseReason? =
        when {
            killswitch ->
                RefuseReason(
                    code = "KILLSWITCH_ENGAGED",
                    userMessage = "AI 기능이 운영자에 의해 일시 차단되었습니다. 잠시 후 다시 시도해 주세요.",
                )
            !enabled ->
                RefuseReason(
                    code = "AI_DISABLED",
                    userMessage = "AI 기능이 비활성 상태입니다. 설정에서 활성화한 후 다시 시도해 주세요.",
                )
            else -> null
        }

    data class RefuseReason(
        val code: String,
        val userMessage: String,
    )
}
