package kr.spdaewoon.magicjar.api.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * ChartCandleProperties — Spring Boot `@ConfigurationProperties` binding 검증.
 *
 * Phase 3 (2026-05-20 ADR 0093) — application.yml `magicjar.candle.*` 키 매핑 회귀 가드.
 *
 * 검증 시나리오:
 *   1. property 미설정 시 default 값 (maxPagesByInterval=empty, defaultMaxPages=6, maxResponseLimit=5000)
 *   2. yml 매트릭스 binding (1d=50·1m=48·3m=38 등 spec PR #609 §5 매트릭스)
 *   3. maxResponseLimit override binding (env override path)
 *
 * ApplicationContextRunner — 부팅 비용 최소화 (full SpringBootTest 회피).
 *
 * 진보 페르소나 (ADR 0087):
 *   - 💡 Spring Boot `@ConfigurationProperties` constructor binding (data class) — kotlin idiom 정합
 *   - 🔄 Map<String, Int> binding은 yml key escape (`"[1d]"`) 필요 — ktlint·yml 표기 LOCK 검증
 *   - ✨ ContextRunner로 ApplicationContext 띄우는 cost 1초 이내
 */
class ChartCandlePropertiesBindingTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(TestConfig::class.java)

    @Test
    fun `property 미설정 시 default 값 binding 검증 — defaultMaxPages 6 maxResponseLimit 5000`() {
        contextRunner.run { ctx ->
            val props = ctx.getBean(ChartCandleProperties::class.java)

            assertThat(props.maxPagesByInterval).isEmpty()
            assertThat(props.defaultMaxPages).isEqualTo(6)
            assertThat(props.maxResponseLimit).isEqualTo(5000)
            // 누락 키 fallback 검증.
            assertThat(props.getMaxPagesFor("1d")).isEqualTo(6)
        }
    }

    @Test
    fun `yml 매트릭스 binding — spec PR #609 §5 정합 (1d=50, 1m=48, 3m=38 등)`() {
        contextRunner
            .withPropertyValues(
                "magicjar.candle.max-pages-by-interval.[1m]=48",
                "magicjar.candle.max-pages-by-interval.[3m]=38",
                "magicjar.candle.max-pages-by-interval.[5m]=38",
                "magicjar.candle.max-pages-by-interval.[15m]=38",
                "magicjar.candle.max-pages-by-interval.[30m]=38",
                "magicjar.candle.max-pages-by-interval.[90m]=38",
                "magicjar.candle.max-pages-by-interval.[1h]=38",
                "magicjar.candle.max-pages-by-interval.[4h]=20",
                "magicjar.candle.max-pages-by-interval.[12h]=12",
                "magicjar.candle.max-pages-by-interval.[1d]=50",
                "magicjar.candle.max-pages-by-interval.[1wk]=20",
                "magicjar.candle.max-pages-by-interval.[1mo]=12",
                "magicjar.candle.max-pages-by-interval.[1y]=6",
                "magicjar.candle.default-max-pages=6",
                "magicjar.candle.max-response-limit=5000",
            ).run { ctx ->
                val props = ctx.getBean(ChartCandleProperties::class.java)

                // ADR 0093 §3-1 매트릭스 LOCK 검증.
                assertThat(props.getMaxPagesFor("1m")).isEqualTo(48)
                assertThat(props.getMaxPagesFor("3m")).isEqualTo(38)
                assertThat(props.getMaxPagesFor("5m")).isEqualTo(38)
                assertThat(props.getMaxPagesFor("90m")).isEqualTo(38)
                assertThat(props.getMaxPagesFor("1d")).isEqualTo(50)
                assertThat(props.getMaxPagesFor("4h")).isEqualTo(20)
                assertThat(props.getMaxPagesFor("12h")).isEqualTo(12)
                assertThat(props.getMaxPagesFor("1wk")).isEqualTo(20)
                assertThat(props.getMaxPagesFor("1mo")).isEqualTo(12)
                assertThat(props.getMaxPagesFor("1y")).isEqualTo(6)
                assertThat(props.defaultMaxPages).isEqualTo(6)
                assertThat(props.maxResponseLimit).isEqualTo(5000)
            }
    }

    @Test
    fun `maxResponseLimit env override — 5000 → 3000 사용자 정책 변경`() {
        contextRunner
            .withPropertyValues("magicjar.candle.max-response-limit=3000")
            .run { ctx ->
                val props = ctx.getBean(ChartCandleProperties::class.java)

                assertThat(props.maxResponseLimit).isEqualTo(3000)
            }
    }

    @Test
    fun `defaultMaxPages override — 6 → 10 점진적 확장 시나리오 (spec Phase 1 정합)`() {
        contextRunner
            .withPropertyValues("magicjar.candle.default-max-pages=10")
            .run { ctx ->
                val props = ctx.getBean(ChartCandleProperties::class.java)

                assertThat(props.defaultMaxPages).isEqualTo(10)
                // 누락 키는 새 default(10) fallback.
                assertThat(props.getMaxPagesFor("unknown")).isEqualTo(10)
            }
    }

    @EnableConfigurationProperties(ChartCandleProperties::class)
    internal class TestConfig
}
