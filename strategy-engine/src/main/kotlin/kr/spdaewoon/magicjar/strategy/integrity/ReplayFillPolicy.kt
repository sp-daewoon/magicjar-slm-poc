package kr.spdaewoon.magicjar.strategy.integrity

/**
 * `order.filled.v1` 재발행(backfill/복구) 체결 식별 단일 순수 함수 — ADR 0115 §P1-2 (R1).
 *
 * backfill·재연결 복구가 재발행한 `order.filled.v1`를 청산 coordinator([AutoExitOrderCoordinator])가
 * **새 체결로 오인**하면 과거 체결마다 중복 익절(TP) SELL을 발행한다(2026-06-08 incident 4·6·5·6·7주, ADR 0115 §1).
 * 본 함수는 그 체결이 "replay(복구된 과거 체결)"인지를 순수하게 판정한다 — replay면 R2가 TP 발행을 skip
 * (보유 인덱스·구독 갱신은 유지, 청산 의사결정만 skip).
 *
 * ## 순수성 (LOCK — EntryPricePolicy · ScaleInExitCalculator 선례 1:1)
 * - Spring·JPA·Jackson·Clock·IO 의존 0. 입력([ReplayFillInput]) → 출력([Boolean]) 결정론.
 * - 현재 시각(`evalEpochSeconds`)은 **입력으로 주입**한다(Clock 의존 0 — 재현성). 함수 내부에서 조회하지 않는다.
 * - AI 호출 0 (룰 기반 자본 흐름 순수 함수 — ADR 0023 §2-4 정합).
 *
 * ## 판정 규칙 (ADR 0115 §P1-2 LOCK — 권위 → fail-safe 2중)
 * 1. **1차(권위) — fillSource**: `fillSource == "REST_BACKFILL"`이면 replay(true). backfill 재발행 체결의 권위 식별.
 *    `OrderFilledKafkaProducer`가 이미 payload에 직렬화한 필드(`"REST_BACKFILL"`/`"WS"`)를 read만 한다(신규 필드 0).
 * 2. **2차(보강) — executedAt 신선도**: `executedAtEpochSeconds`가 `evalEpochSeconds`보다 임계
 *    ([ReplayFillInput.freshnessThresholdSeconds] default 120초) 초과 과거면 "복구된 과거 체결"로 보고 replay(true).
 *    WS 정상 경로는 체결 즉시 발행이라 신선하다. 1차로 대부분 차단되나, 향후 다른 복구 경로 대비 fail-safe 하한.
 * 3. 둘 다 아니면 실시간 신규 체결로 보고 false(TP 발행 진행).
 *
 * ## 경계 처리 (결정론)
 * - `executedAtEpochSeconds == null`(payload 누락) → 신선도 판정 불가. **1차(fillSource)에만 의존**하고,
 *   신선도 차원에서는 replay로 보지 않는다(false). fillSource가 권위이므로 누락 시 보수적으로 신규 취급은
 *   TP 중복 risk가 있으나, executedAt 누락은 정상 WS 경로에선 발생하지 않는다(L74 항상 직렬화) → 안전 가정.
 * - `executedAt`이 미래(now보다 큼 — 시계 skew) → age 음수 → 신선(replay 아님). 보수적 통과.
 * - 임계 경계: `age > threshold`만 replay(`age == threshold`는 신선 — 경계 포함하지 않음, EntryPricePolicy stale 판정 정합).
 */
object ReplayFillPolicy {
    /**
     * replay-fill 판정 단일 진입점.
     *
     * @return 이 체결이 replay(복구된 과거 체결)이면 true(R2가 TP 발행 skip). 실시간 신규 체결이면 false.
     */
    fun isReplayFill(input: ReplayFillInput): Boolean {
        // 1차(권위) — fillSource = REST_BACKFILL 이면 즉시 replay. 대소문자/공백 방어적 정규화.
        val normalizedSource = input.fillSource?.trim()?.uppercase()
        if (normalizedSource == FILL_SOURCE_REST_BACKFILL) {
            return true
        }

        // 2차(보강) — executedAt 신선도. null이면 신선도 판정 skip(fillSource에만 의존 → false).
        val executedAt = input.executedAtEpochSeconds ?: return false
        val ageSeconds = input.evalEpochSeconds - executedAt
        // 미래 timestamp(음수 age)는 시계 skew로 보고 신선 취급. 임계 초과만 replay.
        return ageSeconds > input.freshnessThresholdSeconds
    }

    /** 1차 권위 식별값 — `OrderFilledKafkaProducer.fillSource`가 backfill 재발행에 직렬화하는 값. */
    const val FILL_SOURCE_REST_BACKFILL: String = "REST_BACKFILL"

    /** 실시간 WS 체결 식별값(참조용 — 판정은 REST_BACKFILL 양성 + 신선도로 결정). */
    const val FILL_SOURCE_WS: String = "WS"
}

/**
 * [ReplayFillPolicy.isReplayFill] 입력 — 모든 외부 상태 명시 주입(순수성 LOCK · EntryPriceInput 패턴 정합).
 *
 * @param fillSource `order.filled.v1` payload의 `fillSource`("REST_BACKFILL"/"WS"). null이면 1차 판정 skip(신선도만).
 * @param executedAtEpochSeconds payload의 `executedAt`(epochSecond). null이면 신선도 판정 skip.
 * @param evalEpochSeconds 평가 시각(epochSecond — Clock 의존 0 위해 주입). age = eval - executedAt.
 * @param freshnessThresholdSeconds 신선도 임계초(ENV `magicjar...replay.freshness-threshold-seconds` 주입 — R2).
 *   `age > 이값`이면 replay. default [DriftCorrectionDefaults.REPLAY_FRESHNESS_THRESHOLD_SECONDS](120초).
 */
data class ReplayFillInput(
    val fillSource: String?,
    val executedAtEpochSeconds: Long?,
    val evalEpochSeconds: Long,
    val freshnessThresholdSeconds: Long = DriftCorrectionDefaults.REPLAY_FRESHNESS_THRESHOLD_SECONDS,
)
