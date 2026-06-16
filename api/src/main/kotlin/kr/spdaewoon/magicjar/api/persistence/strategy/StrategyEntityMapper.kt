package kr.spdaewoon.magicjar.api.persistence.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.EntryPolicy
import kr.spdaewoon.magicjar.domain.strategy.EntryPolicySerde
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyOrigin
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import java.math.BigDecimal

/**
 * Strategy JPA Entity ↔ Domain 변환 단일 책임 mapper.
 *
 * ### 의도 (P2-4 진보 페르소나 refactor — ADR 0087)
 * 좌우명: "레거시는 빚이다. 이자가 붙기 전에 갚아라"
 *
 * `StrategyJpaAdapter`·`StrategyLookupJpaAdapter` 양 어댑터가 동일한 `toDomain()` 약 42줄을
 * 각자 복제 보유했었음 (DRY 위반 — 한 쪽 schema 변경 시 양쪽 동기화 누락 risk).
 * Strategy entity 변환 책임을 단일 object로 추출하여 추가 어댑터(예: backtest·screener
 * read-only path)에서도 재사용 가능하도록 캡슐화.
 *
 * ### 헌법 §6 (ADR 0051) 정합
 * - Spring bean 0 (object 정적 singleton) — JPA Repository default fun 함정 영역 아님
 * - Repository interface 미터치 — `findActiveByCodeAndInterval` 등 query inference 무관
 *
 * ### 자본 path 안전성
 * - Pure function (DB·KIS·외부 IO 0건)
 * - exception은 caller에 전파 (ObjectMapper readValue 실패 등은 caller가 fallback 결정)
 * - 단, `safeComplexityClass`·`safeDisabledReason`은 enum 변환 실패를 default로 흡수
 *   (DB에 invalid enum row 존재 시 service 전체 down 회피 — 기존 어댑터 정합)
 *
 * AI 호출 0 — refactor only
 */
object StrategyEntityMapper {
    /**
     * Entity → Domain 변환.
     *
     * @param entity JPA entity (영속 직후 또는 조회 결과)
     * @param objectMapper `eval_metric_summary` JSONB 역직렬화용
     */
    fun toDomain(
        entity: StrategyJpaEntity,
        objectMapper: ObjectMapper,
        buyCondition: DslNode? = null,
        sellCondition: DslNode? = null,
    ): Strategy {
        val dsl =
            DslStrategyDefinition(
                name = entity.name,
                nameKo = entity.nameKo ?: "",
                buyCondition = buyCondition,
                sellCondition = sellCondition,
                buyConditionText = entity.dslEntry,
                sellConditionText = entity.dslExit,
            )
        val definition =
            StrategyDefinition(
                dsl = dsl,
                universe = Universe(market = entity.universeMarket),
                tf = entity.tf,
                fixedStopLoss = entity.fixedStopLossPct,
                takeProfitPct = entity.takeProfitPct,
                // V73 잔익절 방어 B안 — minHoldBars(진입 후 N캔들 DSL 매도 무시). null=비활성(현행).
                minHoldBars = entity.minHoldBars,
                // V82 VI 행동 정책 — invalid row는 ALERT_ONLY(주문 0)로 흡수.
                viActionPolicy = safeViActionPolicy(entity.viActionPolicy),
                // ADR 0111 scale-in/partial-exit JSONB(V65) → 도메인 wiring (분할매수 R3-a).
                scaleIn = deserializeScaleIn(entity.scaleInConfig, objectMapper),
                partialExits = deserializePartialExits(entity.partialExits, objectMapper),
                // ADR 0114 진입 정책 JSONB(V72) → 도메인 wiring (EntryPolicySerde 단일 codec).
                entryPolicy = deserializeEntryPolicy(entity.entryPolicy, objectMapper),
            )
        val complexity =
            StrategyComplexity(
                astDepth = entity.astDepth,
                nodeCount = entity.nodeCount,
                indicatorCallCount = entity.indicatorCallCount,
                estimatedEvalTimeMs = entity.estimatedEvalTimeMs,
                complexityClass = safeComplexityClass(entity.complexityClass),
            )
        val disabledReason = safeDisabledReason(entity.disabledReason)

        @Suppress("UNCHECKED_CAST")
        val evalMetricSummary: Map<String, Any>? =
            entity.evalMetricSummary?.let {
                runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any> }.getOrNull()
            }

        return Strategy(
            id = entity.id,
            userId = entity.userId,
            definition = definition,
            complexity = complexity,
            disabledReason = disabledReason,
            origin = safeOrigin(entity.origin),
            status = safeStatus(entity.status),
            codes = safeStringList(entity.codes, objectMapper),
            intervals = safeStringList(entity.intervals, objectMapper),
            lastDisabledAt = entity.lastDisabledAt,
            evalMetricSummary = evalMetricSummary,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    /**
     * `complexity_class` 문자열 → enum.
     *
     * DB에 invalid 값(미래 enum 추가 후 rollback 등) 존재 시 [ComplexityClass.LOW] default.
     * service 전체 down 회피 — 기존 어댑터 정합 (P2-4 회귀 0 LOCK).
     */
    fun safeComplexityClass(value: String): ComplexityClass =
        runCatching { ComplexityClass.valueOf(value) }.getOrDefault(ComplexityClass.LOW)

    /**
     * `disabled_reason` 문자열(nullable) → enum.
     *
     * null 또는 invalid 값은 [StrategyDisabledReason.NULL] default.
     */
    fun safeDisabledReason(value: String?): StrategyDisabledReason =
        if (value == null) {
            StrategyDisabledReason.NULL
        } else {
            runCatching { StrategyDisabledReason.valueOf(value) }.getOrDefault(StrategyDisabledReason.NULL)
        }

    fun safeStatus(value: String): StrategyStatus = runCatching { StrategyStatus.valueOf(value) }.getOrDefault(StrategyStatus.ACTIVE)

    fun safeViActionPolicy(value: String?): ViActionPolicy =
        value
            ?.trim()
            ?.uppercase()
            ?.let { runCatching { ViActionPolicy.valueOf(it) }.getOrNull() }
            ?: ViActionPolicy.ALERT_ONLY

    /**
     * `origin` 문자열(nullable) → enum (ADR 0122 §2-A · V81).
     *
     * null(legacy V81 이전 행) 또는 invalid 값은 [StrategyOrigin.USER_DSL] default — 하위호환 zero-regression.
     * 페이퍼 식별(`PAPER_DISCOVERY`)은 명시 INSERT된 행에서만 나온다(`safe*` 패턴 정합).
     */
    fun safeOrigin(value: String?): StrategyOrigin =
        if (value == null) {
            StrategyOrigin.USER_DSL
        } else {
            runCatching { StrategyOrigin.valueOf(value) }.getOrDefault(StrategyOrigin.USER_DSL)
        }

    private fun safeStringList(
        value: String?,
        objectMapper: ObjectMapper,
    ): List<String> =
        value
            ?.let {
                runCatching {
                    objectMapper
                        .readValue(it, List::class.java)
                        .mapNotNull { item -> item?.toString()?.takeIf(String::isNotBlank) }
                }.getOrNull()
            }
            ?: emptyList()

    /**
     * `scale_in_config` JSONB(V65) → [ScaleInPlan] (ADR 0111 분할매수).
     *
     * 도메인 모델([ScaleInPlan])의 jackson 어노테이션 없는 평탄 데이터 클래스를 raw map으로 파싱 후 생성한다
     * (도메인은 Spring·jackson import 금지 — ScaleInPlan.kt KDoc 정합). init 검증 실패·역직렬화 실패 시
     * null 흡수(invalid DB row가 조회 전체를 down 시키지 않도록 — `safe*` 패턴 정합).
     */
    private fun deserializeScaleIn(
        value: String?,
        objectMapper: ObjectMapper,
    ): ScaleInPlan? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val node = objectMapper.readTree(value)
            if (!node.isObject) return null
            val totalSlices = node.get("totalSlices")?.takeIf { it.isNumber }?.asInt() ?: return null
            val sliceWeights =
                node
                    .get("sliceWeights")
                    ?.takeIf { it.isArray }
                    ?.mapNotNull { it?.let { w -> BigDecimal(w.asText()) } }
                    ?.takeIf { it.isNotEmpty() }
            val reentryTrigger =
                node
                    .get("reentryTrigger")
                    ?.asText()
                    ?.let { runCatching { ReentryTrigger.valueOf(it) }.getOrNull() }
                    ?: ReentryTrigger.SIGNAL_REPEAT
            val priceDropPct = node.get("priceDropPct")?.takeIf { !it.isNull }?.let { BigDecimal(it.asText()) }
            val minSliceIntervalSec = node.get("minSliceIntervalSec")?.takeIf { it.isNumber }?.asInt()
            ScaleInPlan(
                totalSlices = totalSlices,
                sliceWeights = sliceWeights,
                reentryTrigger = reentryTrigger,
                priceDropPct = priceDropPct,
                minSliceIntervalSec = minSliceIntervalSec,
            )
        }.getOrNull()
    }

    /**
     * `entry_policy` JSONB(V72) → [EntryPolicy] (ADR 0114 진입 집행 정책).
     *
     * [EntryPolicySerde]가 도메인 변환 단일 책임 — 본 mapper는 JSON String → Map 변환만 담당하고 codec에 위임한다.
     * 역직렬화·init 범위 검증 실패 시 null 흡수(현행 = 전역 default MARKET 진입. `safe*` 패턴 정합).
     */
    private fun deserializeEntryPolicy(
        value: String?,
        objectMapper: ObjectMapper,
    ): EntryPolicy? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
            EntryPolicySerde.fromMap(map)
        }.getOrNull()
    }

    /**
     * `partial_exits` JSONB(V65) → [List]<[PartialExit]> (ADR 0111 분할익절).
     *
     * 단계당 [PartialExit.init] + [StrategyDefinition.init]의 cap·weight 합 검증을 통과해야 한다.
     * 역직렬화·검증 실패 시 null 흡수(`safe*` 패턴 정합).
     */
    private fun deserializePartialExits(
        value: String?,
        objectMapper: ObjectMapper,
    ): List<PartialExit>? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val node = objectMapper.readTree(value)
            if (!node.isArray) return null
            node
                .mapNotNull { stage ->
                    if (stage == null || !stage.isObject) return@mapNotNull null
                    val trigger =
                        stage.get("triggerProfitPct")?.takeIf { !it.isNull }?.let { BigDecimal(it.asText()) } ?: return@mapNotNull null
                    val weight =
                        stage.get("exitWeightPct")?.takeIf { !it.isNull }?.let { BigDecimal(it.asText()) } ?: return@mapNotNull null
                    PartialExit(triggerProfitPct = trigger, exitWeightPct = weight)
                }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
