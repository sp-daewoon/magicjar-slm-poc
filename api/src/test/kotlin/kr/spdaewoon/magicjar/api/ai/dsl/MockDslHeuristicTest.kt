package kr.spdaewoon.magicjar.api.ai.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Mock 룰 기반 변환기 fixture — `dsl-translate.md` few-shot 정합 (8 케이스).
 *
 * ADR 0026 사용자 트리거 helper의 mock 분기 결정론 보장 — Anthropic key 없는 개발 환경에서도 동일 응답.
 */
class MockDslHeuristicTest {
    @Test
    fun `RSI 과매도 — 14 30 미만`() {
        val dsl = MockDslHeuristic.translate("RSI 14가 30 미만일 때")
        assertEquals("rsi(14) < 30", dsl)
    }

    @Test
    fun `RSI 과매수 — 14 70 이상`() {
        val dsl = MockDslHeuristic.translate("RSI가 70 이상")
        assertEquals("rsi(14) > 70", dsl)
    }

    @Test
    fun `골든크로스 — 5일선이 20일선 상향 돌파`() {
        val dsl = MockDslHeuristic.translate("5일 이동평균이 20일 이동평균을 상향 돌파")
        assertEquals("ma(5) crosses_above ma(20)", dsl)
    }

    @Test
    fun `골든크로스 + 거래량 동반 돌파`() {
        val direct = MockDslHeuristic.translate("5일선이 20일선을 상향 돌파하고 거래량이 20일 평균의 1.5배")
        assertEquals("ma(5) crosses_above ma(20) AND volume > volume_ma(20) * 1.5", direct)
        val variant = MockDslHeuristic.translate("5일 이평이 20일 이평을 상향 돌파하고 거래량이 20일 평균의 1.5배")
        assertEquals("ma(5) crosses_above ma(20) AND volume > volume_ma(20) * 1.5", variant)
    }

    @Test
    fun `데드크로스`() {
        val dsl = MockDslHeuristic.translate("5일 이동평균이 20일 이동평균을 하향 돌파")
        assertEquals("ma(5) crosses_below ma(20)", dsl)
    }

    @Test
    fun `신고가 돌파`() {
        val dsl = MockDslHeuristic.translate("250일 신고가를 돌파")
        assertEquals("close breaks high(250)", dsl)
    }

    @Test
    fun `백분율 수익률`() {
        val dsl = MockDslHeuristic.translate("5일 수익률이 5% 이상")
        assertEquals("returns(5) > 5%", dsl)
    }

    @Test
    fun `볼린저밴드 하단 — 기본 파라미터`() {
        val dsl = MockDslHeuristic.translate("종가가 볼린저밴드 하단 아래")
        assertNotNull(dsl)
        assertEquals("close < bb_lower(20, 2)", dsl)
    }

    @Test
    fun `MACD 골든크로스`() {
        val dsl = MockDslHeuristic.translate("MACD가 시그널을 상향 돌파")
        assertEquals("macd(12,26,9) crosses_above macd_signal(12,26,9)", dsl)
    }

    @Test
    fun `매칭 실패 — 회장님이 좋아하는 종목`() {
        val dsl = MockDslHeuristic.translate("회장님이 좋아하는 종목")
        assertNull(dsl)
    }
}
