package kr.spdaewoon.magicjar.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * api 모듈 Spring Boot 엔트리.
 *
 * JPA scan 명시는 `kr.spdaewoon.magicjar.api.config.JpaScanConfiguration`에 분리 — `ApiApplication` 본체에
 * `@EnableJpaRepositories`를 직접 붙이면 `@WebMvcTest` 슬라이스가 Application class를 로드할 때
 * JPA stack(entityManagerFactory)이 강제 활성화되어 80건 회귀. 별도 `@Configuration` class로 분리하면
 * `@WebMvcTest`의 TypeExcludeFilters가 자동 제외하므로 회귀 0.
 */
@SpringBootApplication(scanBasePackages = ["kr.spdaewoon.magicjar"])
@EnableScheduling
class ApiApplication

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args)
}
