package kr.spdaewoon.magicjar.consumer.risk

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.port.EntryQuotePort
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.strategy.ResolvedEntryPolicy
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.strategy.entry.EntryPriceDecision
import kr.spdaewoon.magicjar.strategy.entry.EntryPriceInput
import kr.spdaewoon.magicjar.strategy.entry.EntryPricePolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 진입(BUY) marketable limit 가격 산정 배선 — ADR 0114 §3-1·§6 (R2-C2a) · opt-in 게이트 hotfix(2026-06-08).
 *
 * ## 책임 경계 (SRP — 진보 페르소나 ADR 0087)
 * - 순수 계산은 [EntryPricePolicy](strategy-engine) 단일 함수가 책임진다(live·백테스트·리플레이 공유).
 * - 본 컴포넌트는 그 **호출 배선 + 적용 게이트**만 책임진다: 진입정책 대상 판정 → (대상이면) 전략의
 *   [kr.spdaewoon.magicjar.domain.strategy.EntryPolicy] + 전역 default(env) → [ResolvedEntryPolicy],
 *   [EntryQuotePort]로 호가 동기 read → [EntryPriceInput] 조립 → evaluate. (대상 아니면 호가 조회 0 + MARKET.)
 * - 주문 발행/스킵 audit은 호출자([SignalGeneratedKafkaListener.handleAllowed])가 결정([EntryOrderDecision] 소비).
 *
 * ## opt-in 게이트 (자본 경로 회귀 hotfix 2026-06-08 — D-1 장중 MOCK 거래 전면 중단 정정)
 * marketable-limit 진입은 **명시적으로 그 정책을 채택한 시그널에만** 적용한다. 적용 대상:
 * 1. **전략별 [EntryPolicy]가 명시 보유**(entry_policy JSONB 행) — 그 전략은 marketable-limit을 의도한 것이다. 또는
 * 2. **전역 스위치 [limitEnabled]=true** — entry_policy 없는 전략에도 marketable-limit을 전역 적용하겠다는 명시 opt-in.
 *
 * 둘 다 아니면(전략 entry_policy=null AND limitEnabled=false) → [EntryOrderDecision.Market](현행 MARKET 보존).
 * 과거 배선은 entry_policy=null이어도 globalDefault(marketable-limit)를 무조건 적용 → 호가 미가용(호가 WS 미구독
 * 종목 + REST ask1=null) 종목이 전부 [EntryOrderDecision.Skip] → BUY 0 → 포지션 0 → SELL 차단(회귀). 본 게이트로
 * "null entry_policy = 현행 MARKET 하위호환"(R1 도메인 주석)이 실제 배선과 정합한다.
 *
 * ## 시장가 fallback 금지는 marketable-limit **적용 대상에 한해** LOCK (ADR 0114 §3-1-5)
 * marketable-limit이 적용된 시그널은 호가 미가용·캡 초과 시 [EntryPriceDecision.Skip] → 주문 0(슬리피지 캡 자기 부정
 * 회피). 이는 사용자가 그 정책을 명시 채택했기 때문이다. **미채택 시그널은 애초에 marketable-limit이 아니므로**
 * 호가 조회 자체를 하지 않고(REST TPS 0) [EntryOrderDecision.Market]을 반환한다.
 *
 * ## 전역 default (env `magicjar.order.entry.*` — ADR 0114 §10-5)
 * marketable-limit 적용 대상일 때, 전략별 [EntryPolicy]가 null이거나 개별 필드 null이면 본 env default로 폴백한다
 * ([kr.spdaewoon.magicjar.domain.strategy.EntryPolicy.resolved]).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4)
 */
@Component
class EntryOrderPriceResolver(
    private val entryQuotePortProvider: ObjectProvider<EntryQuotePort>,
    @Value("\${magicjar.order.entry.max-slippage-pct:0.2}") private val defaultMaxSlippagePct: String = "0.2",
    @Value("\${magicjar.order.entry.timeout-sec:15}") private val defaultTimeoutSec: Int = 15,
    @Value("\${magicjar.order.entry.max-spread-pct:0.3}") private val defaultMaxSpreadPct: String = "0.3",
    // opt-in 게이트 — entry_policy 없는 전략에도 marketable-limit을 전역 적용할지(default false = 현행 MARKET 보존).
    @Value("\${magicjar.order.entry.limit-enabled:false}") private val limitEnabled: Boolean = false,
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(EntryOrderPriceResolver::class.java)

    private val globalDefault: ResolvedEntryPolicy by lazy {
        ResolvedEntryPolicy(
            maxEntrySlippagePct = parsePct(defaultMaxSlippagePct, ResolvedEntryPolicy.DEFAULTS.maxEntrySlippagePct),
            entryTimeoutSec = defaultTimeoutSec.takeIf { it > 0 } ?: ResolvedEntryPolicy.DEFAULTS.entryTimeoutSec,
            maxSpreadPct = parsePct(defaultMaxSpreadPct, ResolvedEntryPolicy.DEFAULTS.maxSpreadPct),
        )
    }

    /**
     * BUY 시그널의 진입 가격을 산정한다.
     *
     * marketable-limit **적용 대상**([isLimitApplicable])이면 호가 read → [EntryPricePolicy]로 LIMIT/Skip 판정.
     * 미적용이면 호가 조회 0 + [EntryOrderDecision.Market](현행 MARKET 보존 — 자본 경로 회귀 hotfix 2026-06-08).
     *
     * @param signal BUY 시그널(priceAtSignal = 신호가 = cap 산정 base, 추격 금지 §3-2).
     * @param strategy 시그널 발행 전략(entryPolicy override 보유 가능. null이면 전역 게이트로만 적용 여부 판정).
     * @return [EntryOrderDecision.Market]면 시장가 발행, [EntryOrderDecision.Enter]면 LIMIT 발행,
     *   [EntryOrderDecision.Skip]이면 주문 0 + audit.
     */
    fun resolve(
        signal: Signal,
        strategy: Strategy?,
    ): EntryOrderDecision {
        val entryPolicy = strategy?.definition?.entryPolicy

        // opt-in 게이트 — 전략 entry_policy 명시 OR 전역 limit-enabled 일 때만 marketable-limit 적용.
        // 미적용이면 호가 조회 자체를 하지 않고(REST TPS 0) MARKET 반환(현행 보존 · NO_QUOTE 스킵 0).
        if (entryPolicy == null && !limitEnabled) {
            log.debug(
                "BUY 진입 MARKET (marketable-limit 미적용 — entry_policy 없음 + limit-enabled=false): code={} signalId={}",
                signal.code,
                signal.id,
            )
            return EntryOrderDecision.Market
        }

        val resolved = entryPolicy?.resolved(globalDefault) ?: globalDefault

        val quotePort = entryQuotePortProvider.getIfAvailable()
        val quote =
            if (quotePort == null) {
                log.warn(
                    "EntryQuotePort bean 부재 — 진입 호가 미가용 → NoQuote skip: code={} signalId={}",
                    signal.code,
                    signal.id,
                )
                null
            } else {
                runCatching { quotePort.latestQuote(signal.code) }
                    .onFailure { e ->
                        log.warn("EntryQuotePort.latestQuote 실패 — NoQuote skip: code={} err={}", signal.code, e.message)
                    }.getOrNull()
            }

        val input =
            EntryPriceInput(
                priceAtSignal = signal.priceAtSignal,
                ask1 = quote?.ask1,
                bid1 = quote?.bid1,
                askQty1 = quote?.askQty1,
                maxEntrySlippagePct = resolved.maxEntrySlippagePct,
                maxSpreadPct = resolved.maxSpreadPct,
                quoteAsOf = quote?.asOf,
                evalAt = clock.now(),
            )

        return when (val decision = EntryPricePolicy.evaluate(input)) {
            is EntryPriceDecision.Enter ->
                EntryOrderDecision.Enter(
                    limitPrice = decision.limitPrice,
                    entryTimeoutSec = resolved.entryTimeoutSec,
                )
            is EntryPriceDecision.Skip ->
                EntryOrderDecision.Skip(
                    auditCode = decision.auditCode,
                    ask1 = quote?.ask1,
                    bid1 = quote?.bid1,
                    quoteAsOf = quote?.asOf,
                )
        }
    }

    private fun parsePct(
        raw: String,
        fallback: BigDecimal,
    ): BigDecimal = raw.trim().toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO } ?: fallback
}

/**
 * 진입 가격 산정 결과 — sealed(MARKET 발행 vs LIMIT 발행 vs 스킵 audit 명확 분기 · ADR 0114 §6-1).
 *
 * [EntryPriceDecision](strategy-engine)의 도메인 결정을 **배선 관점**(timeout·호가 메타 포함)으로 감싼 consumer 전용 표현이다.
 */
sealed interface EntryOrderDecision {
    /**
     * 진입 — priceType=MARKET 발행(현행 보존 · 자본 경로 회귀 hotfix 2026-06-08).
     *
     * marketable-limit 미적용 시그널(전략 entry_policy 없음 + 전역 limit-enabled=false)의 진입 경로다.
     * 호가 조회 0(REST TPS 0) — entry_policy/limit-enabled로 명시 opt-in한 시그널만 marketable-limit([Enter]/[Skip]).
     */
    data object Market : EntryOrderDecision

    /** 진입 — priceType=LIMIT + limitPrice 발행. entryTimeoutSec는 미체결 취소 워커가 사용(§3-2). */
    data class Enter(
        val limitPrice: BigDecimal,
        val entryTimeoutSec: Int,
    ) : EntryOrderDecision

    /** 진입 스킵 — 주문 0 + audit(auditCode = SIGNAL_SKIPPED_*). 시장가 fallback 0(marketable-limit 적용 대상에 한해). */
    data class Skip(
        val auditCode: String,
        val ask1: BigDecimal?,
        val bid1: BigDecimal?,
        val quoteAsOf: Instant?,
    ) : EntryOrderDecision
}
