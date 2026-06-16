package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PostConstruct
import kr.spdaewoon.magicjar.consumer.order.HoldingPositionIndex
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 보유 포지션 VI 감시 구독 관리자 — H0UNMKO0.
 *
 * 정책:
 * - EXIT_MARKET: 구독 + VI 활성 시 시장가 자동청산.
 * - ALERT_ONLY: 구독 + VI 활성 알림만.
 * - HOLD: 구독하지 않음.
 *
 * 구독 취소는 "주문 발행"이 아니라 정책 대상 보유분이 0이 되는 순간에만 수행한다. 부분체결/거절/리컨실 전
 * 공백을 피하기 위해 [HoldingPositionIndex]가 권위 snapshot을 갱신한 뒤 diff로 subscribe/unsubscribe 한다.
 */
@Component
class ViMarketStatusSubscriptionRegistrar(
    private val holdingPositionIndex: HoldingPositionIndex,
    private val kisWsPort: KisWsPort,
    @Value("\${magicjar.autotrading.vi-status.enabled:true}")
    private val enabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(ViMarketStatusSubscriptionRegistrar::class.java)

    @PostConstruct
    fun bootstrap() {
        if (!enabled) return
        sync()
    }

    @Scheduled(fixedDelayString = "\${magicjar.autotrading.vi-status.refresh-ms:10000}", initialDelay = 20_000)
    fun sync() {
        if (!enabled) return
        val desired =
            holdingPositionIndex
                .holdingKeys()
                .asSequence()
                .filter { it.viActionPolicy != ViActionPolicy.HOLD }
                .map { it.code }
                .toSet()
        val current = kisWsPort.subscribedMarketStatusCodes()
        val toSubscribe = (desired - current).toList()
        val toUnsubscribe = (current - desired).toList()

        if (toSubscribe.isNotEmpty()) kisWsPort.subscribeMarketStatus(toSubscribe)
        if (toUnsubscribe.isNotEmpty()) kisWsPort.unsubscribeMarketStatus(toUnsubscribe)
        if (toSubscribe.isNotEmpty() || toUnsubscribe.isNotEmpty()) {
            log.info(
                "VI market-status subscription sync: desired={} subscribe={} unsubscribe={}",
                desired.size,
                toSubscribe,
                toUnsubscribe,
            )
        }
    }
}
