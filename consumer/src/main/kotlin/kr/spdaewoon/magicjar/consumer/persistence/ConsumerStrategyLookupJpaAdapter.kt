package kr.spdaewoon.magicjar.consumer.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
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
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Consumer-side StrategyLookupPort implementation.
 *
 * api 모듈 StrategyLookupJpaAdapter는 consumer classpath에 없으므로, 실시간 자동매매 JVM에서 같은 테이블을
 * 직접 조회하고 dsl_entry/dsl_exit 텍스트를 AST로 재파싱한다.
 */
@Component
class ConsumerStrategyLookupJpaAdapter(
    private val strategyJpaRepository: ConsumerStrategyJpaRepository,
    private val objectMapper: ObjectMapper,
    private val kisYamlDslParser: KisYamlDslParser,
) : StrategyLookupPort {
    private val log = LoggerFactory.getLogger(ConsumerStrategyLookupJpaAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findActiveByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Strategy> {
        val entities =
            strategyJpaRepository.findActiveByCodeAndInterval(
                codeJson = objectMapper.writeValueAsString(listOf(code)),
                intervalJson = objectMapper.writeValueAsString(listOf(interval.code)),
            )
        return entities.mapNotNull { toDomainOrNull(it) }
    }

    /**
     * ADR 0114 §4-2 P2 — 청산 평가 대상: ACTIVE/BUY_BLOCKED ∪ DISABLED-with-position.
     * DISABLED여도 strategy_position 보유분이 있으면 청산 평가(손절/익절)를 받게 한다(자본 방치 해소).
     */
    @Transactional(readOnly = true)
    override fun findSellEvalByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Strategy> {
        val codeJson = objectMapper.writeValueAsString(listOf(code))
        val intervalJson = objectMapper.writeValueAsString(listOf(interval.code))
        val active = strategyJpaRepository.findActiveByCodeAndInterval(codeJson, intervalJson)
        val disabledHolding = strategyJpaRepository.findDisabledWithPositionByCodeAndInterval(codeJson, intervalJson)
        // id 기준 dedup(합집합 — DISABLED-with-position이 추가분). DELETED는 native 쿼리에서 이미 제외.
        return (active + disabledHolding)
            .distinctBy { it.id }
            .mapNotNull { toDomainOrNull(it) }
    }

    private fun toDomainOrNull(entity: ConsumerStrategyJpaEntity): Strategy? {
        val buy = parseDslOrNull(entity.id, "buy", entity.dslEntry)
        if (buy == null) {
            log.warn("평가 가능 strategy 매수 DSL 파싱 실패로 realtime 평가 제외: strategyId={}", entity.id)
            return null
        }
        val sell = entity.dslExit?.let { parseDslOrNull(entity.id, "sell", it) }
        val dsl =
            DslStrategyDefinition(
                name = entity.name,
                nameKo = entity.nameKo ?: "",
                buyCondition = buy,
                sellCondition = sell,
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
                // ADR 0114 진입 정책 JSONB(V72) → 도메인 (BUY 가격 산정 입력). 역직렬화 실패 시 null = 전역 default.
                entryPolicy = deserializeEntryPolicy(entity.entryPolicy),
            )
        val complexity =
            StrategyComplexity(
                astDepth = entity.astDepth,
                nodeCount = entity.nodeCount,
                indicatorCallCount = entity.indicatorCallCount,
                estimatedEvalTimeMs = entity.estimatedEvalTimeMs,
                complexityClass = safeComplexityClass(entity.complexityClass),
            )

        @Suppress("UNCHECKED_CAST")
        val evalMetricSummary =
            entity.evalMetricSummary?.let {
                runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any> }.getOrNull()
            }

        return Strategy(
            id = entity.id,
            userId = entity.userId,
            definition = definition,
            complexity = complexity,
            disabledReason = safeDisabledReason(entity.disabledReason),
            origin = safeOrigin(entity.origin),
            status = safeStatus(entity.status),
            codes = safeStringList(entity.codes),
            intervals = safeStringList(entity.intervals),
            lastDisabledAt = entity.lastDisabledAt,
            evalMetricSummary = evalMetricSummary,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    private fun parseDslOrNull(
        strategyId: Long?,
        side: String,
        text: String,
    ): DslNode? {
        if (text.isBlank()) return null
        return runCatching { kisYamlDslParser.parse(text) }
            .onFailure { e ->
                log.warn("consumer strategy DSL parse 실패: strategyId={} side={} err={}", strategyId, side, e.message)
            }.getOrNull()
    }

    private fun safeComplexityClass(value: String): ComplexityClass =
        runCatching { ComplexityClass.valueOf(value) }.getOrDefault(ComplexityClass.LOW)

    private fun safeDisabledReason(value: String?): StrategyDisabledReason =
        if (value == null) {
            StrategyDisabledReason.NULL
        } else {
            runCatching { StrategyDisabledReason.valueOf(value) }.getOrDefault(StrategyDisabledReason.NULL)
        }

    private fun safeStatus(value: String): StrategyStatus =
        runCatching { StrategyStatus.valueOf(value) }.getOrDefault(StrategyStatus.ACTIVE)

    private fun safeViActionPolicy(value: String?): ViActionPolicy =
        value
            ?.trim()
            ?.uppercase()
            ?.let { runCatching { ViActionPolicy.valueOf(it) }.getOrNull() }
            ?: ViActionPolicy.ALERT_ONLY

    /**
     * `origin` JSONB 없는 VARCHAR(V81) → enum (ADR 0122 §2-A · consumer capital 분기 판정 키).
     * null(legacy)·invalid → [StrategyOrigin.USER_DSL] default(실 KIS 잔고 경로 — zero-regression).
     */
    private fun safeOrigin(value: String?): StrategyOrigin =
        if (value == null) {
            StrategyOrigin.USER_DSL
        } else {
            runCatching { StrategyOrigin.valueOf(value) }.getOrDefault(StrategyOrigin.USER_DSL)
        }

    /**
     * `entry_policy` JSONB(V72) → [kr.spdaewoon.magicjar.domain.strategy.EntryPolicy] (ADR 0114).
     * [kr.spdaewoon.magicjar.domain.strategy.EntryPolicySerde] 단일 codec 위임 — JSON String → Map 변환만 담당.
     * 역직렬화·범위 검증 실패 시 null(전역 default MARKET 진입 — 실시간 hot path down 회피).
     */
    private fun deserializeEntryPolicy(value: String?): kr.spdaewoon.magicjar.domain.strategy.EntryPolicy? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
            kr.spdaewoon.magicjar.domain.strategy.EntryPolicySerde
                .fromMap(map)
        }.getOrNull()
    }

    private fun safeStringList(value: String?): List<String> =
        value
            ?.let {
                runCatching {
                    objectMapper
                        .readValue(it, List::class.java)
                        .mapNotNull { item ->
                            item?.toString()?.takeIf(String::isNotBlank)
                        }
                }.getOrNull()
            }
            ?: emptyList()
}
