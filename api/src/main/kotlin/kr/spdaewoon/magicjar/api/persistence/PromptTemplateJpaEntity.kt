package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * AI prompt 템플릿 JPA 엔티티 (ADR 0026 §2-7 amend · Flyway V50 — 2026-05-24 R0 amend shift).
 *
 * 테이블: prompt_template (V50__prompt_template.sql)
 *
 * 책임 (V50 본문 주석 정합):
 * - type+version 관리 (A/B 드리프트 추적)
 * - 변수 치환 schema (`variables_schema` JSONB) — PromptTemplateResolver 가 missing key 사전 검출
 * - `is_active=TRUE` 행 1건만 latest active (PARTIAL UNIQUE INDEX `uk_prompt_template_type_active`)
 *
 * seed 5건 (V50 본문):
 * - symbol_v1 / chart_v1 / strategy_v1 / signal_v1 / market_v1
 *
 * variables_schema JSONB 는 String 보관 + Resolver 가 ObjectMapper 로 parse — Hibernate JSON 타입 의존성 회피 (api 모듈 단일 dep 정합).
 */
@Entity
@Table(name = "prompt_template")
class PromptTemplateJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "type", length = 32, nullable = false)
    val type: String,
    @Column(name = "version", length = 16, nullable = false)
    val version: String,
    @Column(name = "template_text", nullable = false, columnDefinition = "TEXT")
    val templateText: String,
    @Column(name = "variables_schema", nullable = false, columnDefinition = "JSONB")
    val variablesSchemaJson: String = "{}",
    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        type = "",
        version = "",
        templateText = "",
    )
}
