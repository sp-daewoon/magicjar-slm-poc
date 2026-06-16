package kr.spdaewoon.magicjar.batch

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration
import org.springframework.boot.runApplication

/**
 * magicJar batch 모듈은 Spring Batch 프레임워크 미사용 — @Component + @Scheduled 기반.
 * BatchAutoConfiguration exclude로 jobRegistryBeanPostProcessor non-static WARN 제거 +
 * 불필요한 Spring Batch bean 생성 회피 (부팅 빠름).
 */
@SpringBootApplication(
    scanBasePackages = ["kr.spdaewoon.magicjar"],
    exclude = [BatchAutoConfiguration::class],
)
class BatchApplication

fun main(args: Array<String>) {
    runApplication<BatchApplication>(*args)
}
