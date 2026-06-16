// indicator-impl — 라이브러리 모듈 (Spring Boot main 0)
// architect spec §4-5 (2026-05-18-indicator-impl-module-spec.md) + ADR 0083 정합
// 의존 방향: domain ← indicator-impl ← api, consumer
// api·consumer 둘 다 implementation(project(":indicator-impl")) 의존
// NativeIndicatorSource·KisStrategyBuilderSource 두 @Component 빈을 양쪽 ApplicationContext에 노출
// pattern-engine 패턴 1:1 정합 (ADR 0054)

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    // org.springframework.boot plugin 미적용 — 라이브러리 jar, bootJar X (spec §4-5 LOCK)
}

dependencies {
    implementation(project(":domain"))

    // Spring Context only (Spring Boot starter 미사용 — pattern-engine 동일)
    implementation("org.springframework:spring-context")

    // Kotlin std
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // slf4j (Logger)
    implementation("org.slf4j:slf4j-api")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "ch.qos.logback")
    }
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

// Spring Boot main 0 — bootJar 비활성화 (spec §4-5 LOCK)
tasks.jar {
    enabled = true
}
