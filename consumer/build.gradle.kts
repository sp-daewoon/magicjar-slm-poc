plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))
    // pattern-engine 라이브러리 의존 — JvmStrategyEvaluator가 patternRecognizer 빈 주입 (spec §4-1 LOCK)
    implementation(project(":pattern-engine"))
    // indicator-impl 라이브러리 의존 — IndicatorEngineConfig가 NativeIndicatorSource·KisStrategyBuilderSource 자동 collect (ADR 0083 + spec §4-5 LOCK)
    implementation(project(":indicator-impl"))
    // strategy-engine 라이브러리 의존 — 실시간 신호와 api 백테스트/스크리너가 같은 JVM DSL evaluator 사용
    implementation(project(":strategy-engine"))
    // persistence-shared 라이브러리 의존 — Outbox/Stomp 어댑터를 api·consumer 양쪽 ApplicationContext에 노출 (ADR 0088 + spec §3 LOCK)
    implementation(project(":persistence-shared"))

    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux") // KIS REST/WS 클라이언트
    implementation("org.springframework.boot:spring-boot-starter-web") // @Scheduled enablement (dispatch §K)

    // ADR 0094 P0-OPS-2 — Micrometer Prometheus registry
    //   /actuator/prometheus 노출 prerequisite (3 모듈 동일 패턴).
    implementation("io.micrometer:micrometer-registry-prometheus")

    // ADR 0094 P0-OPS-1 — Logstash JSON encoder
    //   logback-spring.xml `LogstashEncoder` prerequisite (3 모듈 동일 패턴).
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // Kafka (KafkaListener + KIS WS 데이터 발행)
    implementation("org.springframework.kafka:spring-kafka")

    // PostgreSQL driver
    implementation("org.postgresql:postgresql")

    // Bucket4j Rate Limiter — KIS REST 초당 10건 강제 (ADR 0037 + spec PR #564 §2-1 옵션 A LOCK)
    // multi-JVM 단일 bucket share — Lettuce Redis backend 직접 발행 (Spring Data Redis와 별도 client)
    // spec §2-3 옵션 a 정합 — api 모듈 Bean과 동일 bucket key 사용 (Redis-level share)
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")

    // kotlinx-coroutines — MARKET_DAILY 일봉 backfill 동시성 보강 (ADR 0118 amend).
    //   coroutine Semaphore(permits) 로 throughput shaping. KIS hard ceiling 은 ConsumerRedisKisRateLimiterAdapter
    //   (Bucket4j Redis bucket)가 그대로 유효 — 세마포어는 동시 종목 수만 제한(rate limiter 우회/중복 0).
    //   blocking KIS WebClient .block() 호출은 Dispatchers.IO 에서 수행(워커 스레드 블로킹 격리).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Flyway 미포함 (api 모듈만 마이그레이션)

    // ─────────────────────────────────────────────────────────────────────────
    // W4 R1 T-AI Phase 1 — LLM provider 의존 (ADR 0026 amend · 사용자 결재 cec6a4d6)
    //
    // 개발 룰 §5 "AI 호출 자제" 정합:
    //   - W1·W2·W3 트랙 코드(strategy/signal/order/risk/ingestion 등)에 LLM import 0건 LOCK
    //   - 본 의존은 W4 트랙 한정 + `consumer/ai/` 패키지 외 import 금지
    //   - architect R3 grep 검증 의무: `grep -rln "spring.ai\|AnthropicChatModel" consumer/src/main/kotlin/ | grep -v consumer/ai/` = 0건
    // 매매 결정권 0 (ADR 0023 정합) — AiCommentDispatcherListener 는 advisory only, 시그널/주문 흐름 0 touch.
    // ─────────────────────────────────────────────────────────────────────────

    // Spring AI Anthropic — 1차 default provider (`MAGICJAR_AI_PROVIDER=anthropic`)
    implementation("org.springframework.ai:spring-ai-anthropic-spring-boot-starter") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }

    // Resilience4j — 3 retry exponential (1s/2s/4s) + 30s circuit breaker 50% threshold (ADR 0026 §2-6 amend)
    //   - 모든 provider adapter 가 공통 적용 (AnthropicCommentAdapter · GeminiCommentAdapter · OpenAiCommentAdapter)
    //   - 실패 시 본체 흐름 영향 0 — Circuit Breaker fallback → graceful refuse 한국어
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-kotlin:2.2.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    // mockito-kotlin — ConsumerRedisKisRateLimiterAdapterTest 사용 (api 모듈 패턴 1:1 정합)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}
