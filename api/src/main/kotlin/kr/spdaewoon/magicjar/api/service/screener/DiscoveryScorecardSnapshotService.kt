package kr.spdaewoon.magicjar.api.service.screener

import kr.spdaewoon.magicjar.api.controller.screener.DaytradeDiscoverRequest
import kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandidateProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 발굴 스코어카드 — 일일 장전 snapshot 잡 (Flyway V77).
 *
 * 활성 빌트인 단타 프리셋(DAYTRADE_* · is_active=true) 각각을 [DaytradeDiscoverService]로 발굴하고,
 * TOP N 후보를 `discovery_scorecard` 테이블에 INSERT(멱등 ON CONFLICT DO NOTHING)한다.
 *
 * ### 왜 장전인가 (발굴 입력 = 전일 일봉)
 * 발굴은 DB의 마지막 일봉(= 전 거래일 종가) 기준으로 후보를 뽑는다. 당일 일봉은 아직 없으므로
 * 장전 시점(snapshotDate = 오늘 = 다음 거래일)에 기록하는 것이 정합이다.
 * 채점은 야간([DiscoveryScorecardScoringService])에 이 날짜(오늘)의 일봉으로 수행한다.
 *
 * ### base_close
 * 발굴 입력 일봉(전일 종가)을 다음날 수익률의 분모로 같이 기록한다. 후보별 마지막 1d 캔들의 종가.
 *
 * ### 가드
 * - watchlist write 0 · 주문 0 · 자본 path 0 (startRealtimeWatch=false 고정 — 기록·관찰만).
 * - KIS 호출 0 (발굴·base_close 모두 DB candle).
 * - mock 0 — 후보 없으면 INSERT 0, 정직.
 * - AI 호출 0 (룰 기반 발굴 재사용).
 */
@Service
class DiscoveryScorecardSnapshotService(
    private val daytradeDiscoverService: DaytradeDiscoverService,
    private val candidateProvider: ResearchCandidateProvider,
    private val scorecardRepository: DiscoveryScorecardJpaRepository,
    private val candleRepositoryProvider: ObjectProvider<CandleRepository>,
    @Value("\${magicjar.discovery-scorecard.enabled:true}") private val enabled: Boolean,
    @Value("\${magicjar.discovery-scorecard.top-n:20}") private val topN: Int,
    @Value("\${magicjar.discovery-scorecard.snapshot-user-id:0}") private val snapshotUserId: Long,
) {
    private val log = LoggerFactory.getLogger(DiscoveryScorecardSnapshotService::class.java)

    data class SnapshotResult(
        val status: String,
        val presets: Int,
        val candidates: Int,
        val inserted: Int,
    )

    /**
     * 오늘(snapshotDate) 활성 빌트인 프리셋 발굴 → TOP N INSERT.
     *
     * @param snapshotDate 발사 영업일 (KST). 채점 대상 = 이 날짜의 일봉(다음 거래일).
     */
    fun runSnapshot(snapshotDate: LocalDate): SnapshotResult {
        if (!enabled) {
            log.info("발굴 스코어카드 snapshot DISABLED — skip (snapshotDate={})", snapshotDate)
            return SnapshotResult(status = "DISABLED", presets = 0, candidates = 0, inserted = 0)
        }

        // candidateProvider.candidates() 는 이미 is_active=true 만 반환(DbResearchCandidateProvider) — 활성 가드 내장.
        val activePresets =
            candidateProvider
                .candidates()
                .filter { it.key.startsWith(DAYTRADE_PREFIX) }
        if (activePresets.isEmpty()) {
            log.info("발굴 스코어카드 snapshot — 활성 DAYTRADE_* 프리셋 0건, skip (snapshotDate={})", snapshotDate)
            return SnapshotResult(status = "NO_PRESET", presets = 0, candidates = 0, inserted = 0)
        }

        val candleRepository = candleRepositoryProvider.ifAvailable
        val limit = topN.coerceIn(1, 100)

        var totalCandidates = 0
        var totalInserted = 0
        for (preset in activePresets) {
            val result =
                runCatching {
                    daytradeDiscoverService.discover(
                        DaytradeDiscoverRequest(
                            userId = snapshotUserId.coerceAtLeast(1),
                            presetKey = preset.key,
                            interval = INTERVAL_1D,
                            limit = limit,
                            startRealtimeWatch = false,
                        ),
                    )
                }.getOrElse { ex ->
                    log.warn("발굴 스코어카드 snapshot — 프리셋 발굴 실패(skip): preset={} err={}", preset.key, ex.message)
                    null
                } ?: continue

            for (match in result.matches) {
                totalCandidates++
                val baseClose = candleRepository?.let { resolveBaseClose(it, match.instrumentCode) }
                val inserted =
                    runCatching {
                        scorecardRepository.insertIgnore(
                            snapshotDate = snapshotDate,
                            presetKey = preset.key,
                            rank = match.rank,
                            instrumentCode = match.instrumentCode,
                            instrumentName = match.instrumentName,
                            score = match.score,
                            baseClose = baseClose,
                        )
                    }.getOrElse { ex ->
                        log.warn(
                            "발굴 스코어카드 snapshot INSERT 실패(skip): date={} preset={} code={} err={}",
                            snapshotDate,
                            preset.key,
                            match.instrumentCode,
                            ex.message,
                        )
                        0
                    }
                totalInserted += inserted
            }
        }

        log.info(
            "발굴 스코어카드 snapshot 완료: snapshotDate={} presets={} candidates={} inserted={}",
            snapshotDate,
            activePresets.size,
            totalCandidates,
            totalInserted,
        )
        return SnapshotResult(status = "COMPLETED", presets = activePresets.size, candidates = totalCandidates, inserted = totalInserted)
    }

    /** 발굴 입력(전일) 일봉 = 마지막 1d 캔들의 종가(adj 우선). 없으면 null(채점 시 보강). */
    private fun resolveBaseClose(
        candleRepository: CandleRepository,
        code: String,
    ): BigDecimal? {
        val last = candleRepository.findLatestByCodeAndInterval(code, CandleInterval.ONE_DAY, 1).lastOrNull() ?: return null
        return last.closeAdj ?: last.closeRaw
    }

    private companion object {
        const val DAYTRADE_PREFIX = "DAYTRADE_"
        const val INTERVAL_1D = "1d"
    }
}
