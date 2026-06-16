package kr.spdaewoon.magicjar.consumer.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RealtimeStatusSchedulerConfiguration 단위 테스트 (결함④ fix · 2026-06-15).
 *
 * 검증:
 * 1. 기본 @Scheduled 스케줄러(`taskScheduler`)와 status 발행 전용 스케줄러(`rt-status-*`)가 물리 분리된다.
 * 2. 한 작업이 기본 스케줄러를 오래 점유해도 status 전용 스케줄러는 별도 thread에서 동작한다(격리 핵심).
 *    → "watchlist 폴링이 오래 걸려도 status 5초 발행 유지" 시나리오의 스레드 격리 근거.
 */
class RealtimeStatusSchedulerConfigurationTest {
    private val config = RealtimeStatusSchedulerConfiguration()

    @Test
    fun `기본 스케줄러는 taskScheduler bean 으로 별도 등록된다`() {
        val scheduler = config.defaultTaskScheduler() as ThreadPoolTaskScheduler
        try {
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
            assertThat(scheduler.threadNamePrefix).isEqualTo("scheduler-")
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `status 전용 스케줄러는 rt-status 단일 스레드 풀이다`() {
        val scheduler = config.realtimeStatusTaskScheduler() as ThreadPoolTaskScheduler
        try {
            // corePoolSize = 설정값(1). poolSize 게터는 live 스레드 수라 작업 실행 전 0 → core 로 검증.
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
            assertThat(scheduler.threadNamePrefix).isEqualTo("rt-status-")
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `전용 스케줄러는 기본 scheduling-1 스레드가 아닌 격리 스레드에서 실행된다`() {
        val scheduler = config.realtimeStatusTaskScheduler() as ThreadPoolTaskScheduler
        try {
            val latch = CountDownLatch(1)
            var threadName = ""
            scheduler.execute {
                threadName = Thread.currentThread().name
                latch.countDown()
            }
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
            // 전용 풀 prefix → 다른 @Scheduled(scheduling-1) 점유와 격리됨을 보장.
            assertThat(threadName).startsWith("rt-status-")
            assertThat(threadName).doesNotContain("scheduling-")
        } finally {
            scheduler.shutdown()
        }
    }
}
