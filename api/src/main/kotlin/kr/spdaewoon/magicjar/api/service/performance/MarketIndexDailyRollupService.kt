package kr.spdaewoon.magicjar.api.service.performance

import kr.spdaewoon.magicjar.api.persistence.performance.MarketIndexDailyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.performance.MarketIndexDailyJpaRepository
import kr.spdaewoon.magicjar.api.persistence.trend.MarketIndexSnapshotJpaRepository
import kr.spdaewoon.magicjar.domain.performance.MarketIndexDailyRollup
import kr.spdaewoon.magicjar.domain.performance.MarketRegimeClassifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/**
 * 지수 일봉 롤업 서비스 — spec `2026-06-16-strategy-daily-performance.md` §4-4·§5-1 · ADR 0126 §3-1·§3-4·§3-6 정합.
 *
 * 배치(웨이브 B `DailyPerformanceJob`·백필)가 호출하는 **공개 진입점** [rollup] 을 발행한다:
 * 대상 KST 영업일의 `market_index_snapshot`(V47, 1분) 보존분을 지수별로 OHLC 집계 →
 * 등락률·변동성 산출 → 룰 레짐 분류 → `market_index_daily`(V86) 멱등 UPSERT.
 *
 * 운영 안전 (ADR 0126 §3-6):
 * - **새 KIS 호출 0** — 이미 적재된 영속 snapshot 만 읽음.
 * - **AI 호출 0** — 레짐은 [MarketRegimeClassifier] 룰 기반.
 * - snapshot 0건인 지수는 skip(행 미생성) — 휴장일·미수집일 멱등.
 * - UPSERT(UQ index_code, trade_date) — 배치·백필 재실행 중복 0.
 *
 * 레짐 임계치는 `application.yml magicjar.market-index.regime.*` 로 노출(추후 튜닝) — default spec §4-4 LOCK.
 */
@Service
class MarketIndexDailyRollupService(
    private val snapshotRepository: MarketIndexSnapshotJpaRepository,
    private val dailyRepository: MarketIndexDailyJpaRepository,
    @Value("\${magicjar.market-index.regime.high-vol-threshold-pct:2.5}")
    private val highVolThresholdPct: BigDecimal,
    @Value("\${magicjar.market-index.regime.trend-threshold-pct:0.6}")
    private val trendThresholdPct: BigDecimal,
) {
    private val log = LoggerFactory.getLogger(MarketIndexDailyRollupService::class.java)

    private val regimeClassifier: MarketRegimeClassifier by lazy {
        MarketRegimeClassifier(
            highVolThresholdPct = highVolThresholdPct,
            trendThresholdPct = trendThresholdPct,
        )
    }

    /**
     * 대상 KST 영업일의 지수 일봉 롤업 + 멱등 UPSERT.
     *
     * 처리 지수 = 그날 `market_index_snapshot` 에 존재하는 모든 code (코스피 0001·코스닥 1001 최소 2종 포함).
     * 각 지수에 대해 OHLC 집계 → change_pct(전일 close 대비, 없으면 open 대비) → range_pct → regime → UPSERT.
     * snapshot 0건인 지수는 skip(행 미생성).
     *
     * @param tradeDate 집계 대상 KST 영업일.
     * @return 이번 호출로 UPSERT 된 롤업 행 목록 (snapshot 부재로 skip 된 지수는 미포함).
     */
    @Transactional
    fun rollup(tradeDate: LocalDate): List<MarketIndexDailyRollup> {
        val codes = snapshotRepository.findDistinctCodesByTradeDate(tradeDate)
        if (codes.isEmpty()) {
            log.info("market_index_daily 롤업 skip — snapshot 0건: tradeDate={}", tradeDate)
            return emptyList()
        }
        val results = mutableListOf<MarketIndexDailyRollup>()
        for (code in codes) {
            val ohlc = snapshotRepository.aggregateDailyOhlc(code, tradeDate) ?: continue
            val open = ohlc.getOpen() ?: continue
            val high = ohlc.getHigh() ?: continue
            val low = ohlc.getLow() ?: continue
            val close = ohlc.getClose() ?: continue

            val prevClose = dailyRepository.findMostRecentBefore(code, tradeDate)?.close
            val changePct = changePct(close, prevClose, open)
            val rangePct = rangePct(high, low, open)
            val regime = regimeClassifier.classify(changePct, rangePct)

            val rollup =
                MarketIndexDailyRollup(
                    indexCode = code,
                    tradeDate = tradeDate,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    changePct = changePct,
                    rangePct = rangePct,
                    regime = regime,
                )
            upsert(rollup)
            results.add(rollup)
        }
        log.info(
            "market_index_daily 롤업 완료: tradeDate={} 지수 {}/{}건 UPSERT",
            tradeDate,
            results.size,
            codes.size,
        )
        return results
    }

    // --- private helpers (헌법 §6 — Repository interface 내 body 금지 → 서비스에 위치) ---

    /**
     * 등락률 (%): (close − prevClose)/prevClose × 100. 전일 행 없으면 (close − open)/open × 100.
     * 분모 0 이면 null (spec §3 V86 change_pct).
     */
    private fun changePct(
        close: BigDecimal,
        prevClose: BigDecimal?,
        open: BigDecimal,
    ): BigDecimal? {
        val base = prevClose ?: open
        if (base.signum() == 0) return null
        // 정밀도 보존: ×100 먼저, 그 다음 분모 나눗셈 (scale 4 HALF_UP).
        return close
            .subtract(base)
            .multiply(HUNDRED)
            .divide(base, PCT_SCALE, RoundingMode.HALF_UP)
    }

    /**
     * 변동폭 (%): (high − low)/open × 100. open 0 이면 null.
     */
    private fun rangePct(
        high: BigDecimal,
        low: BigDecimal,
        open: BigDecimal,
    ): BigDecimal? {
        if (open.signum() == 0) return null
        // 정밀도 보존: ×100 먼저, 그 다음 분모 나눗셈 (scale 4 HALF_UP).
        return high
            .subtract(low)
            .multiply(HUNDRED)
            .divide(open, PCT_SCALE, RoundingMode.HALF_UP)
    }

    /**
     * 멱등 UPSERT — UQ(index_code, trade_date) 기존 행 DELETE + INSERT (PerformanceRepositoryAdapter 패턴 정합).
     */
    private fun upsert(rollup: MarketIndexDailyRollup) {
        val now = Instant.now()
        val existing = dailyRepository.findByIndexCodeAndTradeDate(rollup.indexCode, rollup.tradeDate)
        val createdAt = existing?.createdAt ?: now
        if (existing != null) {
            dailyRepository.delete(existing)
            dailyRepository.flush()
        }
        dailyRepository.save(
            MarketIndexDailyJpaEntity(
                indexCode = rollup.indexCode,
                tradeDate = rollup.tradeDate,
                open = rollup.open,
                high = rollup.high,
                low = rollup.low,
                close = rollup.close,
                changePct = rollup.changePct,
                rangePct = rollup.rangePct,
                regime = rollup.regime?.name,
                createdAt = createdAt,
                updatedAt = now,
            ),
        )
    }

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal("100")
        const val PCT_SCALE = 4
    }
}
