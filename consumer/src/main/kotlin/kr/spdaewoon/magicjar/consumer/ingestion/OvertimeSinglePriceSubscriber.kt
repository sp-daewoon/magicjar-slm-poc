package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PostConstruct
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.market.port.SubscriptionPolicy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalTime
import java.time.ZoneId

/**
 * 시간외 단일가 H0STOUP0 dynamic subscribe/unsubscribe 스케줄러 (ADR 0076 Phase 3 B-2).
 *
 * KIS-first 출처:
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
 *   overtime_ccnl_krx() tr_id="H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
 *   국내주식 시간외 단일가(16:00~18:00) 시간대에 실시간체결가 데이터 확인 가능합니다.
 *
 * 40 slot 정합 (ADR 0123 §1-3):
 *   시간외 단일가(16:00~18:00): H0STOUP0(최대 20) + H0STCNI0(1) = 21 ≤ 40.
 *   정규장(09:00~15:30)에는 H0STOUP0 subscribe 0건 → slot 미소모.
 *   최대 구독 종목 수: SubscriptionPolicy.OVERTIME_SINGLE_PRICE_MAX = 20.
 *
 * @Scheduled cron:
 *   16:00 KST — watchlist 종목 H0STOUP0 subscribe
 *   18:00 KST — 전체 H0STOUP0 unsubscribe
 *
 * 부팅 시 16:00~18:00 사이이면 즉시 catch-up subscribe (@PostConstruct).
 *
 * AI 호출 0 — 룰 5 정합.
 */
@Component
class OvertimeSinglePriceSubscriber(
    private val kisWsBridge: KisWebSocketBridge,
    private val watchlistRepository: ConsumerWatchlistRepositoryAdapter,
) {
    private val log = LoggerFactory.getLogger(OvertimeSinglePriceSubscriber::class.java)
    private val kstZone = ZoneId.of("Asia/Seoul")

    // 활성 시간: 16:00 ≤ now < 18:00 KST
    private val overtimeStart: LocalTime = LocalTime.of(16, 0)
    private val overtimeEnd: LocalTime = LocalTime.of(18, 0)

    /**
     * 부팅 시 catch-up subscribe.
     * 현재 KST 시각이 16:00~18:00 사이이면 즉시 watchlist 종목 H0STOUP0 subscribe.
     *
     * WatchlistIngestionWorker.onStartup()보다 늦게 실행되어 KIS WS connect() 완료 후
     * sendSink가 활성 상태임을 가정. (Spring @PostConstruct 순서 — @DependsOn 없이 순서 보장 불가)
     * 실제 운용: sendSink null인 경우 tryEmitNext는 no-op → 다음 checkSession에서 보완.
     */
    @PostConstruct
    fun onStartup() {
        val nowKst = LocalTime.now(kstZone)
        if (isOvertimeWindow(nowKst)) {
            log.info(
                "부팅 시 시간외 단일가 활성 시간 감지 ({}KST) — catch-up H0STOUP0 subscribe",
                nowKst,
            )
            doSubscribeWatchlist()
        } else {
            log.debug("부팅 시 시간외 단일가 비활성 시간 ({}KST) — H0STOUP0 subscribe 생략", nowKst)
        }
    }

    /**
     * 16:00 KST cron — watchlist 종목 H0STOUP0 subscribe.
     *
     * KIS-first: H0STOUP0 활성 시간 16:00~18:00 KST.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     */
    @Scheduled(cron = "0 0 16 * * *", zone = "Asia/Seoul")
    fun onOvertimeStart() {
        log.info("16:00 KST — 시간외 단일가 H0STOUP0 subscribe 시작")
        doSubscribeWatchlist()
    }

    /**
     * 18:00 KST cron — 전체 H0STOUP0 unsubscribe.
     *
     * KIS-first: H0STOUP0 활성 시간 종료 18:00 KST.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     */
    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Seoul")
    fun onOvertimeEnd() {
        log.info("18:00 KST — 시간외 단일가 H0STOUP0 unsubscribe 시작")
        doUnsubscribeAll()
    }

    /**
     * watchlist 종목 중 OVERTIME_SINGLE_PRICE_MAX 이하로 H0STOUP0 subscribe.
     *
     * priority 오름차순(낮은 값 = 높은 우선순위) 정렬 후 최대 20 종목만 선택.
     * 40 slot 예산 — 시간외 단일가 시간대에 H0UNCNT0 체결가 0건이므로 slot 여유 충분.
     */
    private fun doSubscribeWatchlist() {
        try {
            val allEntries = watchlistRepository.findAll()
            if (allEntries.isEmpty()) {
                log.info("H0STOUP0 subscribe 생략 — watchlist 없음")
                return
            }

            // priority ASC (낮은 값 = 높은 우선순위) 정렬 + OVERTIME_SINGLE_PRICE_MAX 제한
            val targetCodes =
                allEntries
                    .sortedBy { it.priority }
                    .take(SubscriptionPolicy.OVERTIME_SINGLE_PRICE_MAX)
                    .map { it.code }

            if (targetCodes.isEmpty()) return

            kisWsBridge.subscribeOvertimeSinglePrice(targetCodes)
            log.info(
                "H0STOUP0 subscribe 완료: {}종목 (전체 watchlist {}건, 최대 {} slot)",
                targetCodes.size,
                allEntries.size,
                SubscriptionPolicy.OVERTIME_SINGLE_PRICE_MAX,
            )
        } catch (e: Exception) {
            log.error("H0STOUP0 subscribe 실패: {}", e.message, e)
        }
    }

    /**
     * 현재 구독 중인 H0STOUP0 종목 전체 unsubscribe.
     */
    private fun doUnsubscribeAll() {
        try {
            val subscribedCodes = kisWsBridge.subscribedOvertimeSinglePriceCodes().toList()
            if (subscribedCodes.isEmpty()) {
                log.info("H0STOUP0 unsubscribe 생략 — 구독 종목 없음")
                return
            }
            kisWsBridge.unsubscribeOvertimeSinglePrice(subscribedCodes)
            log.info("H0STOUP0 unsubscribe 완료: {}종목", subscribedCodes.size)
        } catch (e: Exception) {
            log.error("H0STOUP0 unsubscribe 실패: {}", e.message, e)
        }
    }

    /**
     * 현재 KST 시각이 16:00~18:00(exclusive) 내인지 판별.
     */
    internal fun isOvertimeWindow(nowKst: LocalTime): Boolean = !nowKst.isBefore(overtimeStart) && nowKst.isBefore(overtimeEnd)
}
