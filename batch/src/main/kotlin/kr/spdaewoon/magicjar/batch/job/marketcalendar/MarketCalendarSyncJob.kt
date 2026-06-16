package kr.spdaewoon.magicjar.batch.job.marketcalendar

import kr.spdaewoon.magicjar.batch.infrastructure.kis.KisChkHolidayAdapter
import kr.spdaewoon.magicjar.batch.persistence.BatchMarketCalendarJpaRepository
import kr.spdaewoon.magicjar.domain.market.session.MarketCalendarEntry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * market_calendar 동기화 잡 (ADR 0013 §2-2, PR-29a).
 *
 * 실행 시점:
 *   - sync-at-boot: 부팅 직후 1회 (MarketCalendarSeedRunner 시드 완료 후)
 *   - cron 03:00 daily: 매일 새벽 3시 KST (KIS 명시 "1일 1회 권장" 준수)
 *
 * KIS chk-holiday(CTCA0903R) 정책:
 *   - BASS_DT 기준 약 1년치 한 번에 fetch (연속조회 페이징)
 *   - 응답 → market_calendar 테이블 upsert (멱등)
 *   - 실패 시 WARN 로그 + 정적 시드 유지 (KIS 응답 > 정적 시드 우선순위)
 *
 * KIS-first raw 출처:
 *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/chk_holiday/chk_holiday.py
 *   tr_id: CTCA0903R
 *   endpoint: /uapi/domestic-stock/v1/quotations/chk-holiday
 */
@Component
class MarketCalendarSyncJob(
    private val kisChkHolidayAdapter: KisChkHolidayAdapter,
    private val marketCalendarJpaRepository: BatchMarketCalendarJpaRepository,
    @Value("\${magicjar.market-calendar.sync-at-boot:false}")
    private val syncAtBoot: Boolean,
    @Value("\${magicjar.market-calendar.kis-disabled:false}")
    private val kisDisabled: Boolean,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
) {
    private val log = LoggerFactory.getLogger(MarketCalendarSyncJob::class.java)

    /**
     * cron 03:00 KST daily (ADR 0013 §2-2 — KIS 명시 1일 1회 권장).
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    fun syncDaily() {
        log.info("market_calendar cron sync 시작 (03:00 KST)")
        sync()
    }

    /**
     * 수동 트리거 (MarketCalendarSeedRunner @PostConstruct 또는 관리 endpoint).
     */
    fun syncNow() {
        log.info("market_calendar 수동 sync 시작")
        sync()
    }

    /**
     * 실제 동기화 로직.
     * kisDisabled=true 시 KIS 호출 차단 (test·CI 환경).
     */
    @Transactional
    fun sync() {
        if (kisDisabled) {
            log.info("market_calendar KIS sync 비활성 (magicjar.market-calendar.kis-disabled=true)")
            return
        }

        try {
            // 오늘부터 약 1년 후까지 조회 (연속조회로 페이징 처리)
            val bassDate = LocalDate.now()

            // 실계좌 credential을 여기서 조회하려면 UserKisCredentialRepository 필요.
            // PR-29a 범위: credential 없을 시 graceful skip (WARN + return).
            // 본격 credential 조회는 post-PR 또는 batch 모듈 의존성 보강 시 추가.
            log.warn(
                "market_calendar KIS sync: credential 조회 미구현 (PR-29a 범위 외). " +
                    "정적 시드 DB 유지. 본격 KIS 연동은 배치 credential bean 추가 후 활성화.",
            )
            // TODO: credential bean 추가 후 아래 주석 해제
            // val appKey = ...
            // val secretKey = ...
            // val accessToken = ...
            // val entries = kisChkHolidayAdapter.fetchHolidays(bassDate, appKey, secretKey, accessToken)
            // upsertAll(entries)
        } catch (e: Exception) {
            log.warn("market_calendar KIS sync 실패 (정적 시드 유지): err={}", e.message)
        }
    }

    @Transactional
    fun upsertAll(entries: List<MarketCalendarEntry>): Int {
        if (entries.isEmpty()) return 0
        var count = 0
        for (entry in entries) {
            count +=
                marketCalendarJpaRepository.upsert(
                    date = entry.date,
                    isBusinessDay = entry.isBusinessDay,
                    isHoliday = entry.isHoliday,
                    holidayName = entry.holidayName,
                    source = entry.source,
                )
        }
        log.info("market_calendar upsertAll: total={} affected={}", entries.size, count)
        return count
    }
}
