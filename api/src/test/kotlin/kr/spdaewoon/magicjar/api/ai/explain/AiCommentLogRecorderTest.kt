package kr.spdaewoon.magicjar.api.ai.explain

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.api.persistence.AiCommentLogJpaEntity
import kr.spdaewoon.magicjar.api.persistence.AiCommentLogJpaRepository
import kr.spdaewoon.magicjar.domain.ai.AiCommentType
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/**
 * AiCommentLogRecorder — 2026-05-24 W4 P0 hotfix 회귀 방지 (ai_comment_log INSERT 0/5 → 5/5).
 *
 * 결함 evidence: `_workspace/qa/reports/2026-05-24-w4-p1-persistence-evidence.md`
 *   - 5 AI 진입점 응답 200 GREEN, 그러나 `psql SELECT count(*) FROM ai_comment_log` → 0건
 *   - root cause: `recordRefusal()` 에 `@Transactional` 누락 → JPA save silent fail
 *
 * 본 테스트는 다음을 보장:
 * 1. `recordRefusal()` 호출 시 `repository.save()` 1건 호출 (entity field 정합)
 * 2. `recordRefusal()` 에 `@Transactional(propagation = REQUIRES_NEW)` 어노테이션 존재 (reflection)
 * 3. `repository.save()` 실패 시 graceful — 호출자에게 throw 전파 0건 (본체 자본 path 영향 0)
 */
class AiCommentLogRecorderTest {
    private val repository = mockk<AiCommentLogJpaRepository>(relaxed = true)
    private val recorder = AiCommentLogRecorder(repository)

    @Test
    fun `recordRefusal - repository save 1건 호출 + entity field 정합 (graceful=TRUE · tokens=0 · cost=0)`() {
        val captured = slot<AiCommentLogJpaEntity>()
        every { repository.save(capture(captured)) } answers { firstArg() }

        val requestId = UUID.randomUUID()
        recorder.recordRefusal(
            requestId = requestId,
            userId = 42L,
            type = AiCommentType.MARKET,
            promptTemplate = "market_unresolved",
            refuseCode = "KILLSWITCH_ENGAGED",
            userMessage = "AI 차단됨",
        )

        verify(exactly = 1) { repository.save(any()) }

        val entity = captured.captured
        entity.requestId shouldBe requestId
        entity.userId shouldBe 42L
        entity.commentType shouldBe "MARKET"
        entity.provider shouldBe "none"
        entity.model shouldBe "none"
        entity.promptTemplate shouldBe "market_unresolved"
        entity.tokensIn shouldBe 0
        entity.tokensOut shouldBe 0
        entity.costUsd shouldBe BigDecimal.ZERO
        entity.latencyMs shouldBe 0L
        entity.cached shouldBe false
        entity.graceful shouldBe true
        entity.errorMessage shouldBe "KILLSWITCH_ENGAGED: AI 차단됨"
    }

    @Test
    fun `recordRefusal - repository save throw 시 graceful (호출자에게 예외 전파 0건)`() {
        every { repository.save(any<AiCommentLogJpaEntity>()) } throws RuntimeException("DB down")

        // 예외 전파 0건 — runCatching 없이 직접 호출 (try-catch 가 본체 흐름 보호하는지 검증)
        recorder.recordRefusal(
            requestId = UUID.randomUUID(),
            userId = 1L,
            type = AiCommentType.MARKET,
            promptTemplate = "market_unresolved",
            refuseCode = "DAILY_TOKEN_LIMIT_EXCEEDED",
            userMessage = "초과",
        )

        // save 호출은 발생했고, 본체 흐름은 정상 종료 — assertion 위치까지 도달 자체가 검증
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `recordRefusal - @Transactional propagation = REQUIRES_NEW 어노테이션 존재 (회귀 방지)`() {
        // hotfix 의 핵심 — @Transactional 누락 → INSERT silent fail 결함이 재발하지 않도록
        // reflection 으로 어노테이션 존재 + propagation REQUIRES_NEW 강제 LOCK.
        val method =
            AiCommentLogRecorder::class.java.getMethod(
                "recordRefusal",
                UUID::class.java,
                java.lang.Long.TYPE,
                AiCommentType::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            )

        val annotation = method.getAnnotation(Transactional::class.java)
        check(annotation != null) {
            "AiCommentLogRecorder.recordRefusal() 에 @Transactional 어노테이션 누락 — " +
                "2026-05-24 W4 P0 hotfix 회귀 결함 (ai_comment_log INSERT silent fail)."
        }
        annotation.propagation shouldBe Propagation.REQUIRES_NEW
    }
}
