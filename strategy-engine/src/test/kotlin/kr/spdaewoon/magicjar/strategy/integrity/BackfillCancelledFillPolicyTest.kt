package kr.spdaewoon.magicjar.strategy.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * [BackfillCancelledFillPolicy] 단위 테스트 — CANCELLED 주문 KIS 실체결 누락 대조 (순수 함수 · ADR 0115 §2-P2-② 2026-06-10).
 *
 * G5 incident 재현: 매도 주문이 시스템상 CANCELLED·order_execution 0건인데 KIS 실체결 존재(취소-체결 race).
 * 규칙 LOCK: delta>0 면 terminal 무관 Record(terminal→preserveStatus) / delta≤0 Skip / 토글 off Skip.
 */
class BackfillCancelledFillPolicyTest {
    private fun input(
        kisTotal: Int,
        current: Int,
        terminal: Boolean,
        enabled: Boolean = true,
    ) = CancelledFillInput(
        kisTotalCcldQty = kisTotal,
        currentExecutedQty = current,
        terminal = terminal,
        enabled = enabled,
    )

    // === G5 재현 — CANCELLED 주문(terminal) 인데 KIS 11주 실체결·order_execution 0건 → 11주 기록 + status 보존 ===
    @Test
    fun `G5 — CANCELLED 11주 실체결 current0 → Record(delta11, preserveStatus true)`() {
        val decision = BackfillCancelledFillPolicy.decide(input(kisTotal = 11, current = 0, terminal = true))
        val record = assertInstanceOf(BackfillCancelledFillPolicy.Decision.Record::class.java, decision)
        assertEquals(11, record.deltaQuantity)
        // terminal — status·completed_at 보존(CANCELLED 유지). 체결만 기록.
        assertEquals(true, record.preserveStatus)
    }

    // === CANCELLED 부분 — 이미 일부 기록(3주) + KIS 11주 → delta 8주만 추가 기록 ===
    @Test
    fun `CANCELLED 부분 — current3 kisTotal11 → Record(delta8, preserveStatus true)`() {
        val decision = BackfillCancelledFillPolicy.decide(input(kisTotal = 11, current = 3, terminal = true))
        val record = assertInstanceOf(BackfillCancelledFillPolicy.Decision.Record::class.java, decision)
        assertEquals(8, record.deltaQuantity)
        assertEquals(true, record.preserveStatus)
    }

    // === non-terminal(ACK·PARTIALLY_FILLED) — delta>0 면 기록 + status sync 정상 경로(preserveStatus false) ===
    @Test
    fun `non-terminal — current0 kisTotal5 → Record(delta5, preserveStatus false)`() {
        val decision = BackfillCancelledFillPolicy.decide(input(kisTotal = 5, current = 0, terminal = false))
        val record = assertInstanceOf(BackfillCancelledFillPolicy.Decision.Record::class.java, decision)
        assertEquals(5, record.deltaQuantity)
        // non-terminal — 기존 status sync 정상 통과(PARTIALLY_FILLED→FILLED 전환 등).
        assertEquals(false, record.preserveStatus)
    }

    // === delta 0 — 이미 전량 반영(over-count 0) → Skip ===
    @Test
    fun `delta0 — current11 kisTotal11 → Skip(이미 반영)`() {
        val decision = BackfillCancelledFillPolicy.decide(input(kisTotal = 11, current = 11, terminal = true))
        assertEquals(BackfillCancelledFillPolicy.Decision.Skip, decision)
    }

    // === delta 음수 — KIS 누적 < 기록(이상 — 방어) → Skip(추가 기록 안 함) ===
    @Test
    fun `delta 음수 — current11 kisTotal5 → Skip`() {
        val decision = BackfillCancelledFillPolicy.decide(input(kisTotal = 5, current = 11, terminal = true))
        assertEquals(BackfillCancelledFillPolicy.Decision.Skip, decision)
    }

    // === 토글 off — terminal·delta>0 여도 현행 terminal skip 동작(회귀 안전) ===
    @Test
    fun `토글 off — enabled false 면 delta 무관 Skip(현행 동작)`() {
        val decision =
            BackfillCancelledFillPolicy.decide(input(kisTotal = 11, current = 0, terminal = true, enabled = false))
        assertEquals(BackfillCancelledFillPolicy.Decision.Skip, decision)
    }

    // === REJECTED·EXPIRED 도 terminal — KIS 실체결이면 동일하게 기록 + status 보존 ===
    @Test
    fun `terminal 일반 — REJECTED·EXPIRED 도 delta3 면 Record(preserveStatus true)`() {
        val decision = BackfillCancelledFillPolicy.decide(input(kisTotal = 3, current = 0, terminal = true))
        val record = assertInstanceOf(BackfillCancelledFillPolicy.Decision.Record::class.java, decision)
        assertEquals(3, record.deltaQuantity)
        assertEquals(true, record.preserveStatus)
    }
}
