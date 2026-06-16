package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.strategy.entry.EntryPricePolicy
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * tick 기반 익절 안전망 watcher — canary D2 hotfix B (손절 [PositionStopLossTickWatcher] 대칭 격상).
 *
 * ## 왜 독립 안전망인가 (D2 라이브 결함 — 익절만 시그널 의존이던 구조적 공백)
 * 현행 익절 경로는 ① BUY 체결 직후 [AutoExitOrderCoordinator] 가 익절 LIMIT 1회 선주문 + ② 이후
 * **SELL 시그널(DSL exit 조건) 처리 시에만** "open auto-tp 없으면 재발행"이었다. 그 LIMIT 이 **당일 만료**된
 * 뒤(KIS 국내주식 LIMIT 은 당일 유효) 다음 날 가격이 익절가(+2%)를 훌쩍 넘어 **+11% 급등**하는 동안에도
 * DSL exit(예: close<ma10)이 참이 되지 않으면 **익절이 영영 안 나가는 공백**이 있었다(09:29 매도는 가격이
 * 일시 꺾여 SELL 시그널이 떠준 운). 손절은 이미 [PositionStopLossTickWatcher] 가 tick 으로 감시하는데
 * **익절만 시그널 의존**이라 비대칭이었다.
 *
 * 본 watcher 는 `market.tick.v1` 을 직접 listen 해 그 4중 게이트(readiness·market-hours·전략 status·1m 캔들
 * 마감)를 **우회**하는 익절 안전망이다 — tick ≥ 익절가면 즉시 MARKET SELL.
 *
 * ## 손절 watcher 와의 대칭/차이 (의도적 SRP 분리 — 형제 신설)
 * 손절 평가 방향(`tick ≤ 손절가`)과 발사 키 의미가 달라 한 클래스에 분기를 욱여넣으면 OCP·SRP 위반이라
 * 형제 클래스로 분리했다. 인메모리 hit-only 평가([HoldingPositionIndex])·open SELL 가드
 * ([RestingSellOrderCanceller])·position 권위 잔량 가드는 **그대로 재사용**(DRY).
 *
 * ## 체결 직후 TP LIMIT 과의 공존 (중복 매도 방지 — 핵심)
 * [AutoExitOrderCoordinator] 가 건 `auto-tp:` open SELL LIMIT 이 **살아 있으면 watcher 는 발사하지 않는다**.
 * [RestingSellOrderCanceller.cancelRestingSells] 에 `excludeIdempotencyPrefix = "auto-tp"` 를 줘 open auto-tp 가
 * 하나라도 있으면 [RestingSellOrderCanceller.Result.OwnDuplicate](canProceed=false)로 미발사한다.
 * LIMIT 이 없고(만료 등) tick 이 익절가 이상이면 그때 watcher 가 MARKET SELL — 공백을 메운다.
 *
 * ## 멱등 — F1 고정 해시 함정 회피 ([AutoOrderIdempotencyKeys.takeProfitTick])
 * 키 = `auto-tpt:<digest(userId·strategyId·code·qty·**kstDate**)>`. 손절의 (qty·updatedAtEpoch) signature 와 달리
 * **KST 당일 날짜**를 섞어 하루 1회 발사로 한정하되, 날이 바뀌면(또는 수량 변동) 자연 재발사된다(REJECTED 후
 * 영구 차단 0 — 어제 F1 evidence 회피). stop-loss 키는 본 PR 에서 건드리지 않는다.
 *
 * ## env
 * `magicjar.autotrading.take-profit.tick-watch-enabled`(`MAGICJAR_TAKE_PROFIT_TICK_WATCHER_ENABLED`, default true).
 * false 면 no-op — 현행(시그널 의존 익절)으로 회귀.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4)
 */
@Component
class PositionTakeProfitTickWatcher(
    private val objectMapper: ObjectMapper,
    private val holdingPositionIndex: HoldingPositionIndex,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val restingSellOrderCanceller: RestingSellOrderCanceller,
    private val orderIntentKafkaProducer: OrderIntentKafkaProducer,
    private val positionJpaRepository: PositionJpaRepository,
    @Value("\${magicjar.autotrading.take-profit.tick-watch-enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
    // KST 날짜 차원 idempotency 키 산정용. 테스트는 고정 Clock 주입.
    private val clock: Clock = Clock.system(KST),
) {
    private val log = LoggerFactory.getLogger(PositionTakeProfitTickWatcher::class.java)

    @KafkaListener(topics = [KafkaTopics.Market.TICK], groupId = GROUP)
    fun onTick(record: ConsumerRecord<String, String>) {
        if (!enabled) return
        val tick = parse(record) ?: return
        if (tick.stale || tick.price <= BigDecimal.ZERO) return

        // ★ 인메모리 hit-only — 보유 0 종목은 즉시 종료(DB 조회 0 — 손절 watcher 와 동일 hot-path 비용).
        val held = holdingPositionIndex.heldBy(tick.code)
        if (held.isEmpty()) return

        held.forEach { position ->
            runCatching { evaluate(position, tick.price) }
                .onFailure { e ->
                    log.warn(
                        "take-profit tick 평가 실패(다음 tick 재평가): userId={} strategyId={} code={} err={}",
                        position.userId,
                        position.strategyId,
                        position.code,
                        e.message,
                    )
                }
        }
    }

    internal fun evaluate(
        position: HoldingPositionIndex.HeldPosition,
        tickPrice: BigDecimal,
    ) {
        val takeProfitPct = position.takeProfitPct?.takeIf { it > BigDecimal.ZERO } ?: return
        val targetPrice = takeProfitPriceOf(position.avgPrice, takeProfitPct) ?: return
        // 익절가 미도달 — tick 이 목표가 아래면 발사 0(손절과 부등호 방향 반대).
        if (tickPrice < targetPrice) return

        // position(KIS 권위) 잔량 확인 — 유령(strategy_position>0 인데 position==0) 익절 발사 차단(손절 watcher 정합).
        if (!hasAuthorityHolding(position)) {
            log.warn(
                "take-profit skip(유령 포지션 — position 권위 잔량 0): userId={} strategyId={} code={} ledgerQty={}",
                position.userId,
                position.strategyId,
                position.code,
                position.quantity,
            )
            return
        }

        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        if (orderRepo == null) {
            log.error(
                "take-profit fail-closed: OrderRequestRepository 미합류 — 익절 보류 userId={} strategyId={} code={}",
                position.userId,
                position.strategyId,
                position.code,
            )
            return
        }

        // ★ 체결 직후 TP LIMIT 공존 가드 — open `auto-tp:` LIMIT 이 살아 있으면 OwnDuplicate(canProceed=false)로 미발사.
        // (중복 매도 방지 — LIMIT 이 먼저 익절을 보장 중이면 tick MARKET 은 발사하지 않는다.)
        // 그 외 같은 종목 open SELL(타전략·수동)은 RestingSellOrderCanceller 의 fail-closed 가드가 처리.
        val cancelResult =
            restingSellOrderCanceller.cancelRestingSells(
                orderRepo = orderRepo,
                userId = position.userId,
                strategyId = position.strategyId,
                code = position.code,
                excludeIdempotencyPrefix = AutoOrderIdempotencyKeys.PREFIX_TAKE_PROFIT,
            )
        if (!cancelResult.canProceed) return

        val idempotencyKey =
            AutoOrderIdempotencyKeys.takeProfitTick(
                position.userId,
                position.strategyId,
                position.code,
                position.quantity,
                LocalDate.now(clock).format(KST_DATE),
            )

        orderIntentKafkaProducer.publishExitIntent(
            userId = position.userId,
            strategyId = position.strategyId,
            code = position.code,
            intervalCode = "1m",
            side = OrderSide.SELL,
            priceType = OrderPriceType.MARKET,
            quantity = position.quantity,
            limitPrice = null,
            priceAtSignal = tickPrice,
            idempotencyKey = idempotencyKey,
            // ADR 0115 §2-P2-⑥ — tick 익절(전량): 발사 출처 AUTO_EXIT + 유발 근거 = 청산 대상 전략 id.
            // (전량 익절이므로 PARTIAL_TAKE_PROFIT 아닌 AUTO_EXIT — 코디네이터 LIMIT 선주문과 동일 분류 정합.)
            orderTrigger = OrderTrigger.AUTO_EXIT,
            triggerRefId = position.strategyId,
        )
        log.warn(
            "take-profit MARKET intent (tick watcher): userId={} strategyId={} code={} tick={} targetPrice={} qty={} idemKey={}",
            position.userId,
            position.strategyId,
            position.code,
            tickPrice,
            targetPrice,
            position.quantity,
            idempotencyKey,
        )
    }

    /**
     * position(KIS 권위) 잔량 > 0 확인 — 손절 watcher 와 동일 가드(유령 포지션 발사 차단).
     * source(REAL/MOCK) 차원 조회로 MOCK·REAL 행 공존(UQ=(user,code,source)) 시 단일 결과 보장.
     * 조회 실패 시 fail-safe true(익절 안전망 우선 — 조회 결함으로 익절을 막지 않음).
     */
    private fun hasAuthorityHolding(position: HoldingPositionIndex.HeldPosition): Boolean =
        runCatching {
            val source = RuntimeModeParser.tradingModeOrMock(magicjarMode).name
            (positionJpaRepository.findByUserIdAndCodeAndSource(position.userId, position.code, source)?.quantity ?: 0) > 0
        }.getOrElse { e ->
            log.warn(
                "take-profit position 권위 조회 실패 — fail-safe 발사 진행: userId={} code={} err={}",
                position.userId,
                position.code,
                e.message,
            )
            true
        }

    private fun parse(record: ConsumerRecord<String, String>): TickPayload? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val raw = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            TickPayload(
                code = raw["code"]?.toString() ?: return null,
                price = raw.decimalOrNull("price") ?: return null,
                stale = raw.booleanOrFalse("stale"),
            )
        } catch (e: Exception) {
            log.warn("PositionTakeProfitTickWatcher tick parse skip: offset={} err={}", record.offset(), e.message)
            null
        }
    }

    private fun Map<String, Any?>.decimalOrNull(key: String): BigDecimal? =
        when (val raw = this[key]) {
            is Number -> BigDecimal.valueOf(raw.toDouble())
            is String -> raw.toBigDecimalOrNull()
            else -> null
        }

    private fun Map<String, Any?>.booleanOrFalse(key: String): Boolean =
        when (val raw = this[key]) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }

    private data class TickPayload(
        val code: String,
        val price: BigDecimal,
        val stale: Boolean,
    )

    companion object {
        const val GROUP = "magicjar-consumer-position-take-profit"
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val KST_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * 익절가 = 평단 × (1 + takeProfitPct/100) → KRX 호가단위 내림 (R1 [EntryPricePolicy.takeProfitLimitPrice]
         * 단일 출처 — 체결 직후 LIMIT 선주문과 동일 산정으로 일관성 LOCK). 평단/pct 비정상(≤0)이면 null(평가 skip).
         */
        fun takeProfitPriceOf(
            avgPrice: BigDecimal,
            takeProfitPct: BigDecimal,
        ): BigDecimal? {
            if (avgPrice <= BigDecimal.ZERO || takeProfitPct <= BigDecimal.ZERO) return null
            return EntryPricePolicy.takeProfitLimitPrice(avgPrice, takeProfitPct)
        }
    }
}
