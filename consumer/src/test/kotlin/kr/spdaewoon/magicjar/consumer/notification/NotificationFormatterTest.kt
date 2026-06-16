package kr.spdaewoon.magicjar.consumer.notification

import kr.spdaewoon.magicjar.domain.notification.NotificationEventType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NotificationFormatter 단위 테스트 (spec §2-4 LOCK).
 *
 * 7 케이스 — 트리거별 메시지 포맷 검증:
 *  (1) ORDER_FILLED — 체결 메시지
 *  (2) ORDER_REJECTED — 거절 메시지
 *  (3) ORDER_CANCELLED — 취소 메시지
 *  (4) RISK_VIOLATION — 경고 + 즉시 조치
 *  (5) RECONCILE_DRIFT — 경보 + 즉시 조치
 *  (6) KILLSWITCH_ENGAGED — 경보 + 즉시 조치
 *  (7) RECONCILE_RUN — audit 메시지
 *
 * AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2).
 */
class NotificationFormatterTest {
    @Test
    fun `(1) ORDER_FILLED — 체결 메시지 포함 검증`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_FILLED,
                reason = "체결: [MOCK] 삼성전자(005930) 매수 10주 @ 75,000원",
                meta = emptyMap(),
            )
        assertThat(msg).contains("주문 체결")
        assertThat(msg).contains("1234****") // 마스킹
        assertThat(msg).contains("삼성전자(005930)")
        assertThat(msg).contains("75,000원")
        assertThat(msg).doesNotContain("즉시 조치")
    }

    @Test
    fun `(1-2) ORDER_FILLED — executedAt 기준 시각과 backfill 반영 경로 표시`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_FILLED,
                reason = "체결: [MOCK] 삼성전자(005930) 매도 1주 @ 353,000원",
                meta =
                    mapOf(
                        "executedAt" to "1780373478",
                        "fillSource" to "REST_BACKFILL",
                    ),
            )

        assertThat(msg).contains("[주문 체결]* 2026-06-02 13:11:18 KST")
        assertThat(msg).contains("체결시각: 2026-06-02 13:11:18 KST")
        assertThat(msg).contains("반영: KIS REST backfill")
    }

    @Test
    fun `(1-3) ORDER_FILLED — 자동 전략 주문은 전략명과 ID를 표시한다`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_FILLED,
                reason = "체결: [MOCK] SK하이닉스(000660) 매도 1주 @ 2,337,000원",
                meta =
                    mapOf(
                        "strategyId" to "43",
                        "strategyName" to "익절 전략",
                        "source" to "AUTO",
                    ),
            )

        assertThat(msg).contains("주문출처: 자동 전략 익절 전략 (#43)")
    }

    @Test
    fun `(1-3b) ORDER_FILLED — STOP_LOSS trigger는 자동 손절로 표시한다 (ADR 0115)`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_FILLED,
                reason = "체결: [MOCK] 삼성전자(005930) 매도 7주 @ 48,000원",
                meta =
                    mapOf(
                        "strategyId" to "8",
                        "strategyName" to "단타 전략",
                        "source" to "AUTO",
                        "orderTrigger" to "STOP_LOSS",
                    ),
            )

        assertThat(msg).contains("주문출처: 자동 손절 단타 전략 (#8)")
        assertThat(msg).doesNotContain("자동 전략")
    }

    @Test
    fun `(1-3c) ORDER_FILLED — AUTO_EXIT trigger는 자동 익절로 표시한다 (ADR 0115)`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_FILLED,
                reason = "체결: [MOCK] 삼성전자(005930) 매도 10주 @ 52,500원",
                meta =
                    mapOf(
                        "strategyId" to "8",
                        "source" to "AUTO",
                        "orderTrigger" to "AUTO_EXIT",
                    ),
            )

        assertThat(msg).contains("주문출처: 자동 익절")
    }

    @Test
    fun `(1-3d) ORDER_FILLED — EXTERNAL trigger는 외부 주문으로 표시한다 (ADR 0115)`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_FILLED,
                reason = "체결: [REAL] 삼성전자(005930) 매수 5주 @ 70,000원",
                meta =
                    mapOf(
                        "strategyId" to "0",
                        "source" to "EXTERNAL",
                        "orderTrigger" to "EXTERNAL",
                    ),
            )

        assertThat(msg).contains("주문출처: 외부 주문(HTS·MTS)")
    }

    @Test
    fun `(1-4) ORDER_FILLED — 수동 주문은 전략 대신 수동 주문으로 표시한다`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_FILLED,
                reason = "체결: [MOCK] 삼성전자(005930) 매수 1주 @ 70,000원",
                meta =
                    mapOf(
                        "strategyId" to "0",
                        "source" to "MANUAL",
                    ),
            )

        assertThat(msg).contains("주문출처: 수동 주문")
        assertThat(msg).doesNotContain("자동 전략")
    }

    @Test
    fun `(2) ORDER_REJECTED — 거절 메시지 + 마스킹`() {
        val msg =
            NotificationFormatter.format(
                userId = "9999",
                eventType = NotificationEventType.ORDER_REJECTED,
                reason = "주문 거절: 005930 BUY — KILLSWITCH_ON",
                meta = emptyMap(),
            )
        assertThat(msg).contains("주문 거절")
        assertThat(msg).contains("****") // 4자 미만도 마스킹
    }

    @Test
    fun `(2-2) ORDER_REJECTED — KILLSWITCH_ON 코드는 사람어 사유 1줄로 풀어준다`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_REJECTED,
                reason = "주문 거절: [REAL] 삼성전자(005930) 매수 — KILLSWITCH_ON",
                meta = mapOf("rejectReason" to "KILLSWITCH_ON"),
            )
        assertThat(msg).contains("주문 거절")
        assertThat(msg).contains("사유: KillSwitch가 켜져 있어")
    }

    @Test
    fun `(2-3) ORDER_REJECTED — REAL_GATE_DENIED는 조건을 포함해 풀어준다`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_REJECTED,
                reason = "주문 거절: [REAL] 삼성전자(005930) 매수 — REAL_GATE_DENIED:allowReal",
                meta = mapOf("rejectReason" to "REAL_GATE_DENIED:allowReal"),
            )
        assertThat(msg).contains("사유: 실계좌 이중 게이트")
        assertThat(msg).contains("조건: allowReal")
    }

    @Test
    fun `(2-4) ORDER_REJECTED — KIS rt_cd 코드 패턴은 KIS 거절 코드로 풀어준다`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_REJECTED,
                reason = "주문 거절: [REAL] 삼성전자(005930) 매수 — 40310000:초과 주문",
                meta = mapOf("rejectReason" to "40310000:초과 주문"),
            )
        assertThat(msg).contains("사유: KIS 거절 (코드 40310000)")
        assertThat(msg).contains("초과 주문")
    }

    @Test
    fun `(2-5) ORDER_REJECTED — 미등록 코드는 사유 줄 없이 기술 내용만 (graceful 회귀 보호)`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.ORDER_REJECTED,
                reason = "주문 거절: [REAL] 삼성전자(005930) 매수 — SOME_UNMAPPED",
                meta = mapOf("rejectReason" to "SOME_UNMAPPED"),
            )
        assertThat(msg).contains("주문 거절")
        assertThat(msg).doesNotContain("사유:")
    }

    @Test
    fun `(3) ORDER_CANCELLED — 취소 메시지`() {
        val msg =
            NotificationFormatter.format(
                userId = "U1",
                eventType = NotificationEventType.ORDER_CANCELLED,
                reason = "주문 취소: 005930 SELL (주문번호 K123)",
                meta = emptyMap(),
            )
        assertThat(msg).contains("주문 취소")
        assertThat(msg).contains("취소")
    }

    @Test
    fun `(4) RISK_VIOLATION — 경고 이모지 + 즉시 조치 포함`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.RISK_VIOLATION,
                reason = "리스크 위반: 005930 rule=MAX_LOSS_PER_TRADE",
                meta =
                    mapOf(
                        "alertTitle" to "D. 거래 빈도 한도 도달 - 일별 최대 거래",
                        "alertMessage" to "종목 005930 주문이 오늘 이 전략에서 허용된 주문 횟수를 모두 사용해 차단됐습니다.",
                        "alertFormula" to "오늘 허용 주문 수 50회 >= 일별 최대 거래 50회/일",
                        "alertSetting" to "D. 거래 빈도 한도 - 일별 최대 거래",
                        "alertAction" to "오늘 주문 횟수와 전략 실행 빈도를 확인하세요.",
                        "code" to "005930",
                        "instrumentName" to "삼성전자",
                    ),
            )
        assertThat(msg).contains("리스크 위반")
        assertThat(msg).contains("종목: 삼성전자(005930)")
        assertThat(msg).contains("내용: D. 거래 빈도 한도 도달 - 일별 최대 거래")
        assertThat(msg).contains("설명: 종목 삼성전자(005930)")
        assertThat(msg).contains("수식: 오늘 허용 주문 수 50회 >= 일별 최대 거래 50회/일")
        assertThat(msg).contains("설정: D. 거래 빈도 한도 - 일별 최대 거래")
        assertThat(msg).contains("즉시 조치")
        assertThat(msg).contains("오늘 주문 횟수와 전략 실행 빈도를 확인하세요.")
    }

    @Test
    fun `(4-2) RISK_VIOLATION — KIS 잔고 조회 실패 action은 전략 비활성화 문구로 뭉개지지 않는다`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.RISK_VIOLATION,
                reason = "리스크 차단: SK하이닉스(000660) KIS 잔고 조회 실패",
                meta =
                    mapOf(
                        "strategyId" to "44",
                        "strategyName" to "OB 익절",
                        "alertTitle" to "KIS 잔고 조회 실패 - 매도 주문 보류",
                        "alertMessage" to "종목 000660 KIS 잔고를 확인하지 못해 매도 주문을 내보내지 않았습니다.",
                        "alertFormula" to "KIS 잔고 조회 상태 = 실패, 주문 예정 수량 1주",
                        "alertSetting" to "KIS 잔고 정합성 가드",
                        "alertAction" to "KIS rate limit 또는 계좌 연결 상태를 확인하세요. 실제 보유 수량 부족으로 확정된 알림은 아닙니다.",
                        "code" to "000660",
                        "instrumentName" to "SK하이닉스",
                    ),
            )

        assertThat(msg).contains("전략: OB 익절 (#44)")
        assertThat(msg).contains("내용: KIS 잔고 조회 실패 - 매도 주문 보류")
        assertThat(msg).contains("즉시 조치: KIS rate limit")
        assertThat(msg).doesNotContain("전략 비활성화")
    }

    @Test
    fun `(5) RECONCILE_DRIFT — 경보 + 즉시 조치 (KillSwitch 발동 단정 제거 · 2026-06-05 fix)`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.RECONCILE_DRIFT,
                reason = "포지션 불일치: 005930 KIS=100주 내부=99주 diff=1",
                meta = emptyMap(),
            )
        assertThat(msg).contains("포지션 불일치")
        assertThat(msg).contains("즉시 조치")
        // 2026-06-05 fix — drift 검출 ≠ KillSwitch 발동. "OFF 토글 필요" 단정 대신 실제 보유 확인 + 자동 평가 안내.
        assertThat(msg).contains("KIS HTS 에서 실제 보유 수량 확인")
        assertThat(msg).contains("KillSwitch 는 반복 drift 시 자동 발동")
        // 발동을 단정하는 옛 문구는 더 이상 노출하지 않는다.
        assertThat(msg).doesNotContain("수동 KillSwitch OFF 토글 필요")
    }

    @Test
    fun `(6) KILLSWITCH_ENGAGED — 경보 + 즉시 재활성화 조치 포함`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.KILLSWITCH_ENGAGED,
                reason = "KillSwitch 발동: reason=POSITION_DRIFT triggeredBy=AUTO",
                meta = emptyMap(),
            )
        assertThat(msg).contains("KillSwitch 자동 발동")
        assertThat(msg).contains("즉시 조치")
        assertThat(msg).contains("재활성화")
    }

    @Test
    fun `(7) RECONCILE_RUN — audit 메시지 즉시 조치 없음`() {
        val msg =
            NotificationFormatter.format(
                userId = "12345678",
                eventType = NotificationEventType.RECONCILE_RUN,
                reason = "잔고 점검 완료: status=RECONCILED driftCount=0",
                meta = emptyMap(),
            )
        assertThat(msg).contains("잔고 점검")
        assertThat(msg).doesNotContain("즉시 조치")
    }
}
