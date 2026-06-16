plugins {
    `java-library`
    kotlin("jvm")
    // Spring Boot plugin 미적용 — bootJar 자동 제외 + 순수 도메인 유지
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    // Jackson core만 — Spring 의존 없이 도메인 직렬화 시그니처 지원
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    // JPA·Spring·Kafka·Redis 의존 0 (ADR 0004 헥사고날 정합)
}

// bootJar 비활성 명시 (이중 안전망)
tasks.named("jar") {
    enabled = true
}
