package kr.spdaewoon.magicjar.consumer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * consumer 모듈 Spring Boot 엔트리.
 *
 * JPA scan 명시는 `kr.spdaewoon.magicjar.consumer.config.JpaScanConfiguration`에 분리. api와 동일 사유 —
 * Application class 직접 명시 시 `@WebMvcTest` 회귀 risk. 별도 `@Configuration` 분리로 회귀 0.
 */
@SpringBootApplication(scanBasePackages = ["kr.spdaewoon.magicjar"])
@EnableScheduling
class ConsumerApplication

fun main(args: Array<String>) {
    runApplication<ConsumerApplication>(*args)
}
