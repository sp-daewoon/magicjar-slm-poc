package kr.spdaewoon.magicjar.api.strategybuilder

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.strategyresearch.StrategyResearchAiTextClient
import kr.spdaewoon.magicjar.api.strategyresearch.StrategyResearchAiTextResult
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class StrategyBuilderAiGenerateServiceTest {
    private val parser = KisYamlDslParser(astDepthLimit = 32, nodeCountLimit = 10000, indicatorCallLimit = 50)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val strategyRepository: StrategyJpaRepository = mock()

    private fun service(complete: (String) -> StrategyResearchAiTextResult): StrategyBuilderAiGenerateService =
        StrategyBuilderAiGenerateService(
            aiTextClient = StrategyResearchAiTextClient { complete(it) },
            parser = parser,
            objectMapper = objectMapper,
            strategyRepository = strategyRepository,
        )

    private fun request(
        prompt: String = "20일선 위 눌림목 거래량 반등 스윙",
        count: Int = 1,
        userId: String = "74683223",
    ) = StrategyBuilderAiGenerateRequestDto(userId = userId, prompt = prompt, interval = "1d", count = count)

    private fun strategy(
        id: Long,
        name: String,
        entry: String,
    ) = StrategyJpaEntity(
        id = id,
        userId = 74683223L,
        name = name,
        nameKo = name,
        dslEntry = entry,
        dslExit = null,
        universeMarket = "KOSPI",
        tf = "1d",
    )

    // ───────────────────────── 메타 프롬프트 구성 ─────────────────────────

    @Test
    fun `generate meta prompt includes dsl grammar existing strategies and noise-tp rule`() {
        whenever(strategyRepository.findAllByUserIdAndStatusNot(any(), any(), any()))
            .thenReturn(PageImpl(listOf(strategy(31, "내 골든크로스", "ma(5) crosses_above ma(20)"))))
        var captured = ""
        val svc =
            service { prompt ->
                captured = prompt
                StrategyResearchAiTextResult(
                    provider = "test-claude",
                    text =
                        """
                        {"items":[{"name":"눌림목 반등","description":"x","entryDsl":"close > ma(20) and volume > volume_ma(20) * 1.5",
                        "exitDsl":"close crosses_below ma(5)","riskPlan":{"takeProfitPct":1.5,"fixedStopLossPct":0.8,"minHoldBars":3},
                        "rationale":"20일선 추세 필터와 거래량 확인."}]}
                        """.trimIndent(),
                )
            }

        val response = svc.generate(request())

        // DSL 문법 카탈로그 — 파서 화이트리스트에서 추출된 식별자가 포함되어야 함
        captured.contains("crosses_above") shouldBe true
        captured.contains("volume_ma") shouldBe true
        captured.contains("ma(이동평균(MA))") shouldBe true // IndicatorCatalog id(displayName)
        // 기존 사용자 전략(중복 회피 컨텍스트)
        captured.contains("내 골든크로스") shouldBe true
        captured.contains("ma(5) crosses_above ma(20)") shouldBe true
        // 잔익절 규칙 주입
        captured.contains("0.5% 이상") shouldBe true
        captured.contains("0.3% 이하") shouldBe true
        // 사용자 prompt
        captured.contains("20일선 위 눌림목 거래량 반등 스윙") shouldBe true
        // 타임프레임 힌트
        captured.contains("1d 봉 기준") shouldBe true

        response.items shouldHaveSize 1
        response.items[0].dslValid shouldBe true
        response.provider shouldBe "test-claude"
    }

    // ───────────────────────── DSL 검증 게이트 ─────────────────────────

    @Test
    fun `generate marks dslValid true for parseable entry and exit`() {
        whenever(strategyRepository.findAllByUserIdAndStatusNot(any(), any(), any())).thenReturn(PageImpl(emptyList()))
        val svc =
            service {
                StrategyResearchAiTextResult(
                    provider = "test-claude",
                    text =
                        """
                        {"items":[{"name":"RSI 과매도 반등","description":"x","entryDsl":"rsi(14) < 30 and close > ema(5)",
                        "exitDsl":"rsi(14) > 70","riskPlan":{"takeProfitPct":2.0,"fixedStopLossPct":1.0,"minHoldBars":2},"rationale":"r"}]}
                        """.trimIndent(),
                )
            }

        val response = svc.generate(request())

        response.items[0].dslValid shouldBe true
        response.items[0].dslError shouldBe null
    }

    @Test
    fun `generate marks dslValid false when entry uses unsupported identifier and regen also fails`() {
        whenever(strategyRepository.findAllByUserIdAndStatusNot(any(), any(), any())).thenReturn(PageImpl(emptyList()))
        var calls = 0
        val svc =
            service {
                calls++
                // 1차·재생성 모두 미지원 식별자 prev_drop 반환 → 끝내 invalid
                StrategyResearchAiTextResult(
                    provider = "test-claude",
                    text =
                        """
                        {"items":[{"name":"잘못된 전략","description":"x","entryDsl":"prev_drop > 3 and close > ma(20)",
                        "exitDsl":null,"riskPlan":{"takeProfitPct":1.5,"fixedStopLossPct":0.8,"minHoldBars":2},"rationale":"r"}]}
                        """.trimIndent(),
                )
            }

        val response = svc.generate(request())

        response.items shouldHaveSize 1
        response.items[0].dslValid shouldBe false
        response.items[0].dslError.shouldNotBeNull()
        response.items[0].dslError!!.contains("prev_drop") shouldBe true
        // 1차 + 재생성 = 2회 호출
        calls shouldBe 2
        response.warnings.any { it.contains("자동 재생성") } shouldBe true
        response.warnings.any { it.contains("DSL 파싱에 실패") } shouldBe true
    }

    @Test
    fun `generate recovers invalid item via one regeneration`() {
        whenever(strategyRepository.findAllByUserIdAndStatusNot(any(), any(), any())).thenReturn(PageImpl(emptyList()))
        var calls = 0
        val svc =
            service { prompt ->
                calls++
                if (calls == 1) {
                    // 1차: 미지원 식별자 → invalid
                    StrategyResearchAiTextResult(
                        provider = "test-claude",
                        text =
                            """{"items":[{"name":"신고가 돌파","description":"x","entryDsl":"close > highest_break(20)",
                            "exitDsl":null,"riskPlan":{"takeProfitPct":2.0,"fixedStopLossPct":1.0,"minHoldBars":3},"rationale":"r"}]}""",
                    )
                } else {
                    // 재생성 프롬프트에는 직전 실패 DSL·에러가 동봉되어야 함
                    prompt.contains("highest_break(20)") shouldBe true
                    prompt.contains("파서 에러") shouldBe true
                    StrategyResearchAiTextResult(
                        provider = "test-claude",
                        text =
                            """{"items":[{"name":"신고가 돌파","description":"x","entryDsl":"close > highest(20)",
                            "exitDsl":null,"riskPlan":{"takeProfitPct":2.0,"fixedStopLossPct":1.0,"minHoldBars":3},"rationale":"r"}]}""",
                    )
                }
            }

        val response = svc.generate(request())

        calls shouldBe 2
        response.items shouldHaveSize 1
        response.items[0].dslValid shouldBe true
        response.items[0].entryDsl shouldBe "close > highest(20)"
    }

    // ───────────────────────── count cap ─────────────────────────

    @Test
    fun `generate caps returned items at requested count`() {
        whenever(strategyRepository.findAllByUserIdAndStatusNot(any(), any(), any())).thenReturn(PageImpl(emptyList()))
        val svc =
            service {
                StrategyResearchAiTextResult(
                    provider = "test-claude",
                    text =
                        """
                        {"items":[
                          {"name":"A","description":"a","entryDsl":"close > ma(20)","exitDsl":null,
                           "riskPlan":{"takeProfitPct":1.5,"fixedStopLossPct":0.8,"minHoldBars":2},"rationale":"r"},
                          {"name":"B","description":"b","entryDsl":"close > ma(10)","exitDsl":null,
                           "riskPlan":{"takeProfitPct":1.5,"fixedStopLossPct":0.8,"minHoldBars":2},"rationale":"r"},
                          {"name":"C","description":"c","entryDsl":"close > ma(5)","exitDsl":null,
                           "riskPlan":{"takeProfitPct":1.5,"fixedStopLossPct":0.8,"minHoldBars":2},"rationale":"r"}
                        ]}
                        """.trimIndent(),
                )
            }

        val response = svc.generate(request(count = 2))

        response.items shouldHaveSize 2
        response.items.map { it.name } shouldBe listOf("A", "B")
    }

    // ───────────────────────── 잔익절 경고 ─────────────────────────

    @Test
    fun `generate emits noise-tp warning when take profit below threshold`() {
        whenever(strategyRepository.findAllByUserIdAndStatusNot(any(), any(), any())).thenReturn(PageImpl(emptyList()))
        val svc =
            service {
                StrategyResearchAiTextResult(
                    provider = "test-claude",
                    text =
                        """
                        {"items":[{"name":"초단타","description":"x","entryDsl":"close > ma(5)","exitDsl":null,
                        "riskPlan":{"takeProfitPct":0.2,"fixedStopLossPct":0.3,"minHoldBars":1},"rationale":"r"}]}
                        """.trimIndent(),
                )
            }

        val response = svc.generate(request())

        response.warnings.any { it.contains("왕복 비용") } shouldBe true
    }

    // ───────────────────────── prompt 빈 값 400 ─────────────────────────

    @Test
    fun `generate rejects blank prompt with 400`() {
        val svc = service { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        val ex =
            shouldThrow<ResponseStatusException> {
                svc.generate(request(prompt = "   "))
            }
        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    // ───────────────────────── AI 에러 전파 ─────────────────────────

    @Test
    fun `generate propagates ai client error`() {
        whenever(strategyRepository.findAllByUserIdAndStatusNot(any(), any(), any())).thenReturn(PageImpl(emptyList()))
        val svc =
            service {
                throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Claude CLI 응답 시간이 초과되었습니다.")
            }

        shouldThrow<ResponseStatusException> {
            svc.generate(request())
        }
    }

    @Test
    fun `generate throws 503 when claude returns no usable items`() {
        whenever(strategyRepository.findAllByUserIdAndStatusNot(any(), any(), any())).thenReturn(PageImpl(emptyList()))
        // name/entryDsl 없는 항목만 → 모두 버려져 empty
        val svc = service { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[{"description":"근거만"}]}""") }

        val ex =
            shouldThrow<ResponseStatusException> {
                svc.generate(request())
            }
        ex.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
    }
}
