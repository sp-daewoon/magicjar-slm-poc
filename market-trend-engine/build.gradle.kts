// market-trend-engine — T-MARKET 트랙 pure math + service 모듈 (ADR 0100 §2-2)
// architect spec _workspace/specs/2026-05-24-w4-r1-r2-r3-dispatch-spec.md §R1-2 + ADR 0083 패턴 1:1 정합.
//
// 의존 방향: domain ← market-trend-engine ← api, consumer, batch
// - api: TrendQueryService·SectorRankingService·UserPreferenceController가 이 모듈 빈 주입
// - batch: MarketIndexDailyCloseJob retroactive recompute 시 TrendCalculator reuse (post-R1 market-data)
// - consumer: MarketIndexUpdatedListener fanout 시 TrendCalculator reuse (post-R1)
//
// Spring Boot main 0 — bootJar 비활성화 (indicator-impl 패턴 1:1).
// TrendCalculator interface는 pure math (Spring 의존 0) — service layer만 Spring Context 사용.

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    // org.springframework.boot plugin 미적용 — 라이브러리 jar, bootJar X (indicator-impl 정합)
}

dependencies {
    implementation(project(":domain"))

    // Spring Context only (Spring Boot starter 미사용 — indicator-impl 동일)
    implementation("org.springframework:spring-context")

    // Kotlin std
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // slf4j (Logger)
    implementation("org.slf4j:slf4j-api")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "ch.qos.logback")
    }
}

// Spring Boot main 0 — bootJar 비활성화 (indicator-impl 패턴 1:1)
tasks.jar {
    enabled = true
}
