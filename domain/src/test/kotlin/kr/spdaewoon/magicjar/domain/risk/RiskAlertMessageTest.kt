package kr.spdaewoon.magicjar.domain.risk

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class RiskAlertMessageTest {
    @Test
    fun `TRADE_FREQUENCY_DAY - 일별 거래 한도를 한국어 수식으로 설명한다`() {
        val alert =
            RiskAlertMessage.from(
                rule = RiskRule.TRADE_FREQUENCY_DAY,
                reason = "Strategy daily trade count 50 >= limit 50",
                context = mapOf("recentTradesDay" to 50, "limit" to 50),
                code = "005930",
            )

        alert.title shouldBe "D. 거래 빈도 한도 도달 - 일별 최대 거래"
        alert.message shouldContain "오늘 이 전략"
        alert.formula shouldBe "오늘 허용 주문 수 50회 >= 일별 최대 거래 50회/일"
        alert.setting shouldBe "D. 거래 빈도 한도 - 일별 최대 거래"
    }

    @Test
    fun `POSITION_LIMIT - 절대 한도와 비율 한도 중 작은 값 공식을 포함한다`() {
        val alert =
            RiskAlertMessage.from(
                rule = RiskRule.POSITION_LIMIT,
                reason = "Unit price 80000 exceeds remaining position budget 70000",
                context =
                    mapOf(
                        "unitCost" to "80000",
                        "budget" to "1000000",
                        "remainingBudget" to "70000",
                        "existingExposure" to "930000",
                        "absLimit" to "1000000",
                        "percentBudget" to "2000000",
                        "currentCapital" to "20000000",
                        "maxPositionPercent" to "10",
                    ),
                code = "005930",
            )

        alert.title shouldContain "A. 포지션 한도"
        alert.formula!! shouldContain "min(절대 한도 1,000,000원, 총 자산 20,000,000원 x 비율 한도 10% = 2,000,000원)"
        alert.formula!! shouldContain "신호 단가 80,000원 > 남은 종목 한도 70,000원"
    }

    @Test
    fun `MAX_CONCURRENT_POSITIONS - 보유 종목 수 단위를 종목으로 설명한다`() {
        val alert =
            RiskAlertMessage.from(
                rule = RiskRule.MAX_CONCURRENT_POSITIONS,
                reason = "Concurrent positions 5 >= limit 5",
                context = emptyMap(),
            )

        alert.title shouldBe "C. 동시 보유 종목 한도 도달"
        alert.formula shouldBe "현재 보유 종목 수 5종목 >= 최대 보유 종목 수 5종목"
    }

    @Test
    fun `SELL_QUANTITY_UNAVAILABLE - KIS 잔고 검증 실패는 KIS 정합성 메시지로 설명한다`() {
        val alert =
            RiskAlertMessage.from(
                rule = RiskRule.SELL_QUANTITY_UNAVAILABLE,
                reason = "SELL_QUANTITY_UNAVAILABLE:KIS_HOLDING_QTY_INSUFFICIENT",
                context =
                    mapOf(
                        "source" to "KIS_BALANCE",
                        "code" to "005930",
                        "kisHoldingQty" to 0,
                        "requestedQty" to 7,
                    ),
            )

        alert.title shouldBe "KIS 잔고 매도 가능 수량 부족"
        alert.message shouldContain "KIS 계좌 보유 수량"
        alert.formula shouldBe "KIS 보유 수량 0주 < 주문 예정 수량 7주"
    }

    @Test
    fun `SELL_QUANTITY_UNAVAILABLE - KIS 잔고 조회 실패는 실제 0주 부족처럼 설명하지 않는다`() {
        val alert =
            RiskAlertMessage.from(
                rule = RiskRule.SELL_QUANTITY_UNAVAILABLE,
                reason = "SELL_QUANTITY_UNAVAILABLE:KIS_BALANCE_LOOKUP_FAILED:EGW00201",
                context =
                    mapOf(
                        "source" to "KIS_BALANCE",
                        "code" to "000660",
                        "requestedQty" to 1,
                        "reasonCode" to "KIS_BALANCE_LOOKUP_FAILED:EGW00201",
                    ),
            )

        alert.title shouldBe "KIS 잔고 조회 실패 - 매도 주문 보류"
        alert.message shouldContain "KIS 잔고를 확인하지 못해"
        alert.formula shouldBe "KIS 잔고 조회 상태 = 실패, 주문 예정 수량 1주"
        alert.action shouldContain "실제 보유 수량 부족으로 확정된 알림은 아닙니다"
    }
}
