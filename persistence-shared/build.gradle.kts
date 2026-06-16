// persistence-shared — 라이브러리 모듈 (Spring Boot main 0)
// architect spec (2026-05-19-persistence-shared-module-outbox-wiring-spec.md) + ADR 0088 정합
// 의존 방향: domain ← persistence-shared ← api, consumer
// api·consumer 둘 다 implementation(project(":persistence-shared")) 의존
// Outbox 어댑터(@Component) + JPA 엔티티(@Entity) + JpaRepository(interface)를 양쪽 ApplicationContext에 노출
// indicator-impl 패턴 1:1 정합 (ADR 0083)

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    // org.springframework.boot plugin 미적용 — 라이브러리 jar, bootJar X (spec §3-1 + ADR 0083 정합)
}

dependencies {
    implementation(project(":domain"))

    // Spring Data JPA — Outbox/Stomp 어댑터 JPA 의존
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // PostgreSQL driver (Hibernate JSON cast — StompEventJpaEntity `@JdbcTypeCode(SqlTypes.JSON)` 정합)
    runtimeOnly("org.postgresql:postgresql")

    // Spring Kafka — SystemEventKafkaProducer (P1 system event log)
    // spec: _workspace/specs/2026-05-20-system-event-log-design.md §6.2
    // api·consumer 양쪽 ApplicationContext 에서 KafkaTemplate 빈 wiring 정합
    implementation("org.springframework.kafka:spring-kafka")

    // Micrometer core — Counter/Timer/Gauge metric 노출 (ADR 0094 P0-OPS-6 + ADR 0095 SLO 측정 source)
    // api·batch·consumer 모듈에서 micrometer-registry-prometheus 로 transit-bridge — Prometheus scrape 직결
    // PersistenceMetrics: SystemEvent Counter + Outbox drain lag Gauge wiring source
    implementation("io.micrometer:micrometer-core")

    // Jackson jsr310 — SystemEvent.occurredAt(java.time.Instant) 직렬화
    // api·consumer 모듈은 spring-boot-starter-web 통해 transitively 보유 — persistence-shared 단독 test 정합용
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Kotlin std
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // slf4j (Logger — 어댑터 log)
    implementation("org.slf4j:slf4j-api")

    // Flyway 의존 0 (api 단독 baseline 유지 — ADR 0006 §마이그레이션 LOCK)

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "ch.qos.logback")
    }
}

// Spring Boot main 0 — bootJar 비활성화 (spec §3-1 LOCK + ADR 0083 정합)
tasks.jar {
    enabled = true
}
