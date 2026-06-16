package kr.spdaewoon.magicjar.consumer.account

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.order.AccountBalanceChangedKafkaProducer
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `account.balance.changed.v1` 이벤트를 account availability refresh 요청으로 변환한다.
 *
 * 같은 topic을 api 모듈은 STOMP invalidation 용도로 읽고, consumer는 Redis snapshot refresh trigger 로 읽는다.
 * 실제 KIS 호출은 [AccountAvailabilityRefreshService]가 debounce/coalesce 후 수행한다.
 */
@Component
class AccountAvailabilityRefreshListener(
    private val objectMapper: ObjectMapper,
    private val refreshService: AccountAvailabilityRefreshService,
    private val accountSnapshotCoordinatorProvider: ObjectProvider<AccountSnapshotCoordinator>? = null,
) {
    private val log = LoggerFactory.getLogger(AccountAvailabilityRefreshListener::class.java)

    companion object {
        const val GROUP = "magicjar-consumer-account-availability"
    }

    @KafkaListener(
        topics = [AccountBalanceChangedKafkaProducer.TOPIC],
        groupId = GROUP,
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun onAccountBalanceChanged(record: ConsumerRecord<String, String>) {
        val payload =
            try {
                objectMapper.readValue(record.value(), Map::class.java)
            } catch (e: Exception) {
                log.warn(
                    "account availability refresh event parse failed — skip: offset={} err={}",
                    record.offset(),
                    e.message,
                )
                return
            }
        val userId = payload["userId"]?.toString()
        val mode = payload["accountType"]?.toString()?.let { runCatching { TradingMode.valueOf(it) }.getOrNull() }
        if (userId.isNullOrBlank() || mode == null) {
            log.warn("account availability refresh event invalid — skip: offset={} payload={}", record.offset(), payload)
            return
        }
        val reason = payload["reason"]?.toString() ?: "ACCOUNT_BALANCE_CHANGED"
        val triggerKisOrderNo = payload["triggerKisOrderNo"]?.toString()
        accountSnapshotCoordinatorProvider?.getIfAvailable()?.invalidate(
            userId = userId,
            mode = mode,
            reason = "ACCOUNT_BALANCE_CHANGED:$reason",
            triggerKisOrderNo = triggerKisOrderNo,
        )
        refreshService.requestRefresh(
            userId = userId,
            mode = mode,
            reason = reason,
        )
    }
}
