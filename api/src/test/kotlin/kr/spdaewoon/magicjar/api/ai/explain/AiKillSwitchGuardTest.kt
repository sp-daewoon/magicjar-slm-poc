package kr.spdaewoon.magicjar.api.ai.explain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * AiKillSwitchGuard — ADR 0026 §2-5 amend · ADR 0094 §4-4-B amend.
 */
class AiKillSwitchGuardTest {
    @Test
    fun `enabled=false default - AI_DISABLED refuse 한국어`() {
        val guard = AiKillSwitchGuard(enabled = false, killswitch = false)
        val reason = guard.check()
        reason.shouldNotBeNull()
        reason.code shouldBe "AI_DISABLED"
        reason.userMessage shouldBe "AI 기능이 비활성 상태입니다. 설정에서 활성화한 후 다시 시도해 주세요."
    }

    @Test
    fun `killswitch=true 우선 - KILLSWITCH_ENGAGED refuse 한국어 (enabled true 라도 차단)`() {
        val guard = AiKillSwitchGuard(enabled = true, killswitch = true)
        val reason = guard.check()
        reason.shouldNotBeNull()
        reason.code shouldBe "KILLSWITCH_ENGAGED"
        reason.userMessage shouldBe "AI 기능이 운영자에 의해 일시 차단되었습니다. 잠시 후 다시 시도해 주세요."
    }

    @Test
    fun `enabled=true 와 killswitch=false 통과 - null 반환`() {
        val guard = AiKillSwitchGuard(enabled = true, killswitch = false)
        guard.check().shouldBeNull()
    }
}
