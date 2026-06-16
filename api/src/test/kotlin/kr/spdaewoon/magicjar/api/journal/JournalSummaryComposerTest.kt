package kr.spdaewoon.magicjar.api.journal

import kr.spdaewoon.magicjar.api.messaging.journal.JournalSummaryComposer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * JournalSummaryComposerTest — 5건 GREEN 의무 (spec §3-1 JournalSummaryComposerTest).
 *
 * 5 이벤트 타입 한국어 summary 합성 정합 검증.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class JournalSummaryComposerTest {
    private val sut = JournalSummaryComposer()

    @Test
    fun `ORDER_FILLED — 체결 한국어 summary 합성`() {
        val summary =
            sut.composeOrderFilled(
                name = "삼성전자",
                code = "005930",
                side = "BUY",
                qty = BigDecimal("100"),
                price = BigDecimal("75000"),
            )
        assertThat(summary).isEqualTo("체결: 삼성전자 매수 100주 @ 75,000원")
    }

    @Test
    fun `ORDER_REJECTED — 주문 거부 한국어 summary 합성`() {
        val summary =
            sut.composeOrderRejected(
                name = "카카오",
                code = "035720",
                side = "SELL",
                qty = BigDecimal("50"),
                reason = "POSITION_LIMIT",
            )
        assertThat(summary).contains("주문 거부")
        assertThat(summary).contains("카카오")
        assertThat(summary).contains("매도")
    }

    @Test
    fun `ORDER_CANCELLED — 주문 취소 한국어 summary 합성`() {
        val summary =
            sut.composeOrderCancelled(
                name = "NAVER",
                code = "035420",
                side = "BUY",
                qty = BigDecimal("10"),
            )
        assertThat(summary).contains("주문 취소")
        assertThat(summary).contains("NAVER")
        assertThat(summary).contains("매수")
    }

    @Test
    fun `SIGNAL_GENERATED — 시그널 한국어 summary 합성`() {
        val summary =
            sut.composeSignalGenerated(
                name = "삼성전자",
                code = "005930",
                side = "BUY",
                strength = 78,
            )
        assertThat(summary).isEqualTo("시그널: 삼성전자 매수 강도 78")
    }

    @Test
    fun `RISK_VIOLATION — 리스크 차단 한국어 summary 합성 (종목 nullable)`() {
        // 종목 있는 경우
        val summaryWithCode = sut.composeRiskViolation("POSITION_LIMIT", "005930")
        assertThat(summaryWithCode).contains("리스크 차단")
        assertThat(summaryWithCode).contains("005930")

        // 포트폴리오 전체 이벤트 (종목 null)
        val summaryNoCode = sut.composeRiskViolation("DAILY_LOSS", null)
        assertThat(summaryNoCode).contains("리스크 차단")
        assertThat(summaryNoCode).contains("일일 손실 한도 초과")
    }
}
