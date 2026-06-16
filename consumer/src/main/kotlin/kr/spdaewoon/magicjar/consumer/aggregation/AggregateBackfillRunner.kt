package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleAggregator
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.FIFTEEN_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.FIVE_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.FOUR_HOUR
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.NINETY_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_HOUR
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.THIRTY_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.THREE_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.TWELVE_HOUR
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 1m candle 베이스를 3m·5m·15m·30m·90m·1h·4h·12h 로 일괄 합산 + DB UPSERT.
 *
 * 사용 시점:
 *   - [kr.spdaewoon.magicjar.consumer.bootstrap.CandleBootstrapRunner] 부팅 시 합산봉 누락 재생성 (KIS 호출 0)
 *   - [kr.spdaewoon.magicjar.consumer.application.market.DailyCloseFinalizer] 16:00 KST 당일 갱신
 *   - admin REST endpoint 수동 트리거 (단발 운영)
 *   (구: ColdStartExecutor 1m 적재 직후 — ADR 0119로 cold-start 은퇴)
 *
 * TradingView 정합:
 *   - 입력 1m 중 NXT 통합 세션(KST 08:00~20:00)을 합산한다.
 *   - KRX-only 종목은 해당 시간대 1m 데이터가 없으므로 자연스럽게 정규장만 남는다.
 *   - boundary 정렬은 CandleAggregator 내부 처리 — 본 클래스는 위임만.
 *
 * ADR 0019 §2-5 합성 폐기 결정 **revoke** (ADR 0062 채택 — 2026-05-11):
 *   CandleAggregator 부활 + 백엔드 precomputed(A-1) 결정.
 *
 * 원칙:
 *   - in-memory aggregation (외부 호출 0, ~100ms/종목 예상).
 *   - DB UPSERT 는 1m gap fill 이후 기존 합산봉 교정을 허용한다.
 *   - 1m 0건 종목은 즉시 반환 (guard).
 *   - mock 합성 금지 (feedback_no_mock_data 정합) — CandleAggregator boundary 내 1m 0건 시 bar 생략.
 *
 * @param candleRepository CandleRepository 도메인 포트 (consumer 모듈 구현 주입)
 */
@Component
class AggregateBackfillRunner(
    private val candleRepository: CandleRepository,
) {
    private val log = LoggerFactory.getLogger(AggregateBackfillRunner::class.java)

    companion object {
        /**
         * ADR 0062 UI 매트릭스 — 활성화 interval.
         * (ONE_DAY·ONE_WEEK·ONE_MONTH·ONE_YEAR 는 KIS 직접 수집이므로 합산 제외)
         */
        val TARGET_INTERVALS =
            listOf(
                THREE_MIN,
                FIVE_MIN,
                FIFTEEN_MIN,
                THIRTY_MIN,
                NINETY_MIN,
                ONE_HOUR,
                FOUR_HOUR,
                TWELVE_HOUR,
            )
    }

    data class IntervalBackfillResult(
        val inserted: Int,
        val latestBar: Candle?,
    )

    /**
     * 종목 1m candle 전량을 3m·5m·15m·30m·90m·1h·4h·12h 로 합산 + DB UPSERT.
     *
     * 흐름:
     *   1. DB 에서 해당 종목의 1m candle 전량 조회 (ADR 0105 기본 6개월 기준 KRX ~49k/NXT ~91k row)
     *   2. [CandleAggregator.aggregate] 로 각 target interval 합산
     *   3. [CandleRepository.upsertAll] 로 DB upsert (ON CONFLICT DO UPDATE)
     *   4. 결과 로그
     *
     * @param code 종목 코드 (KIS 6자리, 예: "000660")
     * @return 각 interval 별 INSERT 건수 map (로깅 목적 — 운영 상태 확인)
     */
    fun backfill(code: String): Map<String, Int> = backfillDetailed(code).mapValues { it.value.inserted }

    fun backfillDetailed(code: String): Map<String, IntervalBackfillResult> {
        val oneMinCandles = candleRepository.findByCodeAndInterval(code, ONE_MIN)
        if (oneMinCandles.isEmpty()) {
            log.info("AggregateBackfillRunner: 1m candle 없음 — 합산 skip: code={}", code)
            return emptyMap()
        }

        log.info(
            "AggregateBackfillRunner 시작: code={} 1m count={}",
            code,
            oneMinCandles.size,
        )

        val result = mutableMapOf<String, IntervalBackfillResult>()

        for (target in TARGET_INTERVALS) {
            try {
                val aggregated = CandleAggregator.aggregate(oneMinCandles, target)
                if (aggregated.isNotEmpty()) {
                    val inserted = candleRepository.upsertAll(aggregated)
                    result[target.code] = IntervalBackfillResult(inserted = inserted, latestBar = aggregated.last())
                    log.debug(
                        "AggregateBackfillRunner interval 완료: code={} interval={} aggregated={} inserted={}",
                        code,
                        target.code,
                        aggregated.size,
                        inserted,
                    )
                } else {
                    result[target.code] = IntervalBackfillResult(inserted = 0, latestBar = null)
                    log.warn(
                        "AggregateBackfillRunner interval 빈 결과: code={} interval={}",
                        code,
                        target.code,
                    )
                }
            } catch (e: Exception) {
                log.warn(
                    "AggregateBackfillRunner interval 실패: code={} interval={} err={}",
                    code,
                    target.code,
                    e.message,
                )
                result[target.code] = IntervalBackfillResult(inserted = -1, latestBar = null)
            }
        }

        val totalInserted =
            result.values
                .map { it.inserted }
                .filter { it >= 0 }
                .sum()
        log.info(
            "AggregateBackfillRunner 완료: code={} totalInserted={} breakdown={}",
            code,
            totalInserted,
            result.mapValues { it.value.inserted },
        )
        return result
    }
}
