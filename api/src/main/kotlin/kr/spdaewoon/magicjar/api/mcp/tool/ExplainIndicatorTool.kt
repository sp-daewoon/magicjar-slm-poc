package kr.spdaewoon.magicjar.api.mcp.tool

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.mcp.McpTool
import kr.spdaewoon.magicjar.api.mcp.McpToolException
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalString
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.requireString
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * MCP tool `explain_indicator` — 지표 catalog 정적 응답 (ADR 0027 §2-2 + §2-6).
 *
 * ## LLM 호출 0 LOCK
 * resources `mcp/indicator_catalog.json` 정적 catalog 만 사용 — AiCommentProvider import 0 (grep evidence 의무).
 *
 * post-W4 catalog 보강 시 JSON 파일 add-only — 본체 변경 0.
 *
 * ## input
 * - `name`: 지표 이름 (대소문자 무관 · 예: "RSI" · "macd")
 * - `params`: 파라미터 override (옵션 · catalog default 보강용)
 * - `context`: 호출 맥락 (옵션 · echo 만 · 1차 미사용)
 *
 * ## output
 * `{ name, fullName, category, params, formula, interpretation, paramsApplied, context }`
 */
@Component
class ExplainIndicatorTool(
    private val objectMapper: ObjectMapper,
) : McpTool {
    private val log = LoggerFactory.getLogger(ExplainIndicatorTool::class.java)

    /**
     * catalog 1회 lazy load — 부팅 시 비용 회피 + 첫 호출 시 cache (Kotlin lazy thread-safe).
     */
    private val catalog: Map<String, IndicatorEntry> by lazy { loadCatalog() }

    override val name: String = "explain_indicator"
    override val description: String =
        "지표/패턴 정의·계산식·해석 정적 catalog (LLM 호출 0 — ADR 0027 §2-6 LOCK). 20+ 표준 지표 (RSI · MACD · MA · BBands · ATR · OBV · VWAP 등)."

    override val inputSchema: Map<String, Any?> by lazy {
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "name" to
                        mapOf(
                            "type" to "string",
                            "description" to "지표 이름 (대소문자 무관 — 예: RSI · macd · BBANDS)",
                            "enum" to catalog.keys.sorted(),
                        ),
                    "params" to
                        mapOf(
                            "type" to "object",
                            "description" to "파라미터 override (예: { period: 21 }) — 1차 echo 만 · post-MVP 적용 path",
                        ),
                    "context" to
                        mapOf(
                            "type" to "string",
                            "description" to "호출 맥락 (옵션 · echo)",
                        ),
                ),
            "required" to listOf("name"),
        )
    }

    override fun execute(
        params: Map<String, Any?>,
        userId: String,
    ): Map<String, Any?> {
        val rawName = params.requireString("name") { "지표 이름 누락" }
        val normalized = rawName.uppercase(Locale.ROOT)
        val entry =
            catalog[normalized]
                ?: throw McpToolException(
                    "지표를 찾을 수 없습니다: '$rawName' (지원 = ${catalog.keys.sorted()})",
                )

        @Suppress("UNCHECKED_CAST")
        val paramsOverride = params["params"] as? Map<String, Any?>
        val context = params.optionalString("context")

        return mapOf(
            "name" to entry.name,
            "fullName" to entry.fullName,
            "category" to entry.category,
            "params" to entry.params,
            "formula" to entry.formula,
            "interpretation" to entry.interpretation,
            "paramsApplied" to (paramsOverride ?: entry.params),
            "context" to context,
            "source" to "magicJar static catalog (LLM 호출 0 — ADR 0027 §2-6)",
        )
    }

    private fun loadCatalog(): Map<String, IndicatorEntry> =
        runCatching {
            val resource = ClassPathResource("mcp/indicator_catalog.json")
            val jsonBytes = resource.inputStream.use { it.readAllBytes() }

            @Suppress("UNCHECKED_CAST")
            val root = objectMapper.readValue(jsonBytes, Map::class.java) as Map<String, Any?>
            val indicators = (root["indicators"] as? List<*>) ?: emptyList<Any?>()
            indicators
                .filterIsInstance<Map<String, Any?>>()
                .associate { item ->
                    val n = (item["name"] as? String) ?: error("indicator_catalog.json — name 누락 row 발견")

                    @Suppress("UNCHECKED_CAST")
                    val paramsMap = (item["params"] as? Map<String, Any?>) ?: emptyMap()
                    n.uppercase(Locale.ROOT) to
                        IndicatorEntry(
                            name = n,
                            fullName = item["fullName"]?.toString() ?: n,
                            category = item["category"]?.toString() ?: "general",
                            params = paramsMap,
                            formula = item["formula"]?.toString() ?: "",
                            interpretation = item["interpretation"]?.toString() ?: "",
                        )
                }.also { log.info("[mcp.explain_indicator] catalog load 완료: {} 지표", it.size) }
        }.getOrElse { ex ->
            log.error("[mcp.explain_indicator] catalog load 실패 — empty fallback: {}", ex.message)
            emptyMap()
        }

    /**
     * catalog 1 row 정합 모델.
     *
     * sealed type 까지는 1차 불필요 — JSON 키 4종만 안정적이면 충분 (post-W4 분기 시 sealed interface 도입 path).
     */
    private data class IndicatorEntry(
        val name: String,
        val fullName: String,
        val category: String,
        val params: Map<String, Any?>,
        val formula: String,
        val interpretation: String,
    )
}
