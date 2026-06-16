package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 일봉 패밀리 wipe 서비스 — ADR 0118 amend MIGRATE 모드 전용.
 *
 * ## ★ 가장 중요한 안전 제약 (1m·5m 영구 손실 방지)
 *   wipe 대상은 **[MIGRATE_WIPE_INTERVALS] 화이트리스트로 LOCK** — `1d`/`1wk`/`1mo`/`1y` 4종(REST 재생성 가능)
 *   만 삭제한다. `1m`·`5m` 등 **실시간 누적분은 KIS REST 로 복구 불가(영구 손실)** 이므로 화이트리스트에서
 *   원천 배제한다. 이 상수는 캠페인·서비스 어디서도 외부 입력으로 덮어쓸 수 없는 컴파일-타임 LOCK 이다.
 *
 *   회귀 가드: init 단언이 1m·5m 가 화이트리스트에 절대 포함되지 않음을 부팅 시점에 확인한다(상수가
 *   실수로 오염되면 컴포넌트 생성 단계에서 즉시 실패 → 런타임 누적분 삭제 사고를 부팅으로 끌어올림).
 *
 * ## 배치 삭제 (대량 테이블 long-transaction 회피)
 *   [wipeDailyFamily] 는 [batchSize] 청크 단위 [ConsumerCandleJpaRepository.deleteByIntervalInBatch] 를
 *   반환 0(또는 batchSize 미만)이 될 때까지 반복한다. 각 청크는 독립 트랜잭션(REQUIRES_NEW)이라
 *   한 청크 실패가 이미 삭제한 청크를 롤백하지 않는다(재개 가능·멱등). 무한 루프 방어로 [maxBatches] 상한.
 *
 * 자본 영향 0 — 데이터 마이그레이션만(주문 0). KIS 콜 0(순수 DELETE).
 */
@Service
class MarketDailyWipeService(
    private val candleJpaRepository: ConsumerCandleJpaRepository,
    // self proxy — REQUIRES_NEW 청크 트랜잭션이 self-invocation(프록시 우회)으로 무력화되는 함정 회피.
    // ObjectProvider lazy 조회로 생성자 순환 의존 없이 프록시 경유 호출 확보.
    private val selfProvider: ObjectProvider<MarketDailyWipeService>,
) {
    private val log = LoggerFactory.getLogger(MarketDailyWipeService::class.java)

    companion object {
        /**
         * ★ wipe 화이트리스트 LOCK — REST 재생성 가능 일봉 패밀리 4종만.
         * 1m·5m(실시간 누적·복구 불가)는 절대 포함 금지. 결재 방향 §"wipe 범위" LOCK.
         */
        val MIGRATE_WIPE_INTERVALS: List<String> =
            listOf(
                CandleInterval.ONE_DAY.code, // 1d
                CandleInterval.ONE_WEEK.code, // 1wk
                CandleInterval.ONE_MONTH.code, // 1mo
                CandleInterval.ONE_YEAR.code, // 1y
            )

        /** 화이트리스트에서 절대 배제되어야 하는 보호 대상(실시간 누적·KIS REST 복구 불가). */
        private val PROTECTED_INTERVALS: Set<String> =
            setOf(
                CandleInterval.ONE_MIN.code, // 1m
                CandleInterval.FIVE_MIN.code, // 5m
            )

        const val DEFAULT_BATCH_SIZE = 10_000
        const val DEFAULT_MAX_BATCHES = 100_000
    }

    init {
        // 회귀 가드 — 1m·5m 가 wipe 화이트리스트에 섞이면 부팅 즉시 실패(런타임 누적분 삭제 사고 차단).
        val leaked = MIGRATE_WIPE_INTERVALS.filter { it in PROTECTED_INTERVALS }
        require(leaked.isEmpty()) {
            "MIGRATE_WIPE_INTERVALS 가 보호 대상(1m·5m)을 포함함: $leaked — 실시간 누적분 영구 손실 위험"
        }
    }

    /**
     * 일봉 패밀리(1d·1wk·1mo·1y) 전 source wipe — 배치 삭제. 1m·5m 는 절대 삭제되지 않음.
     *
     * @return 삭제된 총 row 수(wipedRows — 운영 evidence).
     */
    fun wipeDailyFamily(
        batchSize: Int = DEFAULT_BATCH_SIZE,
        maxBatches: Int = DEFAULT_MAX_BATCHES,
    ): Long {
        val effectiveBatch = batchSize.coerceIn(1, 100_000)
        val effectiveMaxBatches = maxBatches.coerceAtLeast(1)
        log.warn(
            "MarketDailyWipeService wipe 시작 — 일봉 패밀리(1d·1wk·1mo·1y) 전 source 삭제 (1m·5m 보존): batchSize={} intervals={}",
            effectiveBatch,
            MIGRATE_WIPE_INTERVALS,
        )
        // 프록시 경유 self 호출 — REQUIRES_NEW 청크 트랜잭션 보장(self-invocation 함정 회피).
        val self = selfProvider.getObject()
        var total = 0L
        var batches = 0
        while (batches < effectiveMaxBatches) {
            val deleted = self.deleteOneBatch(effectiveBatch)
            total += deleted
            batches++
            if (deleted < effectiveBatch) break // 남은 대상 < batchSize → 마지막 청크
        }
        if (batches >= effectiveMaxBatches) {
            log.warn(
                "MarketDailyWipeService wipe maxBatches({}) 도달 — 미완 가능. 재호출 시 이어서 삭제: 누적 wiped={}",
                effectiveMaxBatches,
                total,
            )
        }
        log.warn("MarketDailyWipeService wipe 완료 — wipedRows={} batches={}", total, batches)
        return total
    }

    /**
     * 단일 청크 삭제 — 독립 트랜잭션(REQUIRES_NEW). 한 청크 실패가 이미 커밋된 청크를 롤백하지 않음.
     * 화이트리스트는 [MIGRATE_WIPE_INTERVALS] 고정 — 호출자가 임의 interval 을 주입할 수 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deleteOneBatch(batchSize: Int): Int = candleJpaRepository.deleteByIntervalInBatch(MIGRATE_WIPE_INTERVALS, batchSize)
}
