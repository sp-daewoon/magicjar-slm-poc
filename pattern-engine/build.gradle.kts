// pattern-engine — 라이브러리 모듈 (Spring Boot main 0)
// architect spec §4-4 (pr-41-pattern-engine-design.md) LOCK 정합
// 의존 방향: domain ← pattern-engine ← api, consumer
// api·consumer 둘 다 implementation(project(":pattern-engine")) 의존
// RedisPatternCacheAdapter unify: api 측 삭제 + pattern-engine 단일 구현 (spec §5-3)

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    // org.springframework.boot plugin 미적용 — 라이브러리 jar, bootJar X (spec §4-4 LOCK)
}

dependencies {
    implementation(project(":domain"))

    // Spring Context only (Spring Boot starter 미사용 — spec §4-4 LOCK)
    implementation("org.springframework:spring-context")

    // Redis cache (RedisPatternCacheAdapter — StringRedisTemplate 의존)
    implementation("org.springframework.data:spring-data-redis")

    // Jackson (ObjectMapper — 캐시 직렬화/역직렬화)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

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

// Spring Boot main 0 — bootJar 비활성화 (spec §4-4 LOCK)
tasks.jar {
    enabled = true
}
