package kr.spdaewoon.magicjar.api.service.daytrade

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.api.controller.daytrade.PaperPositionDto
import kr.spdaewoon.magicjar.api.controller.daytrade.PaperPromoteRequest
import kr.spdaewoon.magicjar.api.controller.daytrade.PaperStrategyDto
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchCandidateJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyOrigin
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

/**
 * 발굴(Discovery) → MOCK 페이퍼 트레이딩 dispatch 서비스 (ADR 0122 R2 · spec §3-R2 계약 LOCK).
 *
 * ### 책임 — 기존 자본 경로 100% 재사용 (신규 엔진 0 · ADR 0122 §2-B)
 * 발굴 빌트인 setup(`strategy_research_candidate`)을 사용자 opt-in 으로 페이퍼(MOCK) 전략 인스턴스로 승격한다.
 * 생성된 전략은 origin=PAPER_DISCOVERY·status=ACTIVE 로, 기존 RealtimeSignalGenerator → SignalGeneratedKafkaListener
 * (RiskGateway) → OrderRouter → TradingModeGate(MOCK 강제) 경로를 그대로 탄다. 신규 주문 엔진·Kafka 토픽·테이블 0.
 *
 * ### 자동 진입 절대 금지 (ADR 0122 §1-2 LOCK)
 * 본 서비스의 [promote] 는 **사용자 명시 액션(REST POST)** 에 의해서만 호출된다. 발굴 응답 핸들러·스코어카드 cron 이
 * 본 메서드를 호출하는 경로는 없다(코드 리뷰 reject 게이트). 발굴은 여전히 후보 출발점.
 *
 * ### 가상 자본 격리 (ADR 0122 §2-C · spec §1-3)
 * 페이퍼 RiskConfig 는 파생 키 `<userId>:paper` 별 행(옵션 A · §8-3)으로 분리 INSERT — 실 사용자 risk_config 무오염.
 * 가상 자본 잔액은 consumer PaperCapitalSnapshotProvider 가 산출(실 KIS 잔고 미호출). 페이퍼 포지션은 기존
 * strategy_position 에 strategyId 로 자연 귀속(별 테이블 0 · ADR 0110).
 *
 * ### watchlist write 0 LOCK
 * 페이퍼 감시 종목은 watchlist 가 아니라 STRATEGY owner WS 수요(`market.realtime.demand.v1`)로만 등록한다
 * (DaytradeDiscoverService.publishStrategyRealtimeDemands 패턴 1:1 — ownerId=`daytrade-paper:<strategyId>`).
 *
 * // AI 호출 0 — 페이퍼는 룰 기반 시그널 경로 100% (ADR 0122 §4-1 + development-rules §5)
 */
@Service
class DaytradePaperService(
    private val candidateRepository: StrategyResearchCandidateJpaRepository,
    private val strategyRepositoryPort: StrategyRepositoryPort,
    private val strategyJpaRepository: StrategyJpaRepository,
    private val strategyPositionReadRepository: StrategyPositionReadJpaRepository,
    private val strategyService: StrategyService,
    private val riskConfigRepository: RiskConfigRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val kisYamlDslParser: KisYamlDslParser,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${magicjar.daytrade.paper.enabled:true}")
    private val paperEnabled: Boolean = true,
    @Value("\${magicjar.daytrade.paper.default-capital-krw:10000000}")
    private val defaultCapitalKrw: BigDecimal = BigDecimal("10000000"),
    @Value("\${magicjar.daytrade.paper.max-instances:10}")
    private val maxInstances: Int = 10,
) {
    private val log = LoggerFactory.getLogger(DaytradePaperService::class.java)

    /**
     * 발굴 setup → MOCK 페이퍼 전략 일괄 승격 (사용자 opt-in · 다중선택 일괄 — spec §8-1).
     */
    @Transactional
    fun promote(request: PaperPromoteRequest): PaperStrategyDto {
        if (!paperEnabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "페이퍼 트레이딩 비활성 (MAGICJAR_DAYTRADE_PAPER_ENABLED=false)")
        }
        val userId = request.userId ?: throw badRequest("userId 필수")
        val presetKey = request.presetKey?.trim()?.takeIf { it.isNotBlank() } ?: throw badRequest("presetKey 필수")
        val codes =
            request.codes
                ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
                ?.distinct()
                .orEmpty()
        if (codes.isEmpty()) throw badRequest("codes 최소 1건 필요 (발굴 후보 선택)")

        // 활성 페이퍼 인스턴스 상한 가드 (ADR 0122 §5 — _MAX_INSTANCES).
        val activeCount =
            strategyJpaRepository.countByUserIdAndOriginAndStatusNot(
                userId,
                StrategyOrigin.PAPER_DISCOVERY.name,
                StrategyStatus.DELETED.name,
            )
        if (activeCount >= maxInstances) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "페이퍼 전략 상한 도달 (max=$maxInstances, 현재=$activeCount). 기존 페이퍼 전략을 중단 후 다시 시도하세요.",
            )
        }

        val candidate =
            candidateRepository.findByCandidateKey(presetKey)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "발굴 setup 없음: presetKey=$presetKey")

        val entryDsl = candidate.entryDsl.trim()
        if (entryDsl.isBlank()) throw badRequest("발굴 setup entry_dsl 비어 있음: presetKey=$presetKey")

        val entryAst =
            runCatching { kisYamlDslParser.parse(entryDsl) }
                .getOrElse { throw badRequest("발굴 setup entry_dsl 파싱 실패: ${it.message}") }
        val complexity = strategyService.validateAndComputeComplexity(entryAst)

        val interval = request.interval.takeIf { it.isNotBlank() } ?: "1d"
        val dsl =
            DslStrategyDefinition(
                name = "[페이퍼] ${candidate.title}",
                nameKo = "[모의] ${candidate.title}",
                buyCondition = entryAst,
                sellCondition = null,
                buyConditionText = entryDsl,
                sellConditionText = null,
            )
        val definition =
            StrategyDefinition(
                dsl = dsl,
                universe = Universe(market = "KOSPI"),
                tf = interval,
                // RiskPlan 필수(ensureRiskPlanIfBuy) — setup target/stop 을 손절·익절로 복제. 부재 시 안전 default.
                fixedStopLoss = candidate.stopLossPct ?: DEFAULT_STOP_LOSS_PCT,
                takeProfitPct = candidate.targetProfitPct ?: DEFAULT_TAKE_PROFIT_PCT,
                minHoldBars = candidate.maxHoldBars,
                viActionPolicy = ViActionPolicy.EXIT_MARKET,
            )
        val strategy =
            Strategy(
                userId = userId,
                definition = definition,
                complexity = complexity,
                disabledReason = StrategyDisabledReason.NULL,
                origin = StrategyOrigin.PAPER_DISCOVERY,
                status = StrategyStatus.ACTIVE,
                codes = codes,
                intervals = listOf(interval),
            )
        val saved = strategyRepositoryPort.save(strategy)
        val strategyId = saved.id ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "페이퍼 전략 저장 실패")

        // 페이퍼 RiskConfig 프로필 보장 (옵션 A — 파생 키 `<userId>:paper` 별 행. ADR 0121 사이징 정합).
        ensurePaperRiskProfile(userId)

        // STRATEGY owner WS 수요 등록 (watchlist write 0 — DaytradeDiscoverService 패턴 1:1).
        publishStrategyRealtimeDemands(strategyId, codes, interval)

        val virtualCapital = request.virtualCapitalKrw?.takeIf { it > BigDecimal.ZERO } ?: defaultCapitalKrw
        log.info(
            "페이퍼 전략 승격: userId={} strategyId={} presetKey={} codes={} interval={} virtualCapital={}",
            userId,
            strategyId,
            presetKey,
            codes,
            interval,
            virtualCapital,
        )
        return PaperStrategyDto(
            strategyId = strategyId,
            origin = StrategyOrigin.PAPER_DISCOVERY.name,
            presetKey = presetKey,
            presetTitle = candidate.title,
            codes = codes,
            intervals = listOf(interval),
            virtualCapital = virtualCapital,
            status = saved.status.name,
            updatedAt = saved.updatedAt.toString(),
        )
    }

    /**
     * 페이퍼 전략 중단 (soft delete · status=DELETED). 페이퍼 전략만 대상(실 전략 오삭제 가드).
     */
    @Transactional
    fun stop(
        strategyId: Long,
        userId: Long?,
    ) {
        val strategy =
            strategyRepositoryPort.findById(strategyId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "페이퍼 전략 없음: strategyId=$strategyId")
        if (!strategy.isPaperDiscovery()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "페이퍼 전략이 아닙니다 (origin=${strategy.origin}). 페이퍼 중단 API 대상 아님.")
        }
        if (userId != null && strategy.userId != userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "페이퍼 전략 없음: strategyId=$strategyId")
        }
        strategyJpaRepository.updateStatus(strategyId, StrategyStatus.DELETED.name)
        // WS 수요 해제 (best-effort — TTL 만료로도 자연 회수되나 즉시 EXIT 발행).
        runCatching { publishStrategyRealtimeDemandExit(strategyId, strategy.codes) }
            .onFailure { log.warn("페이퍼 WS 수요 해제 실패(무시): strategyId={} err={}", strategyId, it.message) }
        log.info("페이퍼 전략 중단(soft delete): strategyId={} userId={}", strategyId, strategy.userId)
    }

    /** 활성 페이퍼 전략 목록 조회 (DELETED 제외). */
    @Transactional(readOnly = true)
    fun listStrategies(userId: Long): List<PaperStrategyDto> =
        strategyRepositoryPort
            .findAllByUserId(userId)
            .filter { it.isPaperDiscovery() && it.status != StrategyStatus.DELETED }
            .map { strategy ->
                PaperStrategyDto(
                    strategyId = strategy.id ?: 0L,
                    origin = StrategyOrigin.PAPER_DISCOVERY.name,
                    presetKey = "",
                    presetTitle = strategy.definition.dsl.name,
                    codes = strategy.codes,
                    intervals = strategy.intervals,
                    virtualCapital = defaultCapitalKrw,
                    status = strategy.status.name,
                    updatedAt = strategy.updatedAt.toString(),
                )
            }

    /** 페이퍼 포지션·실현/평가손익 조회 (페이퍼 전략 귀속 strategy_position — 기존 ledger 재사용). */
    @Transactional(readOnly = true)
    fun listPositions(userId: Long): List<PaperPositionDto> {
        val paperStrategyIds =
            strategyRepositoryPort
                .findAllByUserId(userId)
                .filter { it.isPaperDiscovery() && it.status != StrategyStatus.DELETED }
                .mapNotNull { it.id }
                .toSet()
        if (paperStrategyIds.isEmpty()) return emptyList()
        val rows = paperStrategyIds.flatMap { strategyPositionReadRepository.findByStrategyId(it) }
        val codeNames = codeNamesFor(rows.map { it.code }.distinct())
        return rows.map { entity ->
            PaperPositionDto(
                strategyId = entity.strategyId,
                userId = entity.userId,
                code = entity.code,
                codeName = codeNames[entity.code],
                quantity = entity.quantity,
                avgPrice = entity.avgPrice,
                filledLots = entity.filledLots,
                realizedPnl = entity.realizedPnl,
                isClosed = entity.quantity == 0,
                updatedAt = entity.updatedAt.toString(),
            )
        }
    }

    // ── private helper ───────────────────────────────────────────────────────

    /**
     * 페이퍼 RiskConfig 프로필 보장 (옵션 A — `<userId>:paper` 별 행. ADR 0121 사이징 정합 LOCK).
     *
     * 부재 시에만 생성 — maxPositionPercent 10·maxConcurrentPositions 5(ADR 0121) · DLL 3%·maxTradesPerDay 50(§8-4 1차).
     * 가상 자본 규모(1천만) 기준 maxPositionAmount = 가상 자본의 maxPositionPercent(=10%) = 1,000,000 으로 설정해
     * 백테스트·페이퍼·실전 사이징 모델 일관(ADR 0121). 이미 있으면 사용자 조정값 보존(덮어쓰기 0).
     */
    private fun ensurePaperRiskProfile(userId: Long) {
        val paperKey = "$userId:paper"
        if (riskConfigRepository.findByUserId(paperKey) != null) return
        val now = Clock.System.now()
        val paperMaxPositionAmount =
            defaultCapitalKrw
                .multiply(PAPER_MAX_POSITION_PCT)
                .divide(BigDecimal(100))
                .max(BigDecimal.ONE)
        riskConfigRepository.save(
            RiskConfig(
                userId = paperKey,
                maxPositionAmount = paperMaxPositionAmount,
                maxPositionPercent = PAPER_MAX_POSITION_PCT,
                dailyLossLimitAmount = null,
                dailyLossLimitPercent = PAPER_DAILY_LOSS_LIMIT_PCT,
                maxConcurrentPositions = PAPER_MAX_CONCURRENT_POSITIONS,
                maxTradesPerMinute = PAPER_MAX_TRADES_PER_MINUTE,
                maxTradesPerDay = PAPER_MAX_TRADES_PER_DAY,
                autoPauseThreshold = PAPER_AUTO_PAUSE_THRESHOLD,
                createdAt = now,
                updatedAt = now,
            ),
        )
        log.info(
            "페이퍼 RiskConfig 프로필 생성(옵션 A): paperKey={} maxPositionPct={} maxConcurrent={}",
            paperKey,
            PAPER_MAX_POSITION_PCT,
            PAPER_MAX_CONCURRENT_POSITIONS,
        )
    }

    private fun publishStrategyRealtimeDemands(
        strategyId: Long,
        codes: List<String>,
        interval: String,
    ) {
        val ownerId = "daytrade-paper:$strategyId"
        codes.forEachIndexed { index, code ->
            runCatching {
                val payload =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "action" to "ENTER",
                            "owner" to "STRATEGY",
                            "ownerId" to ownerId,
                            "code" to code,
                            "priority" to (STRATEGY_REALTIME_PRIORITY_BASE + index),
                            "ttlSeconds" to STRATEGY_REALTIME_TTL_SECONDS,
                            "interval" to interval,
                            "source" to "DAYTRADE_PAPER",
                            "ts" to Instant.now().toString(),
                        ),
                    )
                kafkaTemplate.send(KafkaTopics.Market.REALTIME_DEMAND, code, payload)
            }.onFailure { ex ->
                log.warn("페이퍼 realtime demand 발행 실패(무시): ownerId={} code={} err={}", ownerId, code, ex.message)
            }
        }
        log.info(
            "페이퍼 realtime demand 발행(owner=STRATEGY, watchlist write 0): ownerId={} interval={} count={}",
            ownerId,
            interval,
            codes.size,
        )
    }

    private fun publishStrategyRealtimeDemandExit(
        strategyId: Long,
        codes: List<String>,
    ) {
        val ownerId = "daytrade-paper:$strategyId"
        codes.forEach { code ->
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "action" to "EXIT",
                        "owner" to "STRATEGY",
                        "ownerId" to ownerId,
                        "code" to code,
                        "source" to "DAYTRADE_PAPER",
                        "ts" to Instant.now().toString(),
                    ),
                )
            kafkaTemplate.send(KafkaTopics.Market.REALTIME_DEMAND, code, payload)
        }
    }

    private fun codeNamesFor(codes: List<String>): Map<String, String> {
        if (codes.isEmpty()) return emptyMap()
        return runCatching {
            instrumentJpaRepository.findAllById(codes).associate { it.code to it.name }
        }.getOrDefault(emptyMap())
    }

    private fun badRequest(message: String): ResponseStatusException = ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private companion object {
        // ADR 0121 사이징 정합 + spec §8-4 결재값.
        val PAPER_MAX_POSITION_PCT: BigDecimal = BigDecimal("10.00")
        val PAPER_DAILY_LOSS_LIMIT_PCT: BigDecimal = BigDecimal("3.00")
        const val PAPER_MAX_CONCURRENT_POSITIONS = 5
        const val PAPER_MAX_TRADES_PER_MINUTE = 3
        const val PAPER_MAX_TRADES_PER_DAY = 50
        const val PAPER_AUTO_PAUSE_THRESHOLD = 10

        // RiskPlan 안전 default (setup 에 target/stop 부재 시) — 단타 보수값.
        val DEFAULT_STOP_LOSS_PCT: BigDecimal = BigDecimal("2.00")
        val DEFAULT_TAKE_PROFIT_PCT: BigDecimal = BigDecimal("3.00")

        // WS 수요 — DaytradeDiscoverService 패턴 1:1 (priority 40 base · TTL 30분).
        const val STRATEGY_REALTIME_PRIORITY_BASE = 40
        const val STRATEGY_REALTIME_TTL_SECONDS = 30L * 60L
    }
}
