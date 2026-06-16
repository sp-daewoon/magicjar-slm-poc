plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))

    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // ADR 0094 P0-OPS-2 — Micrometer Prometheus registry
    //   /actuator/prometheus 노출 prerequisite (3 모듈 동일 패턴).
    implementation("io.micrometer:micrometer-registry-prometheus")

    // ADR 0094 P0-OPS-1 — Logstash JSON encoder
    //   logback-spring.xml `LogstashEncoder` prerequisite (3 모듈 동일 패턴).
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // Kafka (배치 잡 트리거·결과 발행용)
    implementation("org.springframework.kafka:spring-kafka")

    // PostgreSQL driver
    implementation("org.postgresql:postgresql")

    // Bucket4j Rate Limiter — KIS REST 초당 10건 강제 (ADR 0037 + spec PR #564 §2-3 옵션 a LOCK)
    // multi-JVM 단일 bucket share — Lettuce Redis backend 직접 발행 (api·consumer 모듈과 동일 bucketKey).
    // batch 3개 KIS 어댑터(KisChkHolidayAdapter·KisInstrumentMasterAdapter·KisKsdInfoAdapter) 합류 prerequisite.
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")

    // Flyway 미포함 (api 모듈만 마이그레이션 — ADR 0006 §마이그레이션)

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}
