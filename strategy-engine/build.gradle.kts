// strategy-engine — 라이브러리 모듈 (Spring Boot main 0)
// ADR 0056 + 2026-05-19 main 자동매매 종합 리뷰 P0 정합.
// 의존 방향: domain ← strategy-engine ← api, consumer
// api·consumer 둘 다 같은 JVM 전략 평가기와 DSL parser를 사용한다.

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    // org.springframework.boot plugin 미적용 — 라이브러리 jar, bootJar X
}

dependencies {
    implementation(project(":domain"))

    // Spring Context only — @Component/@Configuration/@Value + ThreadPoolTaskExecutor
    implementation("org.springframework:spring-context")

    // YAML — KisYamlDslParser .kis.yaml import (T-DSL)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Kotlin std
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // slf4j (Logger)
    implementation("org.slf4j:slf4j-api")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "ch.qos.logback")
    }
}

// Spring Boot main 0 — bootJar 비활성화
tasks.jar {
    enabled = true
}
