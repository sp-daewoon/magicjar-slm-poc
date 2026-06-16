package kr.spdaewoon.magicjar.api.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ChartCandleProperties — ADR 0093 §3-1 LOCK 매트릭스 정합 검증.
 *
 * Phase 3 (2026-05-20 backend-core):
 *   - timeframe별 maxPagesByInterval lookup 정합
 *   - 누락 키 fallback (defaultMaxPages) 회귀 안전
 *   - maxResponseLimit default 5000 (limit cap 500 → 5000 확장)
 *
 * 진보 페르소나 (ADR 0087):
 *   - 💡 default fallback 매트릭스 LOCK (회귀 안전성 검증)
 *   - 🔄 timeframe별 매트릭스 lookup 단일 시그니처 검증
 *   - ✨ data class default 값 = 신규 키 추가 시 기존 호출 영향 0 (회귀 안전)
 */
class ChartCandlePropertiesTest {
    @Test
    fun `default 생성자 — maxPagesByInterval 빈 map + defaultMaxPages 6 + maxResponseLimit 5000`() {
        val props = ChartCandleProperties()

        assertThat(props.maxPagesByInterval).isEmpty()
        assertThat(props.defaultMaxPages).isEqualTo(6)
        assertThat(props.maxResponseLimit).isEqualTo(5000)
    }

    @Test
    fun `getMaxPagesFor — 누락 키는 defaultMaxPages fallback`() {
        val props = ChartCandleProperties()

        // 모든 timeframe이 default 6으로 fallback (회귀 안전).
        assertThat(props.getMaxPagesFor("1d")).isEqualTo(6)
        assertThat(props.getMaxPagesFor("1m")).isEqualTo(6)
        assertThat(props.getMaxPagesFor("unknown-interval")).isEqualTo(6)
    }

    @Test
    fun `getMaxPagesFor — ADR 0093 §3-1 LOCK 매트릭스 lookup 정합`() {
        // application.yml 매트릭스 1:1 검증 (spec PR #609 §5 + 2026-05-31 3m 확장):
        //   1m=48, 3m·5m·15m·30m·90m·1h=38, 4h=20, 12h=12, 1d=50, 1wk=20, 1mo=12, 1y=6
        val props =
            ChartCandleProperties(
                maxPagesByInterval =
                    mapOf(
                        "1m" to 48,
                        "3m" to 38,
                        "5m" to 38,
                        "15m" to 38,
                        "30m" to 38,
                        "90m" to 38,
                        "1h" to 38,
                        "4h" to 20,
                        "12h" to 12,
                        "1d" to 50,
                        "1wk" to 20,
                        "1mo" to 12,
                        "1y" to 6,
                    ),
                defaultMaxPages = 6,
            )

        // ADR 0093 §3-1 매트릭스 각 timeframe별 검증.
        assertThat(props.getMaxPagesFor("1m")).isEqualTo(48)
        assertThat(props.getMaxPagesFor("3m")).isEqualTo(38)
        assertThat(props.getMaxPagesFor("5m")).isEqualTo(38)
        assertThat(props.getMaxPagesFor("15m")).isEqualTo(38)
        assertThat(props.getMaxPagesFor("30m")).isEqualTo(38)
        assertThat(props.getMaxPagesFor("90m")).isEqualTo(38)
        assertThat(props.getMaxPagesFor("1h")).isEqualTo(38)
        assertThat(props.getMaxPagesFor("4h")).isEqualTo(20)
        assertThat(props.getMaxPagesFor("12h")).isEqualTo(12)
        assertThat(props.getMaxPagesFor("1d")).isEqualTo(50)
        assertThat(props.getMaxPagesFor("1wk")).isEqualTo(20)
        assertThat(props.getMaxPagesFor("1mo")).isEqualTo(12)
        assertThat(props.getMaxPagesFor("1y")).isEqualTo(6)

        // 누락 interval은 default 6 fallback.
        assertThat(props.getMaxPagesFor("unknown")).isEqualTo(6)
    }

    @Test
    fun `매트릭스 cover 봉 수 정합 검증 — KIS 100건・page 기준 ADR 0093 §3-1`() {
        // KIS_PAGE_LIMIT = 100 (FHKST03010100 raw spec docstring).
        val kisPageLimit = 100
        val props =
            ChartCandleProperties(
                maxPagesByInterval =
                    mapOf(
                        "1m" to 48,
                        "3m" to 38,
                        "5m" to 38,
                        "15m" to 38,
                        "30m" to 38,
                        "90m" to 38,
                        "1h" to 38,
                        "4h" to 20,
                        "12h" to 12,
                        "1d" to 50,
                        "1wk" to 20,
                        "1mo" to 12,
                        "1y" to 6,
                    ),
            )

        // ADR 0093 §3-1 매트릭스 — 각 timeframe별 cover 봉 수:
        assertThat(props.getMaxPagesFor("1m") * kisPageLimit).isEqualTo(4800) // ≈ 1주 1분봉
        assertThat(props.getMaxPagesFor("3m") * kisPageLimit).isEqualTo(3800)
        assertThat(props.getMaxPagesFor("5m") * kisPageLimit).isEqualTo(3800) // ≈ 1년 5분봉
        assertThat(props.getMaxPagesFor("90m") * kisPageLimit).isEqualTo(3800)
        assertThat(props.getMaxPagesFor("4h") * kisPageLimit).isEqualTo(2000)
        assertThat(props.getMaxPagesFor("12h") * kisPageLimit).isEqualTo(1200)
        assertThat(props.getMaxPagesFor("1d") * kisPageLimit).isEqualTo(5000) // ≈ 20년 일봉
        assertThat(props.getMaxPagesFor("1wk") * kisPageLimit).isEqualTo(2000) // ≈ 38년 주봉
        assertThat(props.getMaxPagesFor("1mo") * kisPageLimit).isEqualTo(1200) // ≈ 100년 월봉
        assertThat(props.getMaxPagesFor("1y") * kisPageLimit).isEqualTo(600) // ≈ 600년
    }

    @Test
    fun `maxResponseLimit override 검증 — 5000 → 3000 사용자 정책 변경 시나리오`() {
        // 사용자가 application.yml에서 magicjar.candle.max-response-limit=3000으로 override한 경우.
        val props = ChartCandleProperties(maxResponseLimit = 3000)

        assertThat(props.maxResponseLimit).isEqualTo(3000)
    }
}
