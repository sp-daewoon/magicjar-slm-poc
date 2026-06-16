package kr.spdaewoon.magicjar.consumer.monitoring

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * 부팅 직후 LastCandleAtMonitor 1회 실행 — T-CC Phase 1 dogfooding fix 즉시 발화.
 *
 * 목적:
 *   - 한국석유(004090) 일봉 5개월 stale 즉시 검출 → CandleBackfillWorker(ONE_DAY) 자동 트리거
 *   - 18:00 cron 대기 없이 부팅 시점에 한 번 더 일봉 stale 검사
 *   - CandleBootstrapRunner 와의 차이:
 *       * CandleBootstrapRunner: 합산봉(aggregate) 누락 종목 재생성만 (cold-start 백필 폐지 — ADR 0119)
 *       * LastCandleAtMonitor   : 일봉 5 거래일 SLA 위반 검출 + 정밀 backfill (chart-curator SLA 정합)
 *     → 본 runner는 chart-curator SLA 정합 baseline 확보를 위해 별도 발화.
 *
 * ★ ADR 0119 — 1분봉 자동 백필 제거: monitor 가 일봉만 검사하므로 본 runner 도 일봉 SLA baseline 확보 전용.
 *
 * 실행 순서: CandleBootstrapRunner(@Order(10)) 이후 (@Order(20))
 *   - 1차로 합산봉 재생성 (CandleBootstrapRunner)
 *   - 2차로 본 monitor 발화 → 일봉 5 거래일 SLA 위반 검출
 *
 * Toggle: magicjar.monitoring.last-candle-at-bootstrap (default true).
 *   - 운영 emergency 시 false 로 즉시 비활성 가능.
 *
 * 자본 영향 0 — 데이터 정합 보강만 수행.
 */
@Component
@Order(20)
class LastCandleAtBootstrapRunner(
    private val lastCandleAtMonitor: LastCandleAtMonitor,
    @Value("\${magicjar.monitoring.last-candle-at-bootstrap:true}")
    private val enabled: Boolean,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(LastCandleAtBootstrapRunner::class.java)

    override fun run(args: ApplicationArguments) {
        if (!enabled) {
            log.info("LastCandleAtBootstrapRunner skip — magicjar.monitoring.last-candle-at-bootstrap=false")
            return
        }
        log.info("LastCandleAtBootstrapRunner 시작 — 부팅 직후 last_candle_at SLA 검증")
        try {
            val staleEntries = lastCandleAtMonitor.runMonitor("bootstrap")
            log.info(
                "LastCandleAtBootstrapRunner 완료 — staleEntries={}건 (일봉 backfill 비동기 진행 중)",
                staleEntries.size,
            )
        } catch (e: Exception) {
            log.error("LastCandleAtBootstrapRunner 예외 발생 — err={}", e.message, e)
        }
    }
}
