package kr.spdaewoon.magicjar.consumer.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant as JavaInstant

/**
 * ConsumerStrategyLookupJpaAdapter unit test (architect ★★ — 자본 path test coverage).
 *
 * strategy 테이블 조회 + dsl_entry/dsl_exit 텍스트를 AST로 재파싱하는 Phase 1 본체 검증.
 * spec 인용: ADR 0066 §7-1 + §7-2 (StrategyLookupPort) — 실시간 hot path consumer JVM 단독 구현.
 */
class ConsumerStrategyLookupJpaAdapterTest {
    private val strategyJpaRepository = mockk<ConsumerStrategyJpaRepository>()
    private val objectMapper = ObjectMapper()
    private val kisYamlDslParser = mockk<KisYamlDslParser>()

    private val adapter =
        ConsumerStrategyLookupJpaAdapter(
            strategyJpaRepository = strategyJpaRepository,
            objectMapper = objectMapper,
            kisYamlDslParser = kisYamlDslParser,
        )

    @Test
    fun `ACTIVE strategy 1건 조회 시 dsl_entry sell 텍스트를 모두 재파싱하고 DslStrategyDefinition을 반환한다`() {
        val buyAst = condition("1", Operator.GT, "0")
        val sellAst = condition("0", Operator.LT, "1")
        val entity =
            jpaEntity(
                id = 42L,
                userId = 1234L,
                name = "MA cross",
                nameKo = "이동평균 골든크로스",
                dslEntry = "MA(5) > MA(20)",
                dslExit = "MA(5) < MA(20)",
            )

        every {
            strategyJpaRepository.findActiveByCodeAndInterval(
                codeJson = """["005930"]""",
                intervalJson = """["5m"]""",
            )
        } returns listOf(entity)
        every { kisYamlDslParser.parse("MA(5) > MA(20)") } returns buyAst
        every { kisYamlDslParser.parse("MA(5) < MA(20)") } returns sellAst

        val result = adapter.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN)

        assertThat(result).hasSize(1)
        val strategy = result.single()
        assertThat(strategy.id).isEqualTo(42L)
        assertThat(strategy.userId).isEqualTo(1234L)
        assertThat(strategy.definition.dsl.name).isEqualTo("MA cross")
        assertThat(strategy.definition.dsl.nameKo).isEqualTo("이동평균 골든크로스")
        assertThat(strategy.definition.dsl.buyCondition).isSameAs(buyAst)
        assertThat(strategy.definition.dsl.sellCondition).isSameAs(sellAst)
        assertThat(strategy.definition.dsl.buyConditionText).isEqualTo("MA(5) > MA(20)")
        assertThat(strategy.definition.dsl.sellConditionText).isEqualTo("MA(5) < MA(20)")
        assertThat(strategy.complexity.complexityClass).isEqualTo(ComplexityClass.LOW)
        assertThat(strategy.disabledReason).isEqualTo(StrategyDisabledReason.NULL)
        verify(exactly = 1) { kisYamlDslParser.parse("MA(5) > MA(20)") }
        verify(exactly = 1) { kisYamlDslParser.parse("MA(5) < MA(20)") }
    }

    @Test
    fun `JpaRepository 가 빈 리스트 반환 시 빈 결과를 그대로 돌려준다`() {
        every {
            strategyJpaRepository.findActiveByCodeAndInterval(
                codeJson = """["999999"]""",
                intervalJson = """["1d"]""",
            )
        } returns emptyList()

        val result = adapter.findActiveByCodeAndInterval("999999", CandleInterval.ONE_DAY)

        assertThat(result).isEmpty()
        verify(exactly = 0) { kisYamlDslParser.parse(any()) }
    }

    @Test
    fun `dsl_entry 가 blank 인 entity 는 buyCondition null 로 평가되어 결과에서 제외된다`() {
        val entity = jpaEntity(id = 11L, userId = 7L, dslEntry = "", dslExit = "MA(5) < MA(20)")

        every {
            strategyJpaRepository.findActiveByCodeAndInterval(
                codeJson = """["005930"]""",
                intervalJson = """["5m"]""",
            )
        } returns listOf(entity)

        val result = adapter.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN)

        // blank buyCondition → parseDslOrNull → null → toDomainOrNull 에서 strategy 제외 (graceful)
        assertThat(result).isEmpty()
        verify(exactly = 0) { kisYamlDslParser.parse(any()) }
    }

    @Test
    fun `dsl_exit 가 null 인 entity 는 sellCondition null 로 매핑되고 buyCondition 만 반환한다`() {
        val buyAst = condition("1", Operator.GT, "0")
        val entity = jpaEntity(id = 21L, userId = 9L, dslEntry = "MA(5) > MA(20)", dslExit = null)

        every {
            strategyJpaRepository.findActiveByCodeAndInterval(
                codeJson = """["005930"]""",
                intervalJson = """["1h"]""",
            )
        } returns listOf(entity)
        every { kisYamlDslParser.parse("MA(5) > MA(20)") } returns buyAst

        val result = adapter.findActiveByCodeAndInterval("005930", CandleInterval.ONE_HOUR)

        assertThat(result).hasSize(1)
        val strategy = result.single()
        assertThat(strategy.definition.dsl.buyCondition).isSameAs(buyAst)
        assertThat(strategy.definition.dsl.sellCondition).isNull()
        assertThat(strategy.definition.dsl.sellConditionText).isNull()
        verify(exactly = 1) { kisYamlDslParser.parse("MA(5) > MA(20)") }
        verify(exactly = 0) { kisYamlDslParser.parse("") }
    }

    @Test
    fun `DSL 파싱이 예외를 던지면 strategy 는 graceful 하게 제외된다`() {
        val entity = jpaEntity(id = 31L, userId = 5L, dslEntry = "??invalid??", dslExit = null)

        every {
            strategyJpaRepository.findActiveByCodeAndInterval(
                codeJson = """["005930"]""",
                intervalJson = """["5m"]""",
            )
        } returns listOf(entity)
        every { kisYamlDslParser.parse("??invalid??") } throws RuntimeException("syntax error at pos 0")

        val result = adapter.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN)

        // 파싱 실패 → buy null → strategy 제외 (실시간 hot path 차단 회피 LOCK)
        assertThat(result).isEmpty()
    }

    @Test
    fun `eval_metric_summary JSON 텍스트는 Map 으로 역직렬화되어 노출된다`() {
        val buyAst = condition("1", Operator.GT, "0")
        val entity =
            jpaEntity(
                id = 51L,
                userId = 3L,
                dslEntry = "MA(5) > MA(20)",
                dslExit = null,
                evalMetricSummary = """{"avgEvalMs":12.5,"recentRuns":300}""",
            )

        every {
            strategyJpaRepository.findActiveByCodeAndInterval(
                codeJson = """["005930"]""",
                intervalJson = """["5m"]""",
            )
        } returns listOf(entity)
        every { kisYamlDslParser.parse("MA(5) > MA(20)") } returns buyAst

        val result = adapter.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN)

        val summary = result.single().evalMetricSummary
        assertThat(summary).isNotNull
        assertThat(summary!!["avgEvalMs"]).isEqualTo(12.5)
        assertThat(summary["recentRuns"]).isEqualTo(300)
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private fun condition(
        left: String,
        operator: Operator,
        right: String,
    ): DslNode =
        Condition(
            left = LiteralValue(BigDecimal(left)),
            operator = operator,
            right = LiteralValue(BigDecimal(right)),
        )

    private fun jpaEntity(
        id: Long,
        userId: Long,
        name: String = "test-strategy",
        nameKo: String? = null,
        dslEntry: String,
        dslExit: String?,
        evalMetricSummary: String? = null,
    ): ConsumerStrategyJpaEntity =
        ConsumerStrategyJpaEntity(
            id = id,
            userId = userId,
            name = name,
            nameKo = nameKo,
            dslEntry = dslEntry,
            dslExit = dslExit,
            universeMarket = "KOSPI",
            tf = "5m",
            astDepth = 1,
            nodeCount = 3,
            indicatorCallCount = 0,
            estimatedEvalTimeMs = 1,
            complexityClass = "LOW",
            disabledReason = null,
            lastDisabledAt = null,
            evalMetricSummary = evalMetricSummary,
            status = "ACTIVE",
            codes = """["005930"]""",
            intervals = """["5m"]""",
            createdAt = JavaInstant.parse("2026-05-01T00:00:00Z"),
            updatedAt = JavaInstant.parse("2026-05-20T00:00:00Z"),
        )
}
