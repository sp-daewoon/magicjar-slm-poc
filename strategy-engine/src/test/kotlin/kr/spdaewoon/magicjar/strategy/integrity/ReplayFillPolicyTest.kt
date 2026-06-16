package kr.spdaewoon.magicjar.strategy.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ReplayFillPolicy] 단위 테스트 (순수 함수 — 결정론·100% 커버 · ADR 0115 §P1-2).
 *
 * 규칙 LOCK: fillSource=REST_BACKFILL → replay / executedAt 신선도 경계 / null 경계 / 시계 skew / ENV default.
 */
class ReplayFillPolicyTest {
    private val now = 1_733_000_000L // 임의 평가시각(epochSecond)

    private fun input(
        fillSource: String? = "WS",
        executedAt: Long? = now,
        evalEpoch: Long = now,
        threshold: Long = DriftCorrectionDefaults.REPLAY_FRESHNESS_THRESHOLD_SECONDS,
    ) = ReplayFillInput(
        fillSource = fillSource,
        executedAtEpochSeconds = executedAt,
        evalEpochSeconds = evalEpoch,
        freshnessThresholdSeconds = threshold,
    )

    // === 1차(권위) fillSource ===
    @Test
    fun `fillSource REST_BACKFILL — replay true (신선해도)`() {
        // executedAt 신선(now)이어도 fillSource가 권위 → replay
        assertTrue(ReplayFillPolicy.isReplayFill(input(fillSource = "REST_BACKFILL", executedAt = now)))
    }

    @Test
    fun `fillSource WS + 신선 — replay false (실시간 신규 체결)`() {
        assertFalse(ReplayFillPolicy.isReplayFill(input(fillSource = "WS", executedAt = now)))
    }

    @Test
    fun `fillSource 대소문자 공백 정규화 — rest_backfill replay true`() {
        assertTrue(ReplayFillPolicy.isReplayFill(input(fillSource = " rest_backfill ")))
    }

    // === 2차(보강) executedAt 신선도 ===
    @Test
    fun `WS + executedAt 임계 초과 과거 — 신선도로 replay true`() {
        // age = 121 > 120 threshold
        assertTrue(
            ReplayFillPolicy.isReplayFill(input(fillSource = "WS", executedAt = now - 121, evalEpoch = now)),
        )
    }

    @Test
    fun `신선도 경계 — age == threshold 는 신선(replay false)`() {
        // age = 120 == threshold → 경계 미포함 (age > threshold 만 replay)
        assertFalse(
            ReplayFillPolicy.isReplayFill(input(fillSource = "WS", executedAt = now - 120, evalEpoch = now)),
        )
    }

    @Test
    fun `신선도 경계 — age threshold+1 은 replay true`() {
        assertTrue(
            ReplayFillPolicy.isReplayFill(input(fillSource = "WS", executedAt = now - 121, evalEpoch = now)),
        )
    }

    // === 경계 처리 ===
    @Test
    fun `executedAt null + WS — 신선도 판정 skip, replay false`() {
        assertFalse(ReplayFillPolicy.isReplayFill(input(fillSource = "WS", executedAt = null)))
    }

    @Test
    fun `executedAt null + REST_BACKFILL — fillSource 권위로 replay true`() {
        assertTrue(ReplayFillPolicy.isReplayFill(input(fillSource = "REST_BACKFILL", executedAt = null)))
    }

    @Test
    fun `fillSource null + 신선 executedAt — replay false`() {
        assertFalse(ReplayFillPolicy.isReplayFill(input(fillSource = null, executedAt = now)))
    }

    @Test
    fun `fillSource null + 과거 executedAt — 신선도로 replay true`() {
        assertTrue(ReplayFillPolicy.isReplayFill(input(fillSource = null, executedAt = now - 200, evalEpoch = now)))
    }

    @Test
    fun `미래 executedAt(시계 skew) — age 음수 → 신선 replay false`() {
        assertFalse(
            ReplayFillPolicy.isReplayFill(input(fillSource = "WS", executedAt = now + 60, evalEpoch = now)),
        )
    }

    // === ENV default ===
    @Test
    fun `default threshold 120초 (ENV default fallback)`() {
        assertEquals(120L, DriftCorrectionDefaults.REPLAY_FRESHNESS_THRESHOLD_SECONDS)
        // 생략 시 default 적용 — age 119 신선
        assertFalse(
            ReplayFillPolicy.isReplayFill(
                ReplayFillInput(fillSource = "WS", executedAtEpochSeconds = now - 119, evalEpochSeconds = now),
            ),
        )
        // age 121 replay
        assertTrue(
            ReplayFillPolicy.isReplayFill(
                ReplayFillInput(fillSource = "WS", executedAtEpochSeconds = now - 121, evalEpochSeconds = now),
            ),
        )
    }

    @Test
    fun `커스텀 threshold 주입 — 30초로 좁히면 age 31 replay`() {
        assertTrue(
            ReplayFillPolicy.isReplayFill(
                input(fillSource = "WS", executedAt = now - 31, evalEpoch = now, threshold = 30L),
            ),
        )
    }

    @Test
    fun `결정론 — 동일 입력 반복 동일 출력`() {
        val i = input(fillSource = "WS", executedAt = now - 121)
        assertEquals(ReplayFillPolicy.isReplayFill(i), ReplayFillPolicy.isReplayFill(i))
    }
}
