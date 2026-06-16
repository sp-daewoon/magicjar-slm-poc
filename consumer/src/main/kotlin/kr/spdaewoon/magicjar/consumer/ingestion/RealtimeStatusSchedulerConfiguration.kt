package kr.spdaewoon.magicjar.consumer.ingestion

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * 관리자 실시간 상태 발행 전용 스케줄러 (결함④ fix · 2026-06-15).
 *
 * ## 왜 전용 스케줄러인가
 * Spring 기본 `@Scheduled` 는 단일 스레드(`scheduling-1`)에서 모든 작업을 **순차** 실행한다
 * ([ConsumerApplication] 에 커스텀 TaskScheduler 없음). 그 결과 무거운 polling/backfill(과거
 * `WatchlistPollingWorker.tick` 등 — 시세 polling 은 ADR 0124 amend 로 폐지) 이 스레드를 점유하는
 * 동안 [RealtimeStatusSnapshotPublisher.publishScheduled] 의 5초
 * 발행이 밀려 `admin:realtime-status` Redis 키 TTL(60s)이 만료 → 관리자 화면 "실시간 경로 비정상"
 * 빨간 배너(false positive)가 떴다(2026-06-15 REAL 세션 실측, Codex + 오케스트레이터 코드 검증 일치).
 *
 * 본 전용 단일 스레드 스케줄러에 status 발행을 격리하면 **어떤 polling/backfill 이 오래 걸려도** status
 * 5초 cadence 가 유지된다.
 *
 * 주의: TaskScheduler bean 이 이것 하나뿐이면 Spring 이 모든 `@Scheduled` 의 기본 스케줄러로도 이 bean 을
 * 선택한다. 2026-06-15 REAL 배포 후 `WatchlistPollingWorker`·`BalanceReconcileJob` 이 `rt-status-1` 에서
 * 실행되어 전용 격리가 깨진 것을 확인했다. 따라서 별도 기본 `taskScheduler` bean 을 함께 등록하고,
 * status publisher 만 `scheduler = realtimeStatusTaskScheduler` 로 명시 격리한다.
 *
 * 발행 자체는 read-only Redis SET 1회로 가볍다 → pool size 1 로 충분(중첩 실행 fixedDelay 가 자연 방지).
 */
@Configuration
class RealtimeStatusSchedulerConfiguration {
    @Bean(DEFAULT_SCHEDULER)
    @Primary
    fun defaultTaskScheduler(): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("scheduler-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
            initialize()
        }

    @Bean(REALTIME_STATUS_SCHEDULER)
    fun realtimeStatusTaskScheduler(): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("rt-status-")
            // 부팅 직후 스케줄 진입 보장 + graceful shutdown 시 진행 중 발행 완료 대기.
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(5)
            initialize()
        }

    companion object {
        /** Spring `@Scheduled` 기본 탐색 bean name. 이 bean 이 있어야 rt-status 전용 풀이 기본으로 오용되지 않는다. */
        const val DEFAULT_SCHEDULER = "taskScheduler"

        /** [RealtimeStatusSnapshotPublisher] `@Scheduled(scheduler=...)` qualifier (계약 LOCK). */
        const val REALTIME_STATUS_SCHEDULER = "realtimeStatusTaskScheduler"
    }
}
