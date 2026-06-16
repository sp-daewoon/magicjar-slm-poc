package kr.spdaewoon.magicjar.strategy.entry

import kotlinx.datetime.Instant
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 진입(BUY) 가격 산정 단일 순수 함수 — ADR 0114 §3-1·§6.
 *
 * BUY 시그널이 RiskGateway ALLOWED를 통과하면 marketable limit 진입가를 산정한다(시장가 미발행).
 * live(consumer `RealtimeSignalGenerator` BUY 경로)·백테스트(ADR 0056 fill model)·리플레이(ADR 0107)가
 * **이 단일 함수**를 호출 → "리플레이에선 스킵됐는데 라이브는 진입" 의심 원천 차단([ScaleInExitCalculator] 선례 1:1).
 *
 * ## 순수성 (LOCK — ScaleInExitCalculator §순수성 1:1)
 * - Spring·JPA·Jackson·Clock·IO 의존 0. 입력([EntryPriceInput]) → 출력([EntryPriceDecision]) 결정론.
 * - 현재 시각·호가 신선도 등 외부 상태는 **입력으로 주입**한다(호출자 책임). 함수 내부에서 조회하지 않는다.
 * - 동일 입력 → 동일 출력(결정론 우선 — 백테스트/리플레이 재현성).
 * - AI 호출 0 (룰 기반 자본 흐름 순수 함수 — ADR 0023 §2-4 정합).
 *
 * ## 판정 순서 (ADR 0114 §3-1 LOCK)
 * 1. **호가 가용성** — `ask1`/`bid1` 미가용(null·≤0) → [EntryPriceDecision.Skip.NoQuote]. **시장가 fallback 절대 금지**(슬리피지 캡 자기 부정 §3-1-5).
 * 2. **호가 stale** — `quoteAsOf`가 없거나 `evalAt - quoteAsOf > maxQuoteAgeSec` → [EntryPriceDecision.Skip.QuoteStale]. (U-3 결정: 입력 임계 주입 — default [DEFAULT_MAX_QUOTE_AGE_SEC] 보수 3초.)
 * 3. **스프레드 게이트** — `(ask1 - bid1)/mid × 100 > maxSpreadPct` → [EntryPriceDecision.Skip.SpreadTooWide].
 * 4. **슬리피지 캡** — `cap = priceAtSignal × (1 + maxEntrySlippagePct/100)`. `ask1 > cap` → [EntryPriceDecision.Skip.PriceCapExceeded].
 * 5. **Enter** — `limitPrice = ask1`(상한 이내 marketable limit). ask1은 거래소 정렬 가격이라 그대로 사용.
 *
 * ## 재시도 기준가 불변 (ADR 0114 §3-2)
 * 재시도(attempt > 0)도 `priceAtSignal`은 원 신호 가격을 그대로 주입해야 한다(현재가 재산정 = 추격 금지).
 * 본 함수는 `priceAtSignal`만 cap 산정에 쓰므로, 호출자가 동일 `priceAtSignal`을 주입하면 cap·판정이 불변이다.
 *
 * ## 호가 잔량(thin liquidity) — R2 위임 (ADR 0114 §9 U-2)
 * `askQty1` 약함 임계(절대값 vs 주문 수량 대비 비율)는 R2 결정 의제다. R1 계약은 `askQty1`을 입력으로
 * **받아두기만** 하고(시그니처 안정), 잔량 게이트는 R2가 활성화한다([EntryPriceDecision.Skip.ThinLiquidity]는 예약).
 */
object EntryPricePolicy {
    private val ZERO: BigDecimal = BigDecimal.ZERO
    private val HUNDRED: BigDecimal = BigDecimal("100")

    /**
     * U-3 결정 — 호가 stale 판정 기본 임계(초). ADR 0114 §9 U-3 "tick 기반 — 수 초" 보수 default.
     * 호가는 KIS WS H0STASP0 tick으로 갱신되므로 3초 이상 정체면 stale로 본다(진입 스킵 — 시장가 fallback 0).
     * R2가 env로 조정 가능하나 R1 default는 보수 3초로 LOCK한다.
     */
    const val DEFAULT_MAX_QUOTE_AGE_SEC: Long = 3

    /**
     * 진입 가격 산정 단일 진입점. 판정 순서(가용성 → stale → 스프레드 → 캡 → Enter)로 평가한다.
     */
    fun evaluate(input: EntryPriceInput): EntryPriceDecision {
        val ask1 = input.ask1
        val bid1 = input.bid1

        // === 1. 호가 가용성 — 미가용이면 진입 스킵 (시장가 fallback 절대 금지) ===
        if (ask1 == null || ask1 <= ZERO || bid1 == null || bid1 <= ZERO) {
            return EntryPriceDecision.Skip.NoQuote
        }

        // === 2. 호가 stale — quoteAsOf 미가용 또는 임계 초과 경과면 스킵 ===
        val quoteAsOf = input.quoteAsOf ?: return EntryPriceDecision.Skip.QuoteStale
        val ageSec = input.evalAt.epochSeconds - quoteAsOf.epochSeconds
        // 미래 timestamp(음수 age)는 시계 skew로 보고 통과(보수: stale 아님). 임계 초과만 stale.
        if (ageSec > input.maxQuoteAgeSec) {
            return EntryPriceDecision.Skip.QuoteStale
        }

        // === 3. 스프레드 게이트 — (ask1-bid1)/mid × 100 > maxSpreadPct 면 스킵 ===
        val mid = ask1.add(bid1).divide(BigDecimal(2), 8, RoundingMode.HALF_UP)
        if (mid > ZERO) {
            val spreadPct =
                ask1
                    .subtract(bid1)
                    .divide(mid, 8, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
            if (spreadPct > input.maxSpreadPct) {
                return EntryPriceDecision.Skip.SpreadTooWide
            }
        }

        // === 4. 슬리피지 캡 — ask1 > cap 면 스킵 (시장가 fallback 금지) ===
        // cap = priceAtSignal × (1 + maxEntrySlippagePct/100). 호가단위 내림으로 cap을 거래소 정렬가로 보정.
        val rawCap =
            input.priceAtSignal.multiply(
                BigDecimal.ONE.add(input.maxEntrySlippagePct.divide(HUNDRED, 10, RoundingMode.HALF_UP)),
            )
        val cap = KrxTickSize.floorToTick(rawCap)
        if (ask1 > cap) {
            return EntryPriceDecision.Skip.PriceCapExceeded
        }

        // === 5. Enter — ask1 ≤ cap. limitPrice = ask1 (상한 이내 marketable limit, 거래소 정렬가) ===
        return EntryPriceDecision.Enter(limitPrice = ask1)
    }

    /**
     * 익절 LIMIT 가격 = 평단 × (1 + profitPct/100) → KRX 호가단위 정렬(SELL이므로 내림 — 보수적 체결).
     *
     * ADR 0114 §3-3: 익절(PartialExit 포함)은 LIMIT, 기준은 **트리거 가격**(평단 기준 결정론).
     * [ScaleInExitCalculator]는 청산 **여부·수량**을 판정하고, 본 헬퍼는 그 익절의 **LIMIT 가격**을 산정한다
     * (책임 경계 — 중복 계산 금지). 손절(StopLoss)은 MARKET이라 가격 산정 대상이 아니다(§3-3).
     *
     * @param avgPrice 평단(StrategyPosition.avgPrice — 양수 전제, 호출부 가드)
     * @param triggerProfitPct 익절 트리거 수익률%(양수 — PartialExit.triggerProfitPct 또는 takeProfitPct)
     * @return 호가단위 정렬된 익절 LIMIT 가격
     */
    fun takeProfitLimitPrice(
        avgPrice: BigDecimal,
        triggerProfitPct: BigDecimal,
    ): BigDecimal {
        require(avgPrice > ZERO) { "avgPrice must be > 0: $avgPrice" }
        require(triggerProfitPct >= ZERO) { "triggerProfitPct must be >= 0: $triggerProfitPct" }
        val raw =
            avgPrice.multiply(
                BigDecimal.ONE.add(triggerProfitPct.divide(HUNDRED, 10, RoundingMode.HALF_UP)),
            )
        // SELL LIMIT은 내림 정렬 — 호가단위 위로 올리면 체결 확률이 낮아진다(보수적 체결 우선).
        return KrxTickSize.floorToTick(raw)
    }
}

/**
 * [EntryPricePolicy.evaluate] 입력 — 모든 외부 상태를 명시 주입(순수성 LOCK · [ScaleInExitInput] 패턴 정합).
 *
 * @param priceAtSignal 원 신호 기준가(추격 금지 — 재시도도 이 값 고정 §3-2). cap 산정 base. 양수 전제.
 * @param ask1 매도1호가(null/≤0이면 진입 스킵 — 시장가 fallback 절대 금지).
 * @param bid1 매수1호가(스프레드 계산용. null/≤0이면 진입 스킵).
 * @param askQty1 매도1호가 잔량(R2 thin-liquidity 게이트 입력 — R1은 보관만, U-2). 없으면 null.
 * @param maxEntrySlippagePct 전략 override 또는 전역 default(0.2). cap = priceAtSignal × (1+이값/100).
 * @param maxSpreadPct 전략 override 또는 전역 default(0.3). (ask1-bid1)/mid × 100 초과 시 스킵.
 * @param quoteAsOf 호가 신선도 timestamp(stale 판정 — U-3). null이면 stale 취급(진입 스킵).
 * @param evalAt 평가 시각(결정론 위해 주입 — Clock 의존 0). stale age = evalAt - quoteAsOf.
 * @param maxQuoteAgeSec stale 임계(초 — U-3). default [EntryPricePolicy.DEFAULT_MAX_QUOTE_AGE_SEC](보수 3초).
 * @param attempt 재시도 회차(0=최초). priceAtSignal 불변 검증용 메타(R2 워커 — §3-2). 산정 로직엔 영향 0(불변 LOCK).
 */
data class EntryPriceInput(
    val priceAtSignal: BigDecimal,
    val ask1: BigDecimal?,
    val bid1: BigDecimal?,
    val askQty1: Long? = null,
    val maxEntrySlippagePct: BigDecimal,
    val maxSpreadPct: BigDecimal,
    val quoteAsOf: Instant?,
    val evalAt: Instant,
    val maxQuoteAgeSec: Long = EntryPricePolicy.DEFAULT_MAX_QUOTE_AGE_SEC,
    val attempt: Int = 0,
)

/**
 * [EntryPricePolicy.evaluate] 출력 — sealed(스킵 사유 audit 코드 명확 · ADR 0114 §6-1).
 *
 * 각 결정은 자본을 직접 움직이지 않는다 — 호출자(RealtimeSignalGenerator BUY 경로)가
 * [Enter]면 OrderIntent `priceType=LIMIT`+`limitPrice` 발행, [Skip]이면 audit만 남긴다(R2).
 */
sealed interface EntryPriceDecision {
    /** 진입 — priceType=LIMIT + limitPrice(=ask1, 상한 이내 marketable limit) 발행. */
    data class Enter(
        val limitPrice: BigDecimal,
    ) : EntryPriceDecision

    /** 진입 스킵 — 사유별 audit 코드. **어떤 사유에서도 시장가 fallback 0**(§3-1-5 LOCK). */
    sealed interface Skip : EntryPriceDecision {
        /** audit 코드(SIGNAL_SKIPPED_* — R2 발행). */
        val auditCode: String

        /** 호가 미가용(source 부재·ask1/bid1 null·≤0). → SIGNAL_SKIPPED_NO_QUOTE. */
        data object NoQuote : Skip {
            override val auditCode: String = "SIGNAL_SKIPPED_NO_QUOTE"
        }

        /** 호가 stale(quoteAsOf 미가용 또는 maxQuoteAgeSec 초과 경과). → SIGNAL_SKIPPED_NO_QUOTE(stale 세부). */
        data object QuoteStale : Skip {
            override val auditCode: String = "SIGNAL_SKIPPED_NO_QUOTE"
        }

        /** 스프레드 > maxSpreadPct. → SIGNAL_SKIPPED_SPREAD. */
        data object SpreadTooWide : Skip {
            override val auditCode: String = "SIGNAL_SKIPPED_SPREAD"
        }

        /** ask1 > cap(슬리피지 상한 초과). → SIGNAL_SKIPPED_ENTRY_PRICE_CAP. */
        data object PriceCapExceeded : Skip {
            override val auditCode: String = "SIGNAL_SKIPPED_ENTRY_PRICE_CAP"
        }

        /** 매도1호가 잔량 약함(R2 활성화 — U-2). → SIGNAL_SKIPPED_THIN_LIQUIDITY. */
        data object ThinLiquidity : Skip {
            override val auditCode: String = "SIGNAL_SKIPPED_THIN_LIQUIDITY"
        }
    }
}
