package kr.spdaewoon.magicjar.api.application.screener

import kr.spdaewoon.magicjar.domain.screener.ScreenerMatch
import kr.spdaewoon.magicjar.domain.screener.ScreenerScoreBreakdown
import kr.spdaewoon.magicjar.domain.screener.port.ScreenerRankerPort
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Multi-criteria 랭킹 — fixed weights 4 기준 (ADR 0071 §2-5 E-1 + spec §2-2).
 *
 * api 모듈에 위치 — ADR 0004 모듈 경계 정합.
 *
 * fixed weights (LOCK):
 * - signal 0.40 — DSL evalResult.strength
 * - volume 0.30 — log(avgVolume * close) normalize
 * - atr 0.20 — ATR/close ratio sweet spot
 * - cap 0.10 — log(marketCap) normalize
 *
 * 입력 [matches] 각 element의 [ScreenerMatch.breakdown]에는 정규화된 4 기준 점수(0~1)가 채워져 있다.
 * 본 ranker는 weighted sum을 [ScreenerScoreBreakdown.total]에 채우고 descending sort + rank 1-base 부여.
 *
 * ### 동점 처리 (재현 결정성 LOCK)
 * total score 동점일 때 — instrumentCode 사전순 stable sort. 사용자 메모리 `feedback_no_mock_data` 정합
 * (random 합성 0).
 *
 * ### AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5)
 * 본 ranker는 pure math weighted sum — LLM provider import 0 + LLM 가중치 호출 0.
 */
@Component
class MultiCriteriaRanker : ScreenerRankerPort {
    override fun rank(
        matches: List<ScreenerMatch>,
        limit: Int,
    ): List<ScreenerMatch> {
        require(limit > 0) { "limit must be positive: $limit" }
        if (matches.isEmpty()) return emptyList()

        // 1. weighted sum → total score 재계산
        val withTotal =
            matches.map { match ->
                val total = computeTotal(match.breakdown)
                val newBreakdown =
                    match.breakdown.copy(total = total)
                match.copy(
                    score = total,
                    breakdown = newBreakdown,
                )
            }

        // 2. descending score sort + 동점 처리 (instrumentCode 사전순 stable)
        //    Kotlin sortedWith은 stable이므로 secondary key는 명시
        val sorted =
            withTotal.sortedWith(
                compareByDescending<ScreenerMatch> { it.score }
                    .thenBy { it.instrumentCode },
            )

        // 3. limit cut + rank 1-base 부여
        return sorted.take(limit).mapIndexed { idx, match ->
            match.copy(rank = idx + 1)
        }
    }

    /**
     * 4 기준 weighted sum → total score (0~1 normalize).
     *
     * total = signal*0.40 + volume*0.30 + atr*0.20 + cap*0.10
     */
    private fun computeTotal(b: ScreenerScoreBreakdown): BigDecimal {
        val signalContrib = b.signal.multiply(ScreenerScoreBreakdown.WEIGHT_SIGNAL)
        val volumeContrib = b.volume.multiply(ScreenerScoreBreakdown.WEIGHT_VOLUME)
        val atrContrib = b.atr.multiply(ScreenerScoreBreakdown.WEIGHT_ATR)
        val capContrib = b.cap.multiply(ScreenerScoreBreakdown.WEIGHT_CAP)
        return signalContrib
            .add(volumeContrib)
            .add(atrContrib)
            .add(capContrib)
            .setScale(SCALE, RoundingMode.HALF_EVEN)
    }

    companion object {
        /** 점수 소수점 자리수 (6자리 — DB NUMERIC(10,6) 정합). */
        const val SCALE: Int = 6
    }
}
