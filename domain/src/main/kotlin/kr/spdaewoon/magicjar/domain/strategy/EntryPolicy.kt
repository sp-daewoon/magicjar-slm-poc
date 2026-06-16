package kr.spdaewoon.magicjar.domain.strategy

import java.math.BigDecimal

/**
 * 진입(BUY) 집행 정책 — ADR 0114 §3·§5 · U-1 결정(묶음 객체).
 *
 * marketable limit 진입(매도1호가에 걸되 상한 초과 시 스킵)의 3 파라미터를 **하나의 묶음 객체**로 묶는다.
 * [StrategyDefinition.entryPolicy] == null이면 전역 default(env `magicjar.order.entry.*`) 적용 →
 * 기존 전략 0 변경(하위호환 LOCK). 개별 필드 null이면 그 필드만 전역 default로 폴백한다.
 *
 * ## U-1 결정 — 묶음 객체 (ScaleInPlan 선례 1:1)
 * ADR 0114 §9 U-1 보수 default("ScaleInPlan처럼 묶음 객체 권장")를 채택한다. 근거:
 * 1. **응집** — 세 필드는 모두 "진입 집행"이라는 단일 관심사. 흩뿌리면 진입 정책의 경계가 흐려진다.
 * 2. **선례 정합** — `ScaleInPlan`(`scale_in_config` JSONB)·`PartialExit`(`partial_exits` JSONB)가
 *    이미 묶음 객체→전용 JSONB 컬럼 패턴이다. R2가 `entry_policy` JSONB 컬럼 1개로 직렬화하면 된다.
 * 3. **확장성** — 향후 진입 파라미터(잔량 임계·재시도 등)가 추가되면 이 객체 안에서 add-only 확장.
 *    평면 필드면 StrategyDefinition·매퍼·entity가 매 필드마다 비대해진다.
 *
 * ## 순수 도메인 (LOCK)
 * Spring·JPA·Jackson 어노테이션 import 절대 금지(헥사고날 정합 — ScaleInPlan.kt 정합).
 * jackson이 별도 어노테이션 없이 직렬화/역직렬화하도록 평탄한 데이터 클래스를 유지한다(R2 영속 wiring).
 *
 * ## strategy 테이블 `entry_policy` JSONB 직렬화 예시 (R2 신규 컬럼 — V72 후보)
 * ```json
 * { "maxEntrySlippagePct": 0.2, "entryTimeoutSec": 15, "maxSpreadPct": 0.3 }
 * ```
 * 일부만 override(나머지는 전역 default) 예시:
 * ```json
 * { "maxSpreadPct": 0.5 }
 * ```
 *
 * @param maxEntrySlippagePct 진입 상한 슬리피지%. 상한 = 기준가 × (1 + 이값/100). null이면 전역 default(0.2).
 *   ADR 0114 §2-1 채택안. ask1 > 상한이면 진입 스킵(시장가 fallback 금지 §3-1).
 * @param entryTimeoutSec LIMIT 미체결 취소 타임아웃(초). null이면 전역 default(15). 시장가 전환 금지(§3-2).
 *   R1 도메인은 보관만 — 타임아웃 취소 워커 트리거는 R2(§9 U-4).
 * @param maxSpreadPct 스프레드 게이트%. (ask1-bid1)/mid × 100 > 이값이면 진입 스킵. null이면 전역 default(0.3).
 */
data class EntryPolicy(
    val maxEntrySlippagePct: BigDecimal? = null,
    val entryTimeoutSec: Int? = null,
    val maxSpreadPct: BigDecimal? = null,
) {
    init {
        maxEntrySlippagePct?.let {
            require(it >= BigDecimal.ZERO) { "maxEntrySlippagePct must be >= 0: $it" }
            require(it <= EntryPolicyLimits.MAX_SLIPPAGE_PCT) {
                "maxEntrySlippagePct must be <= ${EntryPolicyLimits.MAX_SLIPPAGE_PCT}: $it"
            }
        }
        entryTimeoutSec?.let {
            require(it in EntryPolicyLimits.MIN_TIMEOUT_SEC..EntryPolicyLimits.MAX_TIMEOUT_SEC) {
                "entryTimeoutSec must be in ${EntryPolicyLimits.MIN_TIMEOUT_SEC}..${EntryPolicyLimits.MAX_TIMEOUT_SEC}: $it"
            }
        }
        maxSpreadPct?.let {
            require(it >= BigDecimal.ZERO) { "maxSpreadPct must be >= 0: $it" }
            require(it <= EntryPolicyLimits.MAX_SPREAD_PCT) {
                "maxSpreadPct must be <= ${EntryPolicyLimits.MAX_SPREAD_PCT}: $it"
            }
        }
    }

    /** 전략별 override가 없으면(null) 전역 default를 채워 **모든 필드가 채워진** 유효 정책으로 해석한다(R2 호출 직전). */
    fun resolved(default: ResolvedEntryPolicy): ResolvedEntryPolicy =
        ResolvedEntryPolicy(
            maxEntrySlippagePct = maxEntrySlippagePct ?: default.maxEntrySlippagePct,
            entryTimeoutSec = entryTimeoutSec ?: default.entryTimeoutSec,
            maxSpreadPct = maxSpreadPct ?: default.maxSpreadPct,
        )
}

/**
 * 전역 default가 모두 채워진 진입 정책 — [EntryPolicy.resolved] 결과 · [EntryPricePolicy] 입력 산정 base.
 *
 * 전역 default는 env(`magicjar.order.entry.*`)로 주입된다(R2). R1은 결정론 계약만 정의한다.
 *
 * @param maxEntrySlippagePct 진입 상한 슬리피지%(필수 — 전역 default ADR 0114 §2-3 = 0.2).
 * @param entryTimeoutSec LIMIT 미체결 취소 타임아웃 초(전역 default = 15).
 * @param maxSpreadPct 스프레드 게이트%(전역 default = 0.3).
 */
data class ResolvedEntryPolicy(
    val maxEntrySlippagePct: BigDecimal,
    val entryTimeoutSec: Int,
    val maxSpreadPct: BigDecimal,
) {
    companion object {
        /** ADR 0114 §2-3 권장 보수 시작 기본값 — 전역 default(env 미설정 시 fallback). */
        val DEFAULTS: ResolvedEntryPolicy =
            ResolvedEntryPolicy(
                maxEntrySlippagePct = EntryPolicyLimits.DEFAULT_MAX_SLIPPAGE_PCT,
                entryTimeoutSec = EntryPolicyLimits.DEFAULT_TIMEOUT_SEC,
                maxSpreadPct = EntryPolicyLimits.DEFAULT_MAX_SPREAD_PCT,
            )
    }
}

/**
 * 진입 정책 검증 상수 + ADR 0114 §2-3 권장 보수 기본값.
 *
 * cap은 과실 입력(예: 슬리피지 50% 설정) 방어용 sanity 상한이다(자본 보호 — 사용자 결재 0.2~0.3 보수 시작).
 */
object EntryPolicyLimits {
    /** 진입 상한 슬리피지 전역 default(%) — ADR 0114 §2-3 = 0.2(보수 시작). */
    val DEFAULT_MAX_SLIPPAGE_PCT: BigDecimal = BigDecimal("0.2")

    /** 미체결 취소 타임아웃 전역 default(초) — ADR 0114 §2-3 = 15. */
    const val DEFAULT_TIMEOUT_SEC: Int = 15

    /** 스프레드 게이트 전역 default(%) — ADR 0114 §2-3 = 0.3. */
    val DEFAULT_MAX_SPREAD_PCT: BigDecimal = BigDecimal("0.3")

    /** 슬리피지 상한 sanity cap(%) — 과실 입력 방어(자본 보호). 5% 초과 진입은 슬리피지 캡 의미 상실. */
    val MAX_SLIPPAGE_PCT: BigDecimal = BigDecimal("5")

    /** 스프레드 게이트 sanity cap(%). */
    val MAX_SPREAD_PCT: BigDecimal = BigDecimal("10")

    /** 타임아웃 최소(초) — 0이면 즉시 취소라 의미 없음. */
    const val MIN_TIMEOUT_SEC: Int = 1

    /** 타임아웃 최대(초) — 5분 초과 미체결 보유는 추격 risk(자본 path 보수). */
    const val MAX_TIMEOUT_SEC: Int = 300
}
