package kr.spdaewoon.magicjar.consumer.bootstrap

import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.ManagedCandleUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * 부팅 시 managed universe 합산봉(aggregate) 공백 종목 자동 보충 (KIS-only 정책).
 *
 * ★ proactive 1m cold-start 백필 폐지 (ADR 0119 · 2026-06-09):
 *   기존에는 1d==0 또는 1m 부족·stale 종목에 ColdStartExecutor.submit(code)로 126일 1m 무더기 KIS
 *   백필을 트리거했다. 2026-06-09 전종목 일봉 마이그레이션(일봉 wipe → 1d==0) 시 managed 종목 전체가
 *   동시에 1m cold-start storm(126 거래일×~630콜/종목)을 유발 → KIS 콜 폭증.
 *
 *   분석 결과 proactive 1m 백필은 과수집이다:
 *     - 실시간 1m은 KIS WS 구독 종목(TickToMinuteAggregator, 최대 PRICE_TICK_MAX=30+NXT)만 매분 마감.
 *     - 일봉/주봉/월/년봉은 전종목 일봉 universe(R-BF/R2 · ADR 0118)가 담당 → cold-start daily 역할 무용.
 *     - 과거 1m 정밀 갭은 차트 진입 on-demand(SymbolDetailMinuteGapFillService) 또는 admin 수동 도구만 수행.
 *
 *   따라서 부팅 cold-start 트리거(1d==0·1m 부족·stale 분기)를 전면 제거한다.
 *
 * 잔존 역할 — 합산봉 재생성 (KIS 호출 0):
 *   - 1m 데이터가 이미 존재하나 합산봉(3m·5m·15m·30m·90m·1h·4h·12h)이 미생성된 종목에 대해
 *     aggregateBackfillRunner.backfill(code)로 기존 1m 기반 합산만 재실행한다(외부 KIS fetch 없음).
 *   - 배포 후 첫 부팅에서 합산 누락 자동 보강 보장 (ADR 0062 §9 정합).
 *
 * 실행 순서: PositionBootstrapRunner 이후 실행 (@Order(10)).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
@Order(10)
class CandleBootstrapRunner(
    private val managedUniverseProvider: ManagedCandleUniverseProvider,
    private val candleRepository: CandleRepository,
    private val aggregateBackfillRunner: AggregateBackfillRunner,
    private val instrumentJpaRepository: ConsumerInstrumentJpaRepository,
    private val auditTrailJpaRepository: ConsumerAuditTrailJpaRepository,
    // 부팅 시 managed universe 합산봉 재생성 트리거 kill-switch (default 활성).
    // ADR 0119로 cold-start 백필 트리거는 제거됐고, 본 toggle 은 잔존 aggregate-only 경로(KIS 호출 0)만 게이트한다.
    // 운영 점검/마이그레이션 창에서 false 로 두면 부팅 시 합산 재생성 read 부하도 건너뛴다.
    @Value("\${magicjar.candle.bootstrap.enabled:true}") private val enabled: Boolean = true,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(CandleBootstrapRunner::class.java)

    companion object {
        /**
         * 1m candle 최소 임계치 — 합산 재생성 의미 판단용.
         * ADR 0063 threshold=700 정합: NXT cover(08:00~19:55) 기준 1일 최대 720건.
         * 700건 미만이면 합산 source 가 사실상 비어 있으므로 aggregate-only 트리거도 skip.
         */
        private const val MIN_1M_CANDLE_THRESHOLD = 700L
    }

    override fun run(args: ApplicationArguments) {
        if (!enabled) {
            log.warn("CandleBootstrapRunner 비활성(magicjar.candle.bootstrap.enabled=false) — 부팅 합산 재생성 트리거 건너뜀")
            return
        }
        log.info("CandleBootstrapRunner 시작 — managed universe 합산봉 누락 종목 감지 (cold-start 백필은 ADR 0119로 폐지)")
        val universeEntries = managedUniverseProvider.resolve()
        if (universeEntries.isEmpty()) {
            log.info("CandleBootstrapRunner — managed universe 종목 없음, 건너뜀")
            return
        }

        var aggregateOnlyCount = 0
        var skippedCount = 0

        for (entry in universeEntries) {
            val code = entry.code

            // 거래정지·정리매매 종목 skip (KIS API 응답 0 — 외부 결함, 시스템 보강 불가)
            // instrument 조회 실패(stub/UNKNOWN) 시에는 skip 하지 않고 cold-start 시도
            val instrument = instrumentJpaRepository.findById(code).orElse(null)
            if (instrument != null && (instrument.tradingHalt || instrument.liquidation)) {
                log.info(
                    "CandleBootstrapRunner — 거래정지·정리매매 종목 skip: code={} halt={} liquidation={}",
                    code,
                    instrument.tradingHalt,
                    instrument.liquidation,
                )
                try {
                    val detail =
                        """{"code":"$code","reason":"TRADING_HALT",""" +
                            """"tradingHalt":${instrument.tradingHalt},"liquidation":${instrument.liquidation}}"""
                    auditTrailJpaRepository.save(
                        ConsumerAuditTrailJpaEntity(
                            actor = "system",
                            action = "KIS_COLD_START_SKIPPED",
                            entityType = "candle",
                            entityId = code,
                            detailJson = detail,
                        ),
                    )
                } catch (e: Exception) {
                    log.warn("CandleBootstrapRunner skip audit 저장 실패: code={} err={}", code, e.message)
                }
                skippedCount++
                continue
            }

            val count1m = candleRepository.countByCodeAndInterval(code, CandleInterval.ONE_MIN)

            // ★ ADR 0119 — cold-start 백필 트리거(1d==0·1m 부족·stale 분기) 전면 제거.
            //   1m source 가 사실상 비어 있으면(< threshold) 합산 재생성도 의미 없으므로 skip
            //   (과거 1m 백필은 더 이상 하지 않음 — on-demand 전용).
            if (count1m < MIN_1M_CANDLE_THRESHOLD) {
                log.debug(
                    "CandleBootstrapRunner — 1m source 부족(count={} < threshold={}), 합산 재생성 skip: code={} " +
                        "(과거 1m 백필은 on-demand 전용 — ADR 0119)",
                    count1m,
                    MIN_1M_CANDLE_THRESHOLD,
                    code,
                )
                continue
            }

            // ADR 0062 §9 — 1m 충분하나 합산 candle 일부가 없으면 aggregation만 재실행 (KIS 호출 0).
            // 배포 후 첫 부팅(1m 적재된 상태)에서 자동 트리거됨.
            val missingAggregateIntervals =
                AggregateBackfillRunner.TARGET_INTERVALS.filter { interval ->
                    candleRepository.countByCodeAndInterval(code, interval) == 0L
                }
            if (missingAggregateIntervals.isNotEmpty()) {
                log.info(
                    "CandleBootstrapRunner — 1m 충분하나 합산봉 누락, aggregate 트리거: code={} 1m={} missing={}",
                    code,
                    count1m,
                    missingAggregateIntervals.map { it.code },
                )
                try {
                    aggregateBackfillRunner.backfill(code)
                    aggregateOnlyCount++
                } catch (e: Exception) {
                    log.warn("CandleBootstrapRunner aggregate 실패: code={} err={}", code, e.message)
                }
            } else {
                log.debug(
                    "CandleBootstrapRunner — 합산봉 충분: code={} 1m={} aggregateTargets={}",
                    code,
                    count1m,
                    AggregateBackfillRunner.TARGET_INTERVALS.map { it.code },
                )
            }
        }
        log.info(
            "CandleBootstrapRunner 완료 — aggregate-only {}건 / skip {}건 (total universe={}건, cold-start 백필 폐지 — ADR 0119)",
            aggregateOnlyCount,
            skippedCount,
            universeEntries.size,
        )
    }
}
