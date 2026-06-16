package kr.spdaewoon.magicjar.batch.bootstrap

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.batch.job.marketcalendar.MarketCalendarSyncJob
import kr.spdaewoon.magicjar.batch.persistence.BatchMarketCalendarJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * market_calendar 정적 시드 로더 (ADR 0013 §2-2, PR-29a).
 *
 * 시드 파일: batch/src/main/resources/market-calendar-seed.json
 *   - 2026~2027 KRX 공휴일 약 30건
 *   - 주말은 KrxMarketSessionResolver에서 자동 처리 (시드 미포함)
 *
 * 실행 순서:
 *   Order(2) — InstrumentSeedRunner(Order 1) 이후
 *   1. DB market_calendar row count 확인
 *   2. 0건 → 정적 시드 INSERT
 *   3. sync-at-boot=true → KIS chk-holiday sync (MarketCalendarSyncJob.syncNow)
 *
 * 정책 (ADR 0013 §3-7 §5):
 *   - KIS chk-holiday 응답 > 정적 시드 (KIS의 임시휴장·공휴일 흡수)
 *   - 시드는 KIS API 미연결 환경 fallback 전용
 */
@Component
@Order(2)
class MarketCalendarSeedRunner(
    private val marketCalendarJpaRepository: BatchMarketCalendarJpaRepository,
    private val marketCalendarSyncJob: MarketCalendarSyncJob,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.market-calendar.sync-at-boot:false}")
    private val syncAtBoot: Boolean,
    @Value("\${magicjar.market-calendar.seed-path:market-calendar-seed.json}")
    private val seedPath: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(MarketCalendarSeedRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val count = marketCalendarJpaRepository.count()
        if (count == 0L) {
            log.info("[market-calendar-seed] DB 0건 — 정적 시드 INSERT 시작")
            loadAndInsertSeed()
        } else {
            log.info("[market-calendar-seed] DB {}건 존재 — 시드 skip", count)
        }

        if (syncAtBoot) {
            log.info("[market-calendar-seed] sync-at-boot=true — KIS chk-holiday sync 실행")
            try {
                marketCalendarSyncJob.syncNow()
            } catch (e: Exception) {
                log.warn("[market-calendar-seed] sync-at-boot KIS sync 실패 (정적 시드 유지): err={}", e.message)
            }
        }
    }

    private fun loadAndInsertSeed() {
        val resource = ClassPathResource(seedPath)
        if (!resource.exists()) {
            log.warn("[market-calendar-seed] 시드 파일 없음: {} — skip", seedPath)
            return
        }

        // 시드 JSON 구조: {"holidays": [...]}
        @Suppress("UNCHECKED_CAST")
        val root =
            resource.inputStream.use { stream ->
                objectMapper.readValue(stream, Map::class.java)
            } as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val holidays = root["holidays"] as? List<Map<String, Any?>> ?: emptyList()

        var successCount = 0
        var failCount = 0

        for (holiday in holidays) {
            try {
                val dateStr = holiday["date"]?.toString() ?: continue
                val date = LocalDate.parse(dateStr)
                val isBusinessDay = holiday["is_business_day"]?.toString()?.toBoolean() ?: false
                val isHoliday = holiday["is_holiday"]?.toString()?.toBoolean() ?: true
                val holidayName = holiday["holiday_name"]?.toString()

                marketCalendarJpaRepository.upsert(
                    date = date,
                    isBusinessDay = isBusinessDay,
                    isHoliday = isHoliday,
                    holidayName = holidayName,
                    source = "STATIC_SEED",
                )
                successCount++
            } catch (e: Exception) {
                log.warn("[market-calendar-seed] INSERT 실패: date={} err={}", holiday["date"], e.message)
                failCount++
            }
        }

        log.info("[market-calendar-seed] 시드 INSERT 완료 — success={}건, fail={}건", successCount, failCount)
    }
}
