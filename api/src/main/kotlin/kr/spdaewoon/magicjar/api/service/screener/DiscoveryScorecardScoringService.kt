package kr.spdaewoon.magicjar.api.service.screener

import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaEntity
import kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 발굴 스코어카드 — 야간 채점 잡 (Flyway V77).
 *
 * 미채점(scored_at IS NULL) row를 snapshot_date(= 다음 거래일)의 실제 일봉으로 채점한다.
 * R2 저녁 일봉 snapshot(20:05) **이후**(23:30 KST)에 돌아 그날 일봉이 적재되어 있다고 가정한다.
 *
 * ### 채점 규칙
 *   - snapshot_date(KST) == 채점 대상 일봉의 거래일.
 *   - base_close = snapshot 시 기록한 발굴 입력(전일) 종가. null이면 채점 시 보강(전일 일봉 종가).
 *   - next_return_pct      = (next_close / base_close - 1) * 100
 *   - next_open_return_pct = (next_open  / base_close - 1) * 100
 *   - scored_at 마킹.
 *
 * ### 정직성 (mock 0)
 *   - 대상 일봉 미적재(거래정지·미수집) → skip, scored_at 유지(NULL). 다음 밤 재시도.
 *   - base_close 보강 실패(전일 일봉 부재) → skip.
 *
 * ### 가드
 *   - KIS 호출 0 (DB candle만). 자본/주문/watchlist write 0. AI 0.
 */
@Service
class DiscoveryScorecardScoringService(
    private val scorecardRepository: DiscoveryScorecardJpaRepository,
    private val candleRepositoryProvider: ObjectProvider<CandleRepository>,
    @Value("\${magicjar.discovery-scorecard.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(DiscoveryScorecardScoringService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    data class ScoringResult(
        val status: String,
        val scanned: Int,
        val scored: Int,
        val skipped: Int,
    )

    /**
     * 채점 — scored_at IS NULL + snapshot_date <= today(KST) row 처리.
     *
     * @param today 채점 기준 영업일(KST). snapshot_date 가 이 날짜 이하인 미채점 row를 대상.
     */
    fun runScoring(today: LocalDate): ScoringResult {
        if (!enabled) {
            log.info("발굴 스코어카드 scoring DISABLED — skip (today={})", today)
            return ScoringResult(status = "DISABLED", scanned = 0, scored = 0, skipped = 0)
        }

        val candleRepository = candleRepositoryProvider.ifAvailable
        if (candleRepository == null) {
            log.warn("발굴 스코어카드 scoring — CandleRepository bean 부재, skip")
            return ScoringResult(status = "NO_CANDLE_REPO", scanned = 0, scored = 0, skipped = 0)
        }

        val rows = scorecardRepository.findByScoredAtIsNullAndSnapshotDateLessThanEqualOrderBySnapshotDateAsc(today)
        if (rows.isEmpty()) {
            log.info("발굴 스코어카드 scoring — 미채점 row 0건 (today={})", today)
            return ScoringResult(status = "COMPLETED", scanned = 0, scored = 0, skipped = 0)
        }

        // 종목별 일봉 캐시 (같은 종목이 여러 프리셋·날짜에 중복 등장 — read 절약).
        val candleCache: MutableMap<String, List<Candle>> = HashMap()
        var scored = 0
        var skipped = 0
        val toPersist: MutableList<DiscoveryScorecardJpaEntity> = ArrayList()

        for (row in rows) {
            val candles =
                candleCache.getOrPut(row.instrumentCode) {
                    candleRepository.findLatestByCodeAndInterval(row.instrumentCode, CandleInterval.ONE_DAY, DAILY_LOOKBACK)
                }
            if (candles.isEmpty()) {
                skipped++
                continue
            }

            val targetCandle = candles.firstOrNull { tradeDate(it.ts.toJavaInstant()) == row.snapshotDate }
            if (targetCandle == null) {
                // 대상 일봉 미적재(거래정지·미수집) — scored_at 유지(NULL), 다음 밤 재시도.
                skipped++
                continue
            }

            val baseClose = row.baseClose ?: resolveBaseClose(candles, row.snapshotDate)
            if (baseClose == null || baseClose.signum() <= 0) {
                skipped++
                continue
            }

            val nextClose = targetCandle.closeAdj ?: targetCandle.closeRaw
            val nextOpen = targetCandle.openAdj ?: targetCandle.openRaw
            val nextHigh = targetCandle.highAdj ?: targetCandle.highRaw

            row.baseClose = baseClose
            row.nextClose = nextClose
            row.nextOpen = nextOpen
            row.nextHigh = nextHigh
            row.nextReturnPct = returnPct(nextClose, baseClose)
            row.nextOpenReturnPct = returnPct(nextOpen, baseClose)
            row.scoredAt = Instant.now()
            toPersist.add(row)
            scored++
        }

        if (toPersist.isNotEmpty()) {
            scorecardRepository.saveAll(toPersist)
        }

        log.info(
            "발굴 스코어카드 scoring 완료: today={} scanned={} scored={} skipped(미채점 유지)={}",
            today,
            rows.size,
            scored,
            skipped,
        )
        return ScoringResult(status = "COMPLETED", scanned = rows.size, scored = scored, skipped = skipped)
    }

    /** base_close 보강 = snapshot_date 직전 거래일의 일봉 종가(발굴 입력 = 전일 종가). */
    private fun resolveBaseClose(
        candles: List<Candle>,
        snapshotDate: LocalDate,
    ): BigDecimal? {
        val prev =
            candles
                .filter { tradeDate(it.ts.toJavaInstant()).isBefore(snapshotDate) }
                .maxByOrNull { it.ts.toJavaInstant() }
                ?: return null
        return prev.closeAdj ?: prev.closeRaw
    }

    private fun returnPct(
        value: BigDecimal,
        base: BigDecimal,
    ): BigDecimal =
        value
            .divide(base, 10, RoundingMode.HALF_EVEN)
            .subtract(BigDecimal.ONE)
            .multiply(HUNDRED)
            .setScale(4, RoundingMode.HALF_EVEN)

    private fun tradeDate(ts: Instant): LocalDate = ts.atZone(kst).toLocalDate()

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal("100")

        /** 일봉 lookback — snapshot_date + 전일 base_close 보강 커버(여유 포함). */
        const val DAILY_LOOKBACK: Int = 10
    }
}
