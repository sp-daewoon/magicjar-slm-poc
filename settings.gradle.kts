pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "magicjar"

include(
    "domain",
    "pattern-engine",
    "indicator-impl",
    "strategy-engine",
    "persistence-shared",
    // market-trend-engine — T-MARKET 트랙 pure math 모듈 (ADR 0100 §2-2 + ADR 0004 amend 7→8)
    // indicator-impl 패턴 1:1: Spring Boot main 0, domain port + Spring Context 의존만.
    "market-trend-engine",
    "api",
    "batch",
    "consumer",
)
