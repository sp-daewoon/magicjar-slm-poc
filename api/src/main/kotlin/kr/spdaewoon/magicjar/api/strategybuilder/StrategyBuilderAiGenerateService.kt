package kr.spdaewoon.magicjar.api.strategybuilder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.strategyresearch.StrategyResearchAiTextClient
import kr.spdaewoon.magicjar.domain.indicator.IndicatorCatalog
import kr.spdaewoon.magicjar.domain.indicator.IndicatorCategory
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 전략빌더 AI 전략 생성 서비스.
 *
 * Live-Context Meta-Prompt 패턴(AI 프리셋 추천 [recommendPresets] 선례)의 2번째 이식.
 * 컨텍스트만 교체한다: "발굴 프리셋 설계" → "magicJar DSL 전략 설계".
 *
 * - 저장하지 않는다([StrategyJpaRepository]는 읽기 전용 — 기존 전략 중복 회피 컨텍스트 주입용).
 *   생성 결과는 빌더 draft 로드용 제안이며, 저장은 기존 전략 저장 API가 담당한다(AI 결정권 0 · ADR 0112 정합).
 * - AI 실패/타임아웃은 [StrategyResearchAiTextClient]가 ResponseStatusException으로 전파(mock 합성 0).
 * - ★ 품질 차별점: 생성된 entryDsl/exitDsl을 실제 [KisYamlDslParser]로 파싱 검증한 뒤
 *   파싱 실패 item은 1회 자동 재생성(실패 DSL + 파서 에러를 프롬프트에 동봉)을 시도한다.
 *   그래도 실패하면 dslValid=false로 응답에는 포함(frontend가 draft 로드를 비활성).
 */
@Service
class StrategyBuilderAiGenerateService(
    private val aiTextClient: StrategyResearchAiTextClient,
    private val parser: KisYamlDslParser,
    private val objectMapper: ObjectMapper,
    private val strategyRepository: StrategyJpaRepository,
) {
    private val log = LoggerFactory.getLogger(StrategyBuilderAiGenerateService::class.java)

    companion object {
        /** 잔익절 최소 권장 익절 % (왕복 비용 ~0.2% 고려 — research 잔익절 방어와 정합). */
        private const val MIN_RECOMMENDED_TP_PCT = 0.5

        /** 잔익절 경고 임계 % (이 값 이하 익절은 왕복 비용에 먹힘). */
        private const val NOISE_TP_WARN_PCT = 0.3

        private const val EXISTING_STRATEGY_SAMPLE = 30
    }

    fun generate(request: StrategyBuilderAiGenerateRequestDto): StrategyBuilderAiGenerateResponseDto {
        val prompt = request.prompt.trim()
        if (prompt.isBlank()) {
            // @NotBlank 우회(공백만) 방어 — 발굴과 달리 빈 prompt는 400.
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 의도(prompt)는 비워둘 수 없습니다.")
        }
        val count = request.count.coerceIn(1, 3)
        val userId = request.userId.trim().toLongOrNull()
        val existingStrategies = loadExistingStrategies(userId)

        val metaPrompt =
            buildGeneratePrompt(
                userPrompt = prompt,
                interval = request.interval?.trim()?.takeIf { it.isNotBlank() },
                count = count,
                existingStrategies = existingStrategies,
            )
        val aiResult = aiTextClient.complete(metaPrompt)
        val parsed = parseGeneratedStrategies(aiResult.text).take(count)
        if (parsed.isEmpty()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude 응답에서 전략 후보를 읽지 못했습니다.")
        }

        // ★ 1차 DSL 검증 게이트.
        var items = parsed.map { validateDsl(it) }

        // ★ invalid item 1회 자동 재생성 — 실패 DSL + 파서 에러를 프롬프트에 동봉.
        val invalidIndices = items.withIndex().filter { !it.value.dslValid }.map { it.index }
        var regenerated = false
        if (invalidIndices.isNotEmpty()) {
            val fixPrompt =
                buildRegeneratePrompt(
                    userPrompt = prompt,
                    interval = request.interval?.trim()?.takeIf { it.isNotBlank() },
                    invalidItems = invalidIndices.map { items[it] },
                )
            val fixResult =
                runCatching { aiTextClient.complete(fixPrompt) }
                    .onFailure { log.warn("전략빌더 AI 재생성 실패(원래 invalid 유지): {}", it.message) }
                    .getOrNull()
            if (fixResult != null) {
                val fixedParsed = parseGeneratedStrategies(fixResult.text).map { validateDsl(it) }
                if (fixedParsed.isNotEmpty()) {
                    regenerated = true
                    // 재생성 결과 중 valid한 것으로 invalid 슬롯을 순서대로 교체(valid만, 그래도 부족하면 원래 invalid 유지).
                    val replacements = fixedParsed.filter { it.dslValid }.toMutableList()
                    val merged = items.toMutableList()
                    for (idx in invalidIndices) {
                        if (replacements.isNotEmpty()) {
                            merged[idx] = replacements.removeAt(0)
                        }
                    }
                    items = merged
                }
            }
        }

        val warnings = buildWarnings(items, count, regenerated)

        log.info(
            "전략빌더 AI 생성: userId={} promptLength={} requested={} returned={} valid={} regenerated={}",
            request.userId,
            prompt.length,
            count,
            items.size,
            items.count { it.dslValid },
            regenerated,
        )

        return StrategyBuilderAiGenerateResponseDto(
            provider = aiResult.provider,
            generatedAt = Instant.now().toString(),
            warnings = warnings,
            items = items,
        )
    }

    private fun loadExistingStrategies(userId: Long?): List<StrategyJpaEntity> {
        if (userId == null) return emptyList()
        return runCatching {
            strategyRepository
                .findAllByUserIdAndStatusNot(userId, StrategyStatus.DELETED.name, PageRequest.of(0, EXISTING_STRATEGY_SAMPLE))
                .content
        }.onFailure { log.warn("전략빌더 AI 생성: 기존 전략 조회 실패(중복 회피 컨텍스트 생략): {}", it.message) }
            .getOrDefault(emptyList())
    }

    private fun buildWarnings(
        items: List<StrategyBuilderAiGenerateItemDto>,
        requestedCount: Int,
        regenerated: Boolean,
    ): List<String> =
        buildList {
            add("AI가 생성한 전략은 매수 추천이 아니라 백테스트·MOCK 검증 대상 가설입니다. 저장 전 반드시 검증하세요.")
            add("생성 결과는 저장되지 않습니다. 빌더에서 검토·수정 후 전략 저장으로 직접 저장하세요.")
            if (items.size < requestedCount) {
                add("요청한 ${requestedCount}건 중 ${items.size}건만 생성되었습니다.")
            }
            val invalid = items.count { !it.dslValid }
            if (invalid > 0) {
                add("DSL 파싱에 실패한 전략 ${invalid}건은 그대로 저장할 수 없습니다(빌더에서 조건식 수정 필요).")
            }
            if (regenerated) {
                add("일부 전략은 DSL 검증 실패 후 1회 자동 재생성으로 정정을 시도했습니다.")
            }
            // 잔익절 경고 — NOISE_TP_WARN_PCT 이하 익절은 왕복 비용에 먹힘.
            val noiseTp =
                items.count { it.riskPlan.takeProfitPct != null && it.riskPlan.takeProfitPct <= NOISE_TP_WARN_PCT }
            if (noiseTp > 0) {
                add(
                    "익절 ${NOISE_TP_WARN_PCT}% 이하인 전략 ${noiseTp}건이 있습니다. 왕복 비용(~0.2%)을 고려해 " +
                        "${MIN_RECOMMENDED_TP_PCT}% 이상을 권장합니다(잔익절 매도 방어).",
                )
            }
            val noStop = items.count { it.riskPlan.fixedStopLossPct == null }
            if (noStop > 0) {
                add("손절(fixedStopLossPct)이 없는 전략 ${noStop}건이 있습니다. 자동매매 hard stop 손절을 권장합니다.")
            }
        }

    /**
     * 생성된 전략의 entryDsl/exitDsl을 실제 파서로 파싱·static 검증한다.
     * 둘 중 하나라도 실패하면 dslValid=false + dslError에 첫 실패 사유를 담는다.
     */
    private fun validateDsl(item: StrategyBuilderAiGenerateItemDto): StrategyBuilderAiGenerateItemDto {
        val entryError = validateSingleDsl(item.entryDsl, label = "진입")
        if (entryError != null) {
            return item.copy(dslValid = false, dslError = entryError)
        }
        val exit = item.exitDsl?.trim()?.takeIf { it.isNotBlank() }
        if (exit != null) {
            val exitError = validateSingleDsl(exit, label = "청산")
            if (exitError != null) {
                return item.copy(dslValid = false, dslError = exitError)
            }
        }
        return item.copy(dslValid = true, dslError = null)
    }

    /** 단일 DSL 식 파싱·static 검증. 통과하면 null, 실패하면 사유 문자열. */
    private fun validateSingleDsl(
        dsl: String,
        label: String,
    ): String? {
        val text = dsl.trim()
        if (text.isBlank()) return "$label 조건 DSL이 비어 있습니다."
        return runCatching {
            val ast = parser.parse(text)
            parser.validateStatic(ast)
        }.fold(
            onSuccess = { null },
            onFailure = { "$label 조건 파싱 실패: ${it.message}" },
        )
    }

    /**
     * 전략 생성 메타 프롬프트(본체).
     *
     * "magicJar DSL 전략 설계 전문가" 역할 + 다음 컨텍스트를 모두 주입한다:
     *  1. 역할 — 전략은 매수 추천이 아니라 백테스트·MOCK 검증 대상 가설([researchCoreWrapper] 인용).
     *  2. DSL 문법·지표 카탈로그(파서 화이트리스트 코드 추출 — 지표 추가 시 자동 정합).
     *  3. 기존 사용자 전략 목록(name+entryDsl) — 중복 컨셉 회피.
     *  4. RiskPlan 스키마 + 잔익절 규칙(0.3% 이하 익절 경고, 손절 권장).
     *  5. 사용자 prompt(필수).
     *  6. 출력 JSON 계약.
     */
    private fun buildGeneratePrompt(
        userPrompt: String,
        interval: String?,
        count: Int,
        existingStrategies: List<StrategyJpaEntity>,
    ): String {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val existingBlock =
            if (existingStrategies.isEmpty()) {
                "등록된 사용자 전략 없음. 자유롭게 의도에 맞는 전략을 설계하라."
            } else {
                existingStrategies.joinToString("\n") { s ->
                    "- ${s.nameKo ?: s.name}: ${s.dslEntry.trim()}"
                }
            }
        return listOf(
            "[역할]",
            researchCoreWrapperRolePrefix(),
            "",
            "[작업 정의]",
            "너는 magicJar의 'DSL 전략 설계 전문가'다. 사용자의 전략 의도를 받아, magicJar의 조건식 DSL로 표현된",
            "백테스트·MOCK 검증 가능한 전략 후보를 설계하라. 너의 전략은 절대 매수/매도 추천이 아니며,",
            "사용자가 빌더에서 검토·수정 후 백테스트와 MOCK 검증을 거쳐야 하는 '가설'이다.",
            "",
            dslGrammarBlock(),
            "",
            "[이미 등록된 사용자 전략 — 진입 조건이 사실상 동일하거나 매우 유사한 전략은 금지. 새로운 각도를 제안하라]",
            existingBlock,
            "",
            riskPlanSchemaBlock(),
            "",
            "[사용자 전략 의도 — 아래 의도를 최우선 반영하라]",
            userPrompt,
            if (interval != null) "\n[타임프레임 힌트] 사용자는 $interval 봉 기준 전략을 의도한다. 지표 기간을 이 봉에 맞게 설계하라." else "",
            "",
            "[작업]",
            "오늘 날짜: $today KST",
            "위 의도와 DSL 문법으로 검증 가능한, 기존 전략과 겹치지 않는 신규 전략 ${count}건을 설계하라.",
            "각 전략의 entryDsl은 반드시 위 [허용 문법]만 사용하라. 허용되지 않은 식별자/인덱스 접근(prev_drop·volume[1]·close[-1] 등)은 금지한다.",
            "",
            outputContractBlock(),
        ).filter { it.isNotEmpty() || it == "" }.joinToString("\n")
    }

    /**
     * 재생성 프롬프트 — invalid DSL + 파서 에러를 동봉해 정정을 요청한다(1회).
     */
    private fun buildRegeneratePrompt(
        userPrompt: String,
        interval: String?,
        invalidItems: List<StrategyBuilderAiGenerateItemDto>,
    ): String {
        val invalidBlock =
            invalidItems.joinToString("\n\n") { item ->
                listOf(
                    "- 전략명: ${item.name}",
                    "  entryDsl(실패): ${item.entryDsl}",
                    "  exitDsl(실패): ${item.exitDsl ?: "null"}",
                    "  파서 에러: ${item.dslError ?: "DSL 파싱 실패"}",
                ).joinToString("\n")
            }
        return listOf(
            "[역할]",
            researchCoreWrapperRolePrefix(),
            "",
            "[재작업 — 직전에 네가 만든 전략의 DSL이 magicJar 파서 검증에 실패했다. 아래 에러를 보고 정정하라]",
            invalidBlock,
            "",
            "[정정 지침]",
            "각 실패 전략을, 의도는 유지하되 아래 [허용 문법]만 사용하도록 DSL을 다시 작성하라.",
            "파서 에러가 'Unknown DSL identifier'면 그 식별자를 허용 문법의 동등 표현으로 대체하라" +
                "(예: prev_drop→change(N)·returns(N), N봉 신고가→highest(N)·donchian_upper(N)).",
            "",
            dslGrammarBlock(),
            "",
            riskPlanSchemaBlock(),
            "",
            "[사용자 원래 전략 의도]",
            userPrompt,
            if (interval != null) "[타임프레임 힌트] $interval 봉 기준." else "",
            "",
            outputContractBlock(),
        ).filter { it.isNotEmpty() || it == "" }.joinToString("\n")
    }

    /** core wrapper [분석 원칙]의 '가설 후보' 제약을 전략 생성 톤으로 인용. */
    private fun researchCoreWrapperRolePrefix(): String =
        listOf(
            "너는 한국 주식 자동매매 시스템 magicJar의 전략 설계 전문가다.",
            "네가 설계한 전략은 절대적인 매수/매도 추천이 아니며, magicJar의 백테스트와 MOCK 검증을 거쳐야 하는 '가설'이다.",
            "확정 수익·자동 주문을 암시하는 표현을 금지한다. 왜 이 지표 조합이 의도에 맞는지 근거 중심으로 설계하라.",
        ).joinToString("\n")

    /**
     * DSL 문법·지표 카탈로그 블록.
     *
     * 파서 화이트리스트([KisYamlDslParser.SUPPORTED_IDENTIFIERS])를 코드에서 직접 추출해 주입하므로,
     * 지표/별칭이 추가되면 이 블록도 자동으로 따라간다(메타 프롬프트가 파서 권위와 정합 유지).
     */
    private fun dslGrammarBlock(): String {
        val supported = KisYamlDslParser.SUPPORTED_IDENTIFIERS

        fun catalogNames(
            category: IndicatorCategory,
            take: Int,
        ): String =
            IndicatorCatalog
                .byCategory(category)
                .filter { supported.contains(it.id) }
                .take(take)
                .joinToString(", ") { "${it.id}(${it.displayName})" }
        return listOf(
            "[허용 문법 — magicJar DSL 파서가 실제로 파싱·검증하는 문법만 사용하라. 이외 식별자/함수는 검증 실패로 거부된다]",
            "· 비교 연산자: > < >= <= == != (임계값 조건)",
            "· 교차 연산자: crosses_above · crosses_below (골든/데드크로스)",
            "· 돌파 연산자: breaks (지지/저항 돌파)",
            "· 논리 결합: and · or, 그룹화 ( )",
            "· 산술: + - * / (예: volume_ma(20) * 1.5)",
            "· 캔들 직접 접근: open · high · low · close · prev_close · volume",
            "· 자체 함수: change(N) · highest(N) · lowest(N) · returns(N)",
            "· 패턴: pattern(\"name\") · pattern_detected(\"name\") · consecutive(\"up\"|\"down\")",
            "· 지표는 id(period) 형식으로 호출(예: ma(20), rsi(14), ema(5)). 사용 가능한 지표 id 일부:",
            "  - 추세: ${catalogNames(IndicatorCategory.TREND, 8)}",
            "  - 모멘텀: ${catalogNames(IndicatorCategory.MOMENTUM, 8)}",
            "  - 거래량/수급: ${catalogNames(IndicatorCategory.VOLUME, 6)}",
            "  - 변동성: ${catalogNames(IndicatorCategory.VOLATILITY, 5)}",
            "  - 오실레이터: ${catalogNames(IndicatorCategory.OSCILLATOR, 5)}",
            "· multi-output 별칭(id(period) 형식): macd_signal · macd_hist · bb_upper · bb_lower · bb_middle · stoch_k · stoch_d · volume_ma.",
            "· 채널 지표(id(period) 형식): donchian_upper · donchian_lower(N봉 최고가/최저가 돌파 표현에 활용).",
            "· 스마트머니(구조) 별칭: fvg_active · fvg_bullish_active · ob_active · ob_bullish_active · ob_bos_direction 등.",
            "★ 금지 예시(파싱 불가 — 절대 사용 금지): prev_drop · volume[1] · close[-1] 같은 미지원 식별자/배열 인덱스 접근.",
            "  과거 값/구간 비교가 필요하면 prev_close · change(N) · returns(N) · highest(N) · lowest(N)로 표현하라(이 함수들은 허용된다).",
        ).joinToString("\n")
    }

    private fun riskPlanSchemaBlock(): String =
        listOf(
            "[RiskPlan 스키마 — 각 전략에 riskPlan을 함께 설계하라]",
            "· takeProfitPct: 목표 익절 %. 익절은 왕복 비용(~0.2%)을 고려해 0.5% 이상을 권장한다. " +
                "0.3% 이하는 잔익절(노이즈 매도)로 경고되니 피하라.",
            "· fixedStopLossPct: hard stop 손절 %. 자동매매 손실 방어를 위해 반드시 설계하라(손절 권장).",
            "· minHoldBars: 최소 보유 bar 수. 스윙이면 크게, 단타면 작게. 노이즈 조기 청산 방어용.",
            "값을 정할 수 없으면 해당 필드를 null로 두되, 손절은 가능하면 항상 채워라.",
        ).joinToString("\n")

    private fun outputContractBlock(): String =
        listOf(
            "[출력 — 아래 JSON만 출력하라. 마크다운, 설명문, 코드블록 펜스는 출력하지 마라.]",
            "필드 제약:",
            "- name: 전략 노출명(40자 이내, 핵심 컨셉이 드러나게).",
            "- description: 전략 설명 1~2문장(무엇을·왜).",
            "- entryDsl: 진입 조건. 위 [허용 문법]만 사용한 한 줄 DSL.",
            "- exitDsl: 청산 조건 DSL. 없으면 null(익절/손절/보유로 청산).",
            "- riskPlan: { takeProfitPct, fixedStopLossPct, minHoldBars } — 위 스키마. 없는 값은 null.",
            "- rationale: 왜 이 지표 조합이 사용자 의도에 맞는지 1~2문장.",
            "{",
            "  \"items\": [",
            "    {",
            "      \"name\": \"20일선 눌림목 거래량 반등\",",
            "      \"description\": \"20일선 위 눌림목에서 거래량이 회복될 때 진입하는 스윙 전략.\",",
            "      \"entryDsl\": \"close > ma(20) and close crosses_above ma(5) and volume > volume_ma(20) * 1.5\",",
            "      \"exitDsl\": \"close crosses_below ma(5)\",",
            "      \"riskPlan\": { \"takeProfitPct\": 1.5, \"fixedStopLossPct\": 0.8, \"minHoldBars\": 3 },",
            "      \"rationale\": \"20일선 추세 필터와 거래량 이동평균 수급 확인을 결합해 의도에 맞는다.\"",
            "    }",
            "  ]",
            "}",
        ).joinToString("\n")

    private fun parseGeneratedStrategies(text: String): List<StrategyBuilderAiGenerateItemDto> {
        val json = extractJson(text)
        val root =
            runCatching { objectMapper.readTree(json) }
                .getOrElse {
                    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude 전략 JSON 파싱 실패: ${it.message}", it)
                }
        val itemsNode =
            when {
                root.has("items") -> root["items"]
                root.isArray -> root
                else -> null
            }
        if (itemsNode == null || !itemsNode.isArray) return emptyList()
        return itemsNode.mapNotNull { node ->
            val name = node.text("name") ?: node.text("title")
            val entryDsl = node.text("entryDsl") ?: node.text("entry") ?: node.text("buy_condition")
            if (name.isNullOrBlank() || entryDsl.isNullOrBlank()) return@mapNotNull null
            val exitDsl = node.text("exitDsl") ?: node.text("exit") ?: node.text("sell_condition")
            StrategyBuilderAiGenerateItemDto(
                name = clip(name, 60),
                description = node.text("description") ?: "$name 전략",
                entryDsl = entryDsl,
                exitDsl = exitDsl,
                riskPlan = parseRiskPlan(node.get("riskPlan")),
                rationale = node.text("rationale") ?: node.text("reason") ?: "사용자 의도에 맞춘 지표 조합.",
                // dslValid는 검증 게이트가 채움 — 파싱 단계에서는 잠정 false.
                dslValid = false,
                dslError = null,
            )
        }
    }

    private fun parseRiskPlan(node: JsonNode?): StrategyBuilderAiRiskPlanDto {
        if (node == null || node.isNull) {
            return StrategyBuilderAiRiskPlanDto(null, null, null)
        }
        return StrategyBuilderAiRiskPlanDto(
            takeProfitPct = node.number("takeProfitPct") ?: node.number("takeProfit") ?: node.number("tp"),
            fixedStopLossPct = node.number("fixedStopLossPct") ?: node.number("stopLossPct") ?: node.number("sl"),
            minHoldBars = (node.number("minHoldBars") ?: node.number("minHold"))?.toInt(),
        )
    }

    private fun extractJson(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val fenced =
            Regex("```(?:json)?\\s*([\\s\\S]*?)```")
                .find(trimmed)
                ?.groupValues
                ?.get(1)
                ?.trim()
        if (!fenced.isNullOrBlank()) return fenced
        val startObject = trimmed.indexOf('{')
        val endObject = trimmed.lastIndexOf('}')
        if (startObject >= 0 && endObject > startObject) return trimmed.substring(startObject, endObject + 1)
        val startArray = trimmed.indexOf('[')
        val endArray = trimmed.lastIndexOf(']')
        if (startArray >= 0 && endArray > startArray) return trimmed.substring(startArray, endArray + 1)
        return trimmed
    }

    private fun clip(
        value: String,
        max: Int,
    ): String = if (value.length <= max) value else value.take(max)

    private fun JsonNode.text(fieldName: String): String? =
        get(fieldName)
            ?.takeIf { !it.isNull }
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonNode.number(fieldName: String): Double? =
        get(fieldName)
            ?.takeIf { it.isNumber || it.isTextual }
            ?.asText()
            ?.toDoubleOrNull()
}
