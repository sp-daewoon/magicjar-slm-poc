package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * ACTIVE/BUY_BLOCKED 전략의 대상 종목 WS 구독 보강.
 *
 * 전략 목록에서 ACTIVE로 전환된 사용자 전략은 DB의 strategy.codes 배열 전체가 실시간 평가 대상이다.
 * API가 즉시 realtime demand를 발행하지만, consumer 재시작·Kafka 일시 실패·TTL 만료에 대비해 이 registrar가
 * active 전략을 주기적으로 읽고 동일 ownerId(bare `<id>`)로 갱신한다.
 *
 * ownerId는 bare id(예: `86`)다 — owner enum prefix(`strategy:`)는 스냅샷 직렬화([RealtimeSubscriptionRouter.snapshotDemands]·
 * [OrderbookSubscriptionRouter.snapshotDemands])에서 1회만 부여한다(이중 prefix `strategy:strategy:86` 회피).
 */
@Component
class ActiveStrategyDemandRegistrar(
    private val activeStrategyCodeProvider: ActiveStrategyCodeProvider,
    private val realtimeSubscriptionRouter: RealtimeSubscriptionRouter,
    private val orderbookSubscriptionRouter: OrderbookSubscriptionRouter,
    @Value("\${magicjar.autotrading.strategy-watch.enabled:true}")
    private val enabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(ActiveStrategyDemandRegistrar::class.java)
    private val registeredByStrategy: MutableMap<Long, Set<String>> = linkedMapOf()

    @PostConstruct
    fun bootstrap() {
        if (!enabled) return
        refreshActiveStrategyDemands()
    }

    @Scheduled(
        fixedDelayString = "\${magicjar.autotrading.strategy-watch.refresh-delay-ms:60000}",
        initialDelay = 20_000,
    )
    fun refreshActiveStrategyDemands() {
        if (!enabled) return

        val nextByStrategy = activeStrategyCodeProvider.activeCodesByStrategy()

        nextByStrategy.forEach { (strategyId, codes) ->
            codes.forEach { code ->
                runCatching {
                    realtimeSubscriptionRouter.request(
                        owner = RealtimeDemandOwner.STRATEGY,
                        ownerId = ownerId(strategyId),
                        code = code,
                        priority = RealtimeDemandOwner.STRATEGY.defaultPriority,
                        ttlSeconds = STRATEGY_DEMAND_TTL_SECONDS,
                    )
                    // F11 — 체결가(H0UNCNT0) demand와 대칭으로 호가(H0UNASP0) 구독도 보강한다.
                    // marketable limit 진입가(EntryQuotePort)가 ask1/bid1을 요구하므로 호가 WS 공급이 필수.
                    // 멱등 register(같은 ownerId 반복 호출 무해) — refresh 주기마다 갱신.
                    orderbookSubscriptionRouter.onStrategyActivated(code = code, ownerId = ownerId(strategyId))
                }.onFailure { e ->
                    log.warn("ACTIVE strategy demand request 실패: strategyId={} code={} err={}", strategyId, code, e.message)
                }
            }
        }

        registeredByStrategy.forEach { (strategyId, previousCodes) ->
            val nextCodes = nextByStrategy[strategyId].orEmpty()
            (previousCodes - nextCodes).forEach { code ->
                runCatching {
                    realtimeSubscriptionRouter.release(
                        owner = RealtimeDemandOwner.STRATEGY,
                        ownerId = ownerId(strategyId),
                        code = code,
                    )
                    // F11 — 호가 구독도 대칭 release (다른 전략·DETAIL demand가 남으면 WS 유지).
                    orderbookSubscriptionRouter.onStrategyDeactivated(code = code, ownerId = ownerId(strategyId))
                }.onFailure { e ->
                    log.warn("ACTIVE strategy demand release 실패: strategyId={} code={} err={}", strategyId, code, e.message)
                }
            }
        }

        registeredByStrategy.clear()
        registeredByStrategy.putAll(nextByStrategy)
        if (nextByStrategy.isNotEmpty()) {
            log.debug(
                "ACTIVE strategy demand refresh: strategies={} codes={}",
                nextByStrategy.size,
                nextByStrategy.values.sumOf { it.size },
            )
        }
    }

    // bare ownerId — prefix(`strategy:`)는 스냅샷 직렬화에서 1회 부여(이중 prefix 회피).
    // request/release/onStrategyActivated/onStrategyDeactivated 모두 본 함수를 쓰므로 멱등 매칭 키 동일.
    private fun ownerId(strategyId: Long): String = "$strategyId"

    private companion object {
        const val STRATEGY_DEMAND_TTL_SECONDS = 180L
    }
}
