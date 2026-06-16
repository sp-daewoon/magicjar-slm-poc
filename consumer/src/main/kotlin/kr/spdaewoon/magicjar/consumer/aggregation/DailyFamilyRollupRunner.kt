package kr.spdaewoon.magicjar.consumer.aggregation

import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_DAY
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_MONTH
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_WEEK
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_YEAR
import kr.spdaewoon.magicjar.domain.market.candle.DailyRollupAggregator
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 1d candle 베이스를 1wk·1mo·1y 로 롤업 + DB UPSERT (ADR 0120 — 기준봉/파생봉 통일 모델).
 *
 * [AggregateBackfillRunner](1m → 3m~12h) 의 1d-family 대칭 컴포넌트. KIS 콜 0(이미 보유한 1d 합산).
 *
 * ## 사용 시점
 *   - [MarketDailyRollupService] 전종목 1회 백필 (admin 트리거) — 년봉 회귀 복구.
 *   - [MarketDailyNightlySnapshotService] R2 저녁/장전 1d snapshot 직후 — 1wk/1mo/1y 최신화.
 *
 * ## 닫힌 봉 + 진행봉 정합 (ADR 0120 §실시간 보존)
 *   본 러너는 DB 의 1d 전량(어제까지 닫힌 봉 + R2 가 적재한 today 1d)을 입력으로 롤업한다.
 *   따라서 "오늘 진행 주/월/년봉" 도 today 1d 까지 반영된 닫힌-스냅샷으로 upsert 된다.
 *   장중 tick 실시간 갱신은 `CandleQueryService.mergeQuoteLastCurrentCandle`(quote:last 병합)가
 *   별도로 최신 봉 위에 덮으므로, 본 롤업 결과를 라이브 merge 가 그대로 갱신한다(회귀 0).
 *
 * 원칙:
 *   - in-memory 롤업 (외부 호출 0). 10년 1d ~2,450 row → 롤업 가벼움(<10ms/종목 예상).
 *   - DB UPSERT (ON CONFLICT DO UPDATE) — 1d 교정 후 상위 봉 재생성 허용.
 *   - 1d 0건 종목은 즉시 반환 (guard). mock 합성 0 (DailyRollupAggregator 가 구간 내 1d 0건 시 bar 생략).
 *
 * @param candleRepository CandleRepository 도메인 포트 (consumer 구현 주입)
 */
@Component
class DailyFamilyRollupRunner(
    private val candleRepository: CandleRepository,
) {
    private val log = LoggerFactory.getLogger(DailyFamilyRollupRunner::class.java)

    companion object {
        /** ADR 0120 — 1d 에서 롤업 생성하는 상위 timeframe 3종 (KIS 직접 fetch 폐지). */
        val TARGET_INTERVALS = listOf(ONE_WEEK, ONE_MONTH, ONE_YEAR)

        /**
         * ADR 0120 amend (2026-06-10) — R2 incremental 기본 윈도우(최근 거래일 수).
         *
         * 매일 1wk/1mo/1y 전체 history(10년 ~2,450봉)를 재생성하던 무지성 full-regen 을 대체한다.
         * 과거 닫힌 주/월/년봉은 1d 가 불변이므로 재계산이 낭비 — **갱신 필요한 봉만** 재롤업하면 충분하다.
         *
         * 400 거래일(≈1.6 calendar year)이면 가장 긴 경계인 **연봉**도 안전 커버:
         *   - 올해 진행 연봉(1y) — today 1d 까지 반영해 매일 갱신 필요.
         *   - 작년 닫힌 연봉 — 연초 직후(1월) 작년 봉이 막 닫혔을 때 누락 없이 1회 더 갱신.
         *   주봉·월봉 경계(주초·월초 직후)는 400 거래일 안에 항상 포함 → 경계 갱신 누락 0.
         * 산출 봉만 upsert 하므로 양 ~95% 절감(1y 는 전체~30봉 → 2봉, 1mo·1wk 도 윈도우 내 봉만).
         */
        const val DEFAULT_INCREMENTAL_RECENT_DAYS = 400
    }

    data class IntervalRollupResult(
        val upserted: Int,
        val latestBar: Candle?,
    )

    /**
     * 종목 1d candle 전량을 1wk·1mo·1y 로 롤업 + DB UPSERT (full-regen — admin 1회 복구용).
     *
     * @param code 종목 코드 (KIS 6자리)
     * @return interval code → upsert 건수 (운영 관측)
     */
    fun rollup(code: String): Map<String, Int> = rollupDetailed(code).mapValues { it.value.upserted }

    /**
     * 종목 1d → 1wk/1mo/1y 롤업 + DB UPSERT.
     *
     * @param code 종목 코드 (KIS 6자리)
     * @param recentDays 최근 N 거래일만 읽어 롤업(incremental). `null`이면 1d 전량 full-regen.
     *   - **R2 저녁/장전**: [DEFAULT_INCREMENTAL_RECENT_DAYS](400) — 갱신 필요한 경계 봉만(연봉 경계까지 커버).
     *   - **admin /run**: `null`(full-regen) — 1회성 복구 + 수동 재실행(전 history 정합 보증).
     *   incremental 은 윈도우 밖 과거 봉을 건드리지 않는다(과거 1d 불변 → 과거 상위봉 불변 → 재계산 불필요).
     *   윈도우 경계에 걸친 주/월/년 봉은 윈도우 안에 그 구간의 1d 가 일부만 들어와도 같은 그룹키로 묶여
     *   해당 구간 전체 1d 가 들어온 full-regen 과 동일한 OHLCV 가 되도록 보장된다(아래 ★ 경계 정확성).
     *
     * ## ★ 경계 정확성 (full-regen 과 동일 결과 보증)
     *   incremental 윈도우(최근 400 거래일)의 가장 이른 봉이 어떤 주/월/년의 "중간"일 수 있다.
     *   그 경우 그 경계 봉의 open/누적 거래량이 full-regen(구간 전체 1d 포함)과 달라질 위험이 있다.
     *   400 거래일 ≈ 1.6년이므로 **연봉**은 "올해 + 작년 일부"만 재계산 대상이 되는데,
     *   작년 연봉은 이미 닫혀 직전 R2/admin full-regen 으로 정확히 적재되어 있고(불변), 윈도우 부분 재계산이
     *   그 값을 부정확하게 덮을 수 있다 → 이를 막기 위해 **윈도우의 가장 이른 봉이 속한 그룹 전체가 윈도우 안에
     *   완전히 포함되지 않는 경계 그룹은 upsert 대상에서 제외**한다([dropLeadingPartialGroup]).
     *   → 부분 그룹(작년 연봉의 끝자락 등)은 그대로 두고(불변), 완전 포함 그룹만 갱신 → full-regen 과 동일.
     */
    fun rollupDetailed(
        code: String,
        recentDays: Int? = null,
    ): Map<String, IntervalRollupResult> {
        val oneDayCandles =
            try {
                if (recentDays != null) {
                    candleRepository.findLatestByCodeAndInterval(code, ONE_DAY, recentDays)
                } else {
                    candleRepository.findByCodeAndInterval(code, ONE_DAY)
                }
            } catch (e: Exception) {
                log.warn("DailyFamilyRollupRunner: 1d 조회 실패 — skip: code={} err={}", code, e.message)
                return emptyMap()
            }
        if (oneDayCandles.isEmpty()) {
            log.debug("DailyFamilyRollupRunner: 1d candle 없음 — 롤업 skip: code={}", code)
            return emptyMap()
        }

        // incremental(recentDays!=null) + 윈도우가 full 을 잘랐을 때만 경계 부분 그룹 drop.
        // 반환 size < recentDays 면 그 종목 1d 전량이 윈도우보다 적음(신규 상장 등) → 부분 그룹 없음 → drop 0.
        val windowTruncated = recentDays != null && oneDayCandles.size >= recentDays

        val result = mutableMapOf<String, IntervalRollupResult>()
        for (target in TARGET_INTERVALS) {
            try {
                val rolledAll = DailyRollupAggregator.rollup(oneDayCandles, target)
                // ★ 경계 정확성: 윈도우가 잘렸으면 가장 이른 그룹(부분 그룹 가능)을 upsert 에서 제외.
                //   그 봉은 윈도우 밖 더 이른 1d 를 포함할 수 있어 open/volume 이 full-regen 과 다를 수 있다.
                //   이미 직전 full-regen/incremental 으로 정확 적재된 불변 봉이므로 건드리지 않는 게 정합.
                val rolled = if (windowTruncated) rolledAll.drop(1) else rolledAll
                if (rolled.isNotEmpty()) {
                    val upserted = candleRepository.upsertAll(rolled)
                    // ★ 고아 앵커 정리 (ADR 0120 amend — 2026-06-11 P1 fix).
                    //   진행 월/년봉 앵커가 전진(6/9→6/10)하면 옛 앵커 행이 같은 버킷에 고아로 남는다.
                    //   이번에 산출한 각 봉의 기간 버킷 안에서 산출 앵커(ts) ≠ 인 같은 (code,interval) 행을 삭제.
                    //   버킷 경계 + 앵커 불일치 이중 조건이라 윈도우 밖 과거 닫힌 봉은 무손상(광역 DELETE 아님).
                    val staleDeleted = cleanupStaleAnchors(code, target, rolled)
                    result[target.code] = IntervalRollupResult(upserted = upserted, latestBar = rolled.last())
                    log.debug(
                        "DailyFamilyRollupRunner interval 완료: code={} interval={} rolledAll={} upserted={} staleDeleted={} windowTruncated={}",
                        code,
                        target.code,
                        rolledAll.size,
                        upserted,
                        staleDeleted,
                        windowTruncated,
                    )
                } else {
                    result[target.code] = IntervalRollupResult(upserted = 0, latestBar = null)
                }
            } catch (e: Exception) {
                log.warn(
                    "DailyFamilyRollupRunner interval 실패: code={} interval={} err={}",
                    code,
                    target.code,
                    e.message,
                )
                result[target.code] = IntervalRollupResult(upserted = -1, latestBar = null)
            }
        }

        val total =
            result.values
                .map { it.upserted }
                .filter { it >= 0 }
                .sum()
        log.info(
            "DailyFamilyRollupRunner 완료: code={} 1dCount={} totalUpserted={} breakdown={}",
            code,
            oneDayCandles.size,
            total,
            result.mapValues { it.value.upserted },
        )
        return result
    }

    /**
     * 이번 롤업이 산출한 봉들의 기간 버킷 안에서 산출 앵커 외 고아 행을 삭제 (ADR 0120 amend — 2026-06-11).
     *
     * 각 산출 봉마다 [DailyRollupAggregator.bucketRange] 로 KST 기간 버킷 `[start, end)` 를 구하고,
     * `ts ≠ 산출 앵커(bar.ts)` 인 같은 (code, interval) 행을 [CandleRepository.deleteStaleBucketAnchors] 로 삭제.
     *
     * 같은 실행 안에서 두 봉의 버킷이 겹치는 일은 없다(롤업이 버킷당 정확히 1봉 산출). 따라서 버킷별 1회 호출.
     * 삭제 실패는 정합 위협이 아니므로(다음 실행에서 재정리) 종목 단위 롤업을 막지 않고 0 으로 흡수한다.
     *
     * @return 이 interval 에서 삭제된 고아 row 합계.
     */
    private fun cleanupStaleAnchors(
        code: String,
        interval: CandleInterval,
        rolled: List<Candle>,
    ): Int {
        var deleted = 0
        for (bar in rolled) {
            try {
                val (start, end) = DailyRollupAggregator.bucketRange(bar.ts, interval)
                deleted +=
                    candleRepository.deleteStaleBucketAnchors(
                        code = code,
                        interval = interval,
                        bucketStart = start.toJavaInstant(),
                        bucketEnd = end.toJavaInstant(),
                        keepTs = bar.ts.toJavaInstant(),
                    )
            } catch (e: Exception) {
                log.warn(
                    "DailyFamilyRollupRunner 고아 정리 실패(skip): code={} interval={} anchorTs={} err={}",
                    code,
                    interval.code,
                    bar.ts,
                    e.message,
                )
            }
        }
        return deleted
    }
}
