package kr.spdaewoon.magicjar.api.ai.explain

import kr.spdaewoon.magicjar.api.persistence.PromptTemplateJpaEntity
import kr.spdaewoon.magicjar.api.persistence.PromptTemplateJpaRepository
import kr.spdaewoon.magicjar.domain.ai.AiCommentType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Prompt 템플릿 조회 + 변수 치환 (ADR 0026 §2-7 amend · V50 정합).
 *
 * 흐름:
 * 1. `PromptTemplateJpaRepository.findFirstByTypeAndIsActiveTrue(type)` — V50 PARTIAL UNIQUE INDEX 로 1건 조회
 * 2. `template_text` 의 `{{name}}` placeholder 를 `context` Map 값으로 치환
 * 3. 미존재 변수 → 빈 문자열로 치환 + WARN log (모델이 "값 없음" 으로 해석)
 *
 * 안전 가드:
 * - template 미존재 → `PromptTemplateNotFoundException` 발생 → 호출자 graceful refuse 합성
 * - context value 가 null 인 경우 빈 문자열 치환 (NPE 회피)
 * - context value 가 collection / map → `toString()` (JSON 직렬화는 호출자 책임 — context 구성 시점에 ObjectMapper 합성)
 */
@Component
class PromptTemplateResolver(
    private val repository: PromptTemplateJpaRepository,
) {
    private val log = LoggerFactory.getLogger(PromptTemplateResolver::class.java)

    /** `{{variable}}` 형식 placeholder 매칭 — `\w` + `.` 허용 (nested key 대비 — 1차는 단일 키 only). */
    private val placeholderRegex = Regex("\\{\\{(\\w+)}}")

    /**
     * type 에 해당하는 active prompt 조회 + 변수 치환 결과 반환.
     *
     * @return Pair<finalPrompt, templateKey> — templateKey 는 `ai_comment_log.prompt_template` 컬럼 기록용 (예: `market_v1`)
     * @throws PromptTemplateNotFoundException type 의 active 행 0건
     */
    fun resolve(
        type: AiCommentType,
        context: Map<String, Any?>,
    ): ResolvedPrompt {
        val typeKey = type.toTemplateKey()
        val template: PromptTemplateJpaEntity =
            repository.findFirstByTypeAndIsActiveTrue(typeKey)
                ?: throw PromptTemplateNotFoundException(typeKey)

        val rendered =
            placeholderRegex.replace(template.templateText) { match ->
                val key = match.groupValues[1]
                val value = context[key]
                if (value == null) {
                    log.warn(
                        "prompt 변수 누락 — type={} version={} key={} (빈 문자열로 치환)",
                        typeKey,
                        template.version,
                        key,
                    )
                    ""
                } else {
                    value.toString()
                }
            }

        val templateKey = "${typeKey}_${template.version}"
        return ResolvedPrompt(prompt = rendered, templateKey = templateKey, version = template.version)
    }

    data class ResolvedPrompt(
        val prompt: String,
        val templateKey: String,
        val version: String,
    )
}

class PromptTemplateNotFoundException(
    typeKey: String,
) : RuntimeException("prompt_template active 행 미존재 (type=$typeKey · V50 seed 누락 또는 is_active 모두 FALSE)")
