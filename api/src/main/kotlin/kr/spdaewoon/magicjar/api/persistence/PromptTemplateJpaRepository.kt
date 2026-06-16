package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * AI prompt 템플릿 Spring Data JPA Repository (ADR 0026 §2-7 amend · V50).
 *
 * ADR 0051 default fun 함정 회피 LOCK:
 *   - interface 내부 body 가진 fun 발행 금지 (abstract 만 허용)
 *
 * 사용 처:
 * - `PromptTemplateResolver.latestActive(type)` → `findFirstByTypeAndIsActiveTrue(type)`
 */
interface PromptTemplateJpaRepository : JpaRepository<PromptTemplateJpaEntity, Long> {
    /**
     * type 에 대한 latest active template 조회.
     *
     * V50 `uk_prompt_template_type_active` PARTIAL UNIQUE INDEX 로 행 1건 LOCK — `findFirstBy` 가 안전.
     * 미존재 시 `null` 반환 (호출자 graceful refuse).
     */
    fun findFirstByTypeAndIsActiveTrue(type: String): PromptTemplateJpaEntity?
}
