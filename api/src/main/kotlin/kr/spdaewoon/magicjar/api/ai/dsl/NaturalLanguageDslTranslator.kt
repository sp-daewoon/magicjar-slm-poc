package kr.spdaewoon.magicjar.api.ai.dsl

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets

/**
 * 자연어 → magicJar/KIS 호환 DSL 변환 helper.
 *
 * 정합 SoT:
 * - ADR 0026 (AI 옵션 — 사용자 트리거 한정, 매매 결정권 0)
 * - ADR 0023 (AI 사용자 트리거 helper 정책 — endpoint 호출 시에만 활성)
 * - PR-35 spec §5-1 KIS 텍스트 1줄 표현식 LOCK (raw `parser.py` 정합)
 * - 사용자 메모리 `project_system_vision.md` — AI는 분석 보조, 매매는 룰/시그널
 *
 * 게이트 분기:
 * 1. `MAGICJAR_AI_ENABLED=false` → endpoint에서 503 반환 (Controller 책임).
 * 2. `MAGICJAR_AI_MOCK_MODE=mock` 또는 (`auto` + Anthropic key 미설정) → MockDslHeuristic 룰 fallback.
 * 3. 그 외 → Spring AI ChatClient(Anthropic) 호출 + 1회 재시도 + UNTRANSLATABLE / 카탈로그 외 식별자 graceful 차단.
 *
 * 출력 안전:
 * - LLM 응답을 그대로 KIS 파서로 흘리지 않습니다 — 식별자 화이트리스트(safe regex) 사전 검증 후 통과.
 * - 결과는 한 줄. 줄바꿈/코드펜스/접두접미어 자동 제거.
 *
 * 룰 5 (W2 LLM import 0) 예외:
 * - ai-engineer 영역(api/.../ai/dsl/)은 사용자 결재로 W2 진입 — 룰 5 본체 LOCK은 strategy/domain/consumer 기준.
 * - 본 클래스는 자동매매 본체 데이터 흐름이 아니라 사용자 트리거 helper.
 */
@Service
class NaturalLanguageDslTranslator(
    private val resourceLoader: ResourceLoader,
    private val chatClientBuilderProvider: ObjectProvider<ChatClient.Builder>,
    @Value("\${magicjar.ai.enabled:false}") private val aiEnabled: Boolean,
    @Value("\${magicjar.ai.mock-mode:auto}") private val mockMode: String,
    @Value("\${magicjar.ai.provider:anthropic}") private val provider: String,
    @Value("\${spring.ai.anthropic.api-key:}") private val anthropicApiKey: String,
) {
    private val log = LoggerFactory.getLogger(NaturalLanguageDslTranslator::class.java)

    private val chatClient: ChatClient? by lazy {
        if (resolveMode() == DslTranslateResult.TranslateMode.MOCK) {
            null
        } else {
            runCatching { chatClientBuilderProvider.ifAvailable?.build() }
                .onFailure { log.warn("Spring AI ChatClient build 실패 — mock fallback 강제. cause={}", it.message) }
                .getOrNull()
        }
    }

    /** 카탈로그 외 식별자/제어문자 차단 — `dsl-translate.md` 식별자 카탈로그 정합. */
    private val safeDslPattern: Regex =
        Regex("^[a-zA-Z0-9_().,\\s+\\-*/%<>=!\"\\[\\]]+$")

    /** 단일 진입점. enabled=false 인 경우 호출자(Controller)가 사전 차단해야 하지만 이중 안전. */
    fun translate(naturalLanguage: String): DslTranslateResult {
        if (!aiEnabled) {
            return DslTranslateResult.untranslatable(
                mode = DslTranslateResult.TranslateMode.DISABLED,
                reasoning = "MAGICJAR_AI_ENABLED=false — AI 기능이 비활성 상태입니다. .env에서 활성화 후 재기동하세요.",
            )
        }
        val trimmed = naturalLanguage.trim()
        if (trimmed.isBlank()) {
            return DslTranslateResult.untranslatable(
                mode = resolveMode(),
                reasoning = "자연어 입력이 비어 있습니다.",
            )
        }
        val mode = resolveMode()
        return when (mode) {
            DslTranslateResult.TranslateMode.MOCK -> mockTranslate(trimmed)
            DslTranslateResult.TranslateMode.REAL -> realTranslate(trimmed)
            DslTranslateResult.TranslateMode.DISABLED -> error("unreachable — guard above")
        }
    }

    private fun resolveMode(): DslTranslateResult.TranslateMode {
        if (!aiEnabled) return DslTranslateResult.TranslateMode.DISABLED
        return when (mockMode.lowercase()) {
            "mock" -> DslTranslateResult.TranslateMode.MOCK
            "real" -> DslTranslateResult.TranslateMode.REAL
            "auto" ->
                if (anthropicApiKey.isBlank()) {
                    DslTranslateResult.TranslateMode.MOCK
                } else {
                    DslTranslateResult.TranslateMode.REAL
                }
            else -> DslTranslateResult.TranslateMode.MOCK
        }
    }

    private fun mockTranslate(naturalLanguage: String): DslTranslateResult {
        val dsl = MockDslHeuristic.translate(naturalLanguage)
        return if (dsl == null) {
            // 카탈로그·운영 게이트 SoT: `_workspace/ai/prompt-catalog.md` §1-3 (현행 dsl-translate).
            //   `docs/architecture/policies/ai-trigger-gate.md` 발행 예정(backlog §80 — ai-engineer 후속 PR).
            //   당분간 ADR 0026·0023 + spec PR-35 §5-1 본문 권위 LOCK.
            DslTranslateResult.untranslatable(
                mode = DslTranslateResult.TranslateMode.MOCK,
                reasoning = "Mock 룰 매칭 실패 — Anthropic key 설정 후 재시도하거나 자연어를 단순화하세요. 카탈로그: _workspace/ai/prompt-catalog.md",
            )
        } else {
            DslTranslateResult.success(
                dsl = dsl,
                mode = DslTranslateResult.TranslateMode.MOCK,
                reasoning = "Mock 룰 기반 변환 결과입니다 (ANTHROPIC_API_KEY 미설정 또는 mock 강제 모드).",
            )
        }
    }

    private fun realTranslate(naturalLanguage: String): DslTranslateResult {
        if (provider.lowercase() != "anthropic") {
            log.warn("MAGICJAR_AI_PROVIDER={} — 본 helper는 anthropic 한정. mock fallback.", provider)
            return mockTranslate(naturalLanguage)
        }
        val client = chatClient
        if (client == null) {
            log.warn("ChatClient unavailable — mock fallback")
            return mockTranslate(naturalLanguage)
        }
        val template = loadPromptTemplate()
        val prompt = template.replace("{{naturalLanguage}}", naturalLanguage)
        val raw =
            invokeWithRetry(client, prompt) ?: return DslTranslateResult.untranslatable(
                mode = DslTranslateResult.TranslateMode.REAL,
                reasoning = "Anthropic 호출이 1회 재시도 후에도 실패했습니다. 잠시 후 다시 시도하세요.",
            )
        return parseLlmResponse(raw)
    }

    private fun invokeWithRetry(
        client: ChatClient,
        prompt: String,
    ): String? {
        repeat(2) { attempt ->
            try {
                val response =
                    client
                        .prompt()
                        .user(prompt)
                        .call()
                        .content()
                if (!response.isNullOrBlank()) return response
            } catch (e: Exception) {
                log.warn("LLM 호출 실패 (attempt={}): {}", attempt + 1, e.message)
            }
        }
        return null
    }

    private fun parseLlmResponse(raw: String): DslTranslateResult {
        val cleaned = sanitize(raw)
        if (cleaned.isBlank()) {
            return DslTranslateResult.untranslatable(
                mode = DslTranslateResult.TranslateMode.REAL,
                reasoning = "LLM이 빈 응답을 반환했습니다.",
                raw = raw,
            )
        }
        if (cleaned.equals("UNTRANSLATABLE", ignoreCase = true)) {
            return DslTranslateResult.untranslatable(
                mode = DslTranslateResult.TranslateMode.REAL,
                reasoning = "LLM이 변환 불가로 판단했습니다 — 자연어를 더 구체적으로 (지표·기간·임계값 명시) 다시 입력해 주세요.",
                raw = raw,
            )
        }
        if (!safeDslPattern.matches(cleaned)) {
            log.warn("LLM 응답이 화이트리스트 외 문자를 포함 — graceful 차단. raw='{}' cleaned='{}'", raw, cleaned)
            return DslTranslateResult.untranslatable(
                mode = DslTranslateResult.TranslateMode.REAL,
                reasoning = "LLM 응답이 안전 화이트리스트(영문·숫자·괄호·연산자) 외 문자를 포함하여 차단되었습니다.",
                raw = raw,
            )
        }
        return DslTranslateResult.success(
            dsl = cleaned,
            mode = DslTranslateResult.TranslateMode.REAL,
            reasoning = "Anthropic Claude 변환 결과 — 등록 전 KisYamlDslParser.validateStatic() 통과 시 안전합니다.",
            raw = raw,
        )
    }

    private fun sanitize(raw: String): String {
        // 코드 펜스 / 따옴표 / 백틱 / 줄바꿈 제거 + 마지막 줄(설명 후 DSL 케이스) 우선.
        val stripped =
            raw
                .replace("```", "")
                .replace("`", "")
                .trim()
        val lines = stripped.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        val candidate = lines.last()
        return candidate
            .removePrefix("DSL:")
            .removePrefix("dsl:")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
    }

    private fun loadPromptTemplate(): String {
        val resource: Resource = resourceLoader.getResource("classpath:prompts/dsl-translate.md")
        return resource.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }
}
