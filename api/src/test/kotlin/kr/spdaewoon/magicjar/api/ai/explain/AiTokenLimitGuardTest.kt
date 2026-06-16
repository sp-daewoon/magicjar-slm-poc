package kr.spdaewoon.magicjar.api.ai.explain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.api.persistence.AiCommentLogJpaRepository
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * AiTokenLimitGuard — ADR 0026 §2-5 amend · 1일 1사용자 토큰 한도 가드.
 */
class AiTokenLimitGuardTest {
    private val repository = mockk<AiCommentLogJpaRepository>()
    private val guard = AiTokenLimitGuard(repository = repository, dailyLimit = 1000L)

    @Test
    fun `한도 미달 시 통과 (null)`() {
        every { repository.sumTokensByUserSince(any(), any<Instant>()) } returns 500L
        guard.check(userId = 1L).shouldBeNull()
    }

    @Test
    fun `한도 정확히 도달 시 refuse 한국어`() {
        every { repository.sumTokensByUserSince(any(), any<Instant>()) } returns 1000L
        val reason = guard.check(userId = 1L)
        reason.shouldNotBeNull()
        reason.code shouldBe "DAILY_TOKEN_LIMIT_EXCEEDED"
        reason.limit shouldBe 1000L
        reason.used shouldBe 1000L
        reason.userMessage shouldContain "1000 토큰"
        reason.userMessage shouldContain "오늘"
    }

    @Test
    fun `한도 초과 시 refuse - 사용량 표시 정확`() {
        every { repository.sumTokensByUserSince(any(), any<Instant>()) } returns 1500L
        val reason = guard.check(userId = 1L)
        reason.shouldNotBeNull()
        reason.used shouldBe 1500L
        reason.userMessage shouldContain "1500"
    }
}
