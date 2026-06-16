package kr.spdaewoon.magicjar.api.service.screener

import kr.spdaewoon.magicjar.api.controller.screener.DiscoveryScorecardResponseDto
import kr.spdaewoon.magicjar.api.controller.screener.DiscoveryScorecardRowDto
import kr.spdaewoon.magicjar.api.controller.screener.DiscoveryScorecardSummaryDto
import kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaEntity
import kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * 발굴 스코어카드 조회 서비스 (Flyway V77).
 *
 * `GET /api/screener/scorecard` — 기간·프리셋별 forward-test 요약(hit rate·평균 수익률·표본 수) + 상세 rows.
 *
 * 가드: read-only. 자본/주문/watchlist write 0. KIS 0. AI 0. mock 0(미채점 row는 null 그대로 노출).
 */
@Service
class DiscoveryScorecardQueryService(
    private val scorecardRepository: DiscoveryScorecardJpaRepository,
) {
    @Transactional(readOnly = true)
    fun query(
        from: LocalDate,
        to: LocalDate,
        presetKey: String?,
        maxRows: Int,
    ): DiscoveryScorecardResponseDto {
        val rows = scorecardRepository.findRowsInRange(from, to, presetKey)
        val rowDtos = rows.take(maxRows.coerceIn(1, MAX_ROW_CAP)).map { it.toRowDto() }

        val overall = summarize(presetKey = null, rows = rows)
        val byPreset =
            rows
                .groupBy { it.presetKey }
                .toSortedMap()
                .map { (key, group) -> summarize(presetKey = key, rows = group) }

        return DiscoveryScorecardResponseDto(
            from = from,
            to = to,
            presetKey = presetKey,
            overall = overall,
            byPreset = byPreset,
            rows = rowDtos,
        )
    }

    private fun summarize(
        presetKey: String?,
        rows: List<DiscoveryScorecardJpaEntity>,
    ): DiscoveryScorecardSummaryDto {
        val scored = rows.filter { it.scoredAt != null && it.nextReturnPct != null }
        val scoredCount = scored.size
        val avgNextReturn = scored.mapNotNull { it.nextReturnPct }.averageOrNull()
        val avgNextOpenReturn = scored.mapNotNull { it.nextOpenReturnPct }.averageOrNull()
        val hitRate =
            if (scoredCount == 0) {
                null
            } else {
                val hits = scored.count { (it.nextReturnPct ?: BigDecimal.ZERO).signum() > 0 }
                BigDecimal(hits)
                    .multiply(HUNDRED)
                    .divide(BigDecimal(scoredCount), 1, RoundingMode.HALF_EVEN)
            }
        return DiscoveryScorecardSummaryDto(
            presetKey = presetKey,
            sampleCount = rows.size,
            scoredCount = scoredCount,
            avgNextReturnPct = avgNextReturn,
            avgNextOpenReturnPct = avgNextOpenReturn,
            hitRatePct = hitRate,
        )
    }

    private fun List<BigDecimal>.averageOrNull(): BigDecimal? {
        if (isEmpty()) return null
        return fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(size), 2, RoundingMode.HALF_EVEN)
    }

    private fun DiscoveryScorecardJpaEntity.toRowDto(): DiscoveryScorecardRowDto =
        DiscoveryScorecardRowDto(
            snapshotDate = snapshotDate,
            presetKey = presetKey,
            rank = rank,
            instrumentCode = instrumentCode,
            instrumentName = instrumentName,
            score = score,
            baseClose = baseClose,
            nextOpen = nextOpen,
            nextHigh = nextHigh,
            nextClose = nextClose,
            nextReturnPct = nextReturnPct,
            nextOpenReturnPct = nextOpenReturnPct,
            scoredAt = scoredAt,
        )

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal("100")
        const val MAX_ROW_CAP: Int = 2000
    }
}
