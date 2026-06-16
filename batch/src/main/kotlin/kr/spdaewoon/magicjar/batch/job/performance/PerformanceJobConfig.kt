package kr.spdaewoon.magicjar.batch.job.performance

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Performance Job 스케줄링 설정 — ADR 0072 §2-2 B-1 정합.
 *
 * @EnableScheduling: DailyPerformanceJob @Scheduled cron 활성화.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Configuration
@EnableScheduling
class PerformanceJobConfig
