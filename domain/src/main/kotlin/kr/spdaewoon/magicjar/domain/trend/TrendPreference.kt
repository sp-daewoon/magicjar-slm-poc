package kr.spdaewoon.magicjar.domain.trend

import java.time.Instant

/**
 * 사용자 시장 추세 분석 설정 (MA periods · RS period · cross pair).
 *
 * 본 도메인은 forward-compat 확장 슬롯 단일 진입점 — MA type · indicator 추가 · 차트 default · 알림 임계 등
 * 모든 사용자 설정은 `extra JSONB` (Flyway V51 user_preference 표 · 2026-05-24 R0 amend V44 → V51 shift · ADR 0102 정합)로 add-only 흡수.
 *
 * @see ADR 0100 (T-MARKET 트랙) · ADR 0101 (본 ADR — TrendPreference + user_preference)
 *
 * ## invariant 5건 (init require)
 * 1. maPeriods 비어있지 않음
 * 2. maPeriods size 1..5
 * 3. 각 period 1..252
 * 4. sorted ASC + 중복 없음
 * 5. rsPeriod 1..252
 * 6. crossPair? 명시 시 short < long + 둘 다 maPeriods 내
 *
 * 모든 위반은 한국어 require 메시지로 throw (사용자 노출 의무 · 자체 추론 0).
 */
data class TrendPreference(
    val userId: String,
    val maPeriods: List<Int>,
    val rsPeriod: Int = TrendPreferenceDefaults.RS_PERIOD,
    val crossPair: Pair<Int, Int>? = null,
    val updatedAt: Instant,
    val extra: Map<String, Any?>? = null,
) {
    init {
        require(maPeriods.isNotEmpty()) { "MA periods 1개 이상 필수" }
        require(maPeriods.size <= 5) { "MA periods 5개 초과 금지 (입력=${maPeriods.size})" }
        require(maPeriods.all { it in 1..252 }) {
            "각 MA 기간은 1~252일 사이 (입력 위반=${maPeriods.filter { it !in 1..252 }})"
        }
        require(maPeriods == maPeriods.sorted().distinct()) {
            "MA periods는 중복 없이 오름차순 (입력=$maPeriods)"
        }
        require(rsPeriod in 1..252) { "RS period는 1~252일 사이 (입력=$rsPeriod)" }
        crossPair?.let { (short, long) ->
            require(short in maPeriods && long in maPeriods) {
                "crossPair는 maPeriods 내 값 필수 (입력=$short/$long · maPeriods=$maPeriods)"
            }
            require(short < long) { "crossPair는 short < long (입력=$short/$long)" }
        }
    }

    /** crossPair 명시 시 본 값, null 시 maPeriods 최소 */
    fun shortMa(): Int = crossPair?.first ?: maPeriods.first()

    /** crossPair 명시 시 본 값, null 시 maPeriods 최대 */
    fun longMa(): Int = crossPair?.second ?: maPeriods.last()

    /** default preference 와 동일 여부 — `isUserCustom` flag 계산 */
    fun isDefault(): Boolean =
        maPeriods == TrendPreferenceDefaults.MA_PERIODS &&
            rsPeriod == TrendPreferenceDefaults.RS_PERIOD &&
            crossPair == null &&
            extra.isNullOrEmpty()
}

/**
 * TrendPreference 기본값 단일 진입점 — 코드 곳곳 hardcode 회피.
 *
 * Flyway V51 시드 SQL `INSERT FROM user_account ON CONFLICT DO NOTHING` 와 동기화 의무 —
 * 본 default 변경 시 V51 DEFAULT 절도 함께 갱신.
 */
object TrendPreferenceDefaults {
    val MA_PERIODS: List<Int> = listOf(5, 20, 60)
    const val RS_PERIOD: Int = 60

    /** 사용자가 한 번도 PUT 한 적 없을 때 service layer가 반환할 default 인스턴스 생성. */
    fun forUser(
        userId: String,
        updatedAt: Instant = Instant.EPOCH,
    ): TrendPreference =
        TrendPreference(
            userId = userId,
            maPeriods = MA_PERIODS,
            rsPeriod = RS_PERIOD,
            crossPair = null,
            updatedAt = updatedAt,
            extra = null,
        )
}
