plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))
    // pattern-engine 라이브러리 의존 — PatternController가 PatternRecognizer 빈 주입 (spec §4-1·§4-4 LOCK)
    implementation(project(":pattern-engine"))
    // indicator-impl 라이브러리 의존 — IndicatorEngineConfig가 NativeIndicatorSource·KisStrategyBuilderSource 자동 collect (ADR 0083 + spec §4-5 LOCK)
    implementation(project(":indicator-impl"))
    // strategy-engine 라이브러리 의존 — Backtest/Screener/StrategyController가 실제 JVM DSL parser/evaluator 사용 (autotrading review P0)
    implementation(project(":strategy-engine"))
    // persistence-shared 라이브러리 의존 — Outbox/Stomp 어댑터를 api·consumer 양쪽 ApplicationContext에 노출 (ADR 0088 + spec §3 LOCK)
    implementation(project(":persistence-shared"))
    // market-trend-engine 라이브러리 의존 — TrendCalculator·SmaTrendCalculator @Component 자동 collect
    // (ADR 0100 §2-2 + indicator-impl 패턴 1:1 + W4 R1 T-MARKET Phase 2)
    implementation(project(":market-trend-engine"))

    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // ADR 0094 P0-OPS-2 — Micrometer Prometheus registry
    //   /actuator/prometheus 노출 prerequisite. spring-boot-starter-actuator + 본 의존이 함께 있을 때
    //   PrometheusMeterRegistry 자동 등록.
    implementation("io.micrometer:micrometer-registry-prometheus")

    // ADR 0094 P0-OPS-1 — Logstash JSON encoder
    //   logback-spring.xml `LogstashEncoder` prerequisite. JSON 한 줄 로그 + MDC(traceId/spanId/userId/orderId)
    //   필드 자동 포함. 운영 검색·grep·Loki ingestion 정합.
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // Flyway (api 모듈만 마이그레이션 — ADR 0006 §마이그레이션)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // PostgreSQL driver
    implementation("org.postgresql:postgresql")

    // Swagger (사용자 1차 API 문서 — memory feedback_swagger_pattern 정합)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Spring AI Anthropic (T-DSL 자연어→DSL 변환 helper — ADR 0026 사용자 트리거 옵션 한정)
    // 사용자 결재로 W2 진입. 매매 결정권 0 — REST endpoint에서 사용자가 명시 호출 시에만 활성화.
    // MAGICJAR_AI_ENABLED=false 시 503, MAGICJAR_AI_MOCK_MODE=auto + key 없을 때 mock 응답.
    implementation("org.springframework.ai:spring-ai-anthropic-spring-boot-starter") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }

    // Bucket4j Rate Limiter — KIS REST 초당 10건 강제 (ADR 0037 + spec PR #564 §2-1 옵션 A LOCK)
    // multi-JVM 단일 bucket share — Lettuce Redis backend 직접 발행 (Spring Data Redis와 별도 client)
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")
    // Lettuce는 spring-boot-starter-data-redis가 이미 가져옴 — 별도 명시 0 (transitive)

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}
