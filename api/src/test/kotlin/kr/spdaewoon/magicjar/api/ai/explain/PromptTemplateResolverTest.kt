package kr.spdaewoon.magicjar.api.ai.explain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.api.persistence.PromptTemplateJpaEntity
import kr.spdaewoon.magicjar.api.persistence.PromptTemplateJpaRepository
import kr.spdaewoon.magicjar.domain.ai.AiCommentType
import org.junit.jupiter.api.Test

/**
 * PromptTemplateResolver — 변수 치환 + 미존재 throw 검증.
 */
class PromptTemplateResolverTest {
    private val repository = mockk<PromptTemplateJpaRepository>()
    private val resolver = PromptTemplateResolver(repository)

    @Test
    fun `변수 치환 - 모든 placeholder 정상 대체`() {
        val template =
            PromptTemplateJpaEntity(
                type = "symbol",
                version = "v1",
                templateText = "code={{code}} name={{name}}",
                variablesSchemaJson = "{}",
            )
        every { repository.findFirstByTypeAndIsActiveTrue("symbol") } returns template

        val result =
            resolver.resolve(
                type = AiCommentType.SYMBOL,
                context = mapOf("code" to "005930", "name" to "삼성전자"),
            )
        result.prompt shouldBe "code=005930 name=삼성전자"
        result.templateKey shouldBe "symbol_v1"
        result.version shouldBe "v1"
    }

    @Test
    fun `누락 변수 - 빈 문자열 치환 (NPE 회피)`() {
        val template =
            PromptTemplateJpaEntity(
                type = "market",
                version = "v1",
                templateText = "kospi={{kospiTrend}} sector={{sectorTopList}}",
                variablesSchemaJson = "{}",
            )
        every { repository.findFirstByTypeAndIsActiveTrue("market") } returns template

        val result =
            resolver.resolve(
                type = AiCommentType.MARKET,
                // sectorTopList 누락
                context = mapOf("kospiTrend" to "UP"),
            )
        result.prompt shouldBe "kospi=UP sector="
    }

    @Test
    fun `MARKET 진입점 - maPeriods 와 rsPeriod 치환 (ADR 0101 정합)`() {
        val template =
            PromptTemplateJpaEntity(
                type = "market",
                version = "v1",
                templateText = "ma={{maPeriods}} rs={{rsPeriod}}",
                variablesSchemaJson = "{}",
            )
        every { repository.findFirstByTypeAndIsActiveTrue("market") } returns template

        val result =
            resolver.resolve(
                type = AiCommentType.MARKET,
                context =
                    mapOf(
                        "maPeriods" to listOf(5, 20, 60, 120),
                        "rsPeriod" to 60,
                    ),
            )
        result.prompt shouldContain "[5, 20, 60, 120]"
        result.prompt shouldContain "rs=60"
    }

    @Test
    fun `template 미존재 - PromptTemplateNotFoundException throw`() {
        every { repository.findFirstByTypeAndIsActiveTrue(any()) } returns null
        val ex =
            shouldThrow<PromptTemplateNotFoundException> {
                resolver.resolve(AiCommentType.CHART, emptyMap())
            }
        ex.message!! shouldContain "type=chart"
    }
}
