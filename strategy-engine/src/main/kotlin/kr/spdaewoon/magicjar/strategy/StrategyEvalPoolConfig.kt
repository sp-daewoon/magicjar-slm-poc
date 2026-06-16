package kr.spdaewoon.magicjar.strategy

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

/**
 * strategy-eval-pool — 전략 평가 thread pool (ADR 0047 §2-3).
 *
 * - corePoolSize = maxPoolSize = 4 (fixed)
 * - queueCapacity = 100 (backpressure)
 * - rejectedExecution = CallerRunsPolicy (KafkaListener thread 차단으로 backpressure 자연 적용)
 * - graceful shutdown (ADR 0045 + 0047 §2-8): waitForTasksToCompleteOnShutdown=true · awaitTerminationSeconds=2
 *
 * `JvmStrategyEvaluator`가 `@Qualifier("strategyEvalPool")` inject로 사용.
 *
 * spec §6-3 LOCK.
 */
@Configuration
class StrategyEvalPoolConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean("strategyEvalPool")
    fun strategyEvalPool(
        @Value("\${magicjar.dsl.eval-pool.size:4}") poolSize: Int,
        @Value("\${magicjar.dsl.eval-pool.queue:100}") queueSize: Int,
    ): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = poolSize
        executor.maxPoolSize = poolSize
        executor.queueCapacity = queueSize
        executor.setThreadNamePrefix("strategy-eval-")
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(2)
        executor.initialize()
        log.info(
            "Initialized strategy-eval-pool (size={}, queue={}, rejection=AbortPolicy, gracefulShutdown=2s)",
            poolSize,
            queueSize,
        )
        return executor
    }
}
