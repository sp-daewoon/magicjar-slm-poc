package kr.spdaewoon.magicjar.api.screener

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.api.application.screener.MultiCriteriaRanker
import kr.spdaewoon.magicjar.api.controller.screener.DaytradeDiscoverRequest
import kr.spdaewoon.magicjar.api.infrastructure.cache.DaytradeDiscoverCache
import kr.spdaewoon.magicjar.api.service.screener.DaytradeDiscoverService
import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.screener.ScreenerFilter
import kr.spdaewoon.magicjar.domain.screener.port.InstrumentLookupPort
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandidateDefinition
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandidateProvider
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal

/**
 * DaytradeDiscoverService 단위 테스트 (ADR 0117 R2 + spec §3-R2).
 *
 * 검증 케이스:
 * 1. happy-path — strategyId 없이 빌트인 DAYTRADE_* 프리셋으로 후보를 발굴해 반환한다.
 * 2. startRealtimeWatch=false — STRATEGY owner WS 수요 발행 0 (Kafka publish 0) + realtimeWatchRegistered=0.
 * 3. startRealtimeWatch=true — 후보를 market.realtime.demand.v1 owner=STRATEGY 로만 발행 (watchlist write 0 — 구조적 보장).
 *
 * ### watchlist write 0 증명 (owner 가드 — CLAUDE.md · spec §1-2)
 * 본 서비스 생성자에 watchlist Repository 의존이 0이다(컴파일 구조상 watchlist write 불가능).
 * 실시간 수요는 오직 KafkaTemplate("market.realtime.demand.v1", owner=STRATEGY)로만 발행됨을 verify로 고정한다.
 */
class DaytradeDiscoverServiceTest {
    private val parser = KisYamlDslParser(astDepthLimit = 32, nodeCountLimit = 10000, indicatorCallLimit = 50)
    private val ranker = MultiCriteriaRanker()
    private val instrumentLookup: InstrumentLookupPort = mock()
    private val candleRepository: CandleRepository = mock()
    private val evaluator: StrategyEvaluatorPort = mock()
    private val kafkaTemplate: KafkaTemplate<String, String> = mock()
    private val objectMapper = ObjectMapper()

    /** in-memory StringRedisTemplate 백킹 — DaytradeDiscoverCache 단위 테스트용 (실 Redis 불필요). */
    private val redisStore = mutableMapOf<String, String>()
    private val redisTemplate: org.springframework.data.redis.core.StringRedisTemplate =
        mock<org.springframework.data.redis.core.StringRedisTemplate>().also { tpl ->
            val ops = mock<org.springframework.data.redis.core.ValueOperations<String, String>>()
            whenever(tpl.opsForValue()).thenReturn(ops)
            whenever(ops.get(any<String>())).thenAnswer { redisStore[it.getArgument(0)] }
            org.mockito.kotlin
                .doAnswer {
                    redisStore[it.getArgument(0)] = it.getArgument(1)
                    Unit
                }.whenever(ops)
                .set(any<String>(), any<String>(), any<java.time.Duration>())
        }
    private val discoverCache =
        DaytradeDiscoverCache(
            redisTemplate = redisTemplate,
            objectMapper = ObjectMapper().registerKotlinModule(),
            ttlMinutes = 60,
            cacheEnabled = true,
        )

    /** V75 시드 단타 프리셋을 흉내내는 provider — entry_dsl은 일봉 표현 가능 식별자만 사용 (trade_intensity 미포함). */
    private val candidateProvider =
        ResearchCandidateProvider {
            listOf(
                ResearchCandidateDefinition(
                    key = "DAYTRADE_GAP_RVOL_TURNOVER",
                    title = "장전 갭상승·RVOL·거래대금 setup",
                    marketFit = "단타 적합",
                    entryRules = listOf("갭상승 1.5%", "RVOL 2.5배", "거래대금 100억"),
                    entryDsl =
                        "open >= prev_close * 1.015 and volume > volume_ma(20) * 2.5 and " +
                            "close * volume > 10000000000 and close > 2000",
                    isBuiltin = true,
                ),
                // 비-단타 후보는 default 발굴에서 제외됨을 검증하기 위한 noise
                ResearchCandidateDefinition(
                    key = "MA10_RECLAIM_SCALP",
                    title = "MA10 회복",
                    marketFit = "스캘프",
                    entryRules = listOf("MA10 회복"),
                    entryDsl = "close crosses_above ma(10)",
                    isBuiltin = true,
                ),
            )
        }

    private fun service(enabled: Boolean = true): DaytradeDiscoverService =
        DaytradeDiscoverService(
            candidateProvider = candidateProvider,
            parser = parser,
            instrumentLookupProvider = providerOf(instrumentLookup),
            candleRepositoryProvider = providerOf(candleRepository),
            strategyEvaluatorPort = evaluator,
            ranker = ranker,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            discoverCache = discoverCache,
            discoveryEnabled = enabled,
            candleLookbackDays = 90,
            minCandles = 20,
        )

    @org.junit.jupiter.api.BeforeEach
    fun resetCache() {
        redisStore.clear()
    }

    @Test
    fun `strategyId 없이 빌트인 DAYTRADE 프리셋으로 후보를 발굴한다`() {
        whenever(instrumentLookup.findActive(any<ScreenerFilter>())).thenReturn(
            listOf(instrument("005930", "삼성전자"), instrument("000660", "SK하이닉스")),
        )
        whenever(candleRepository.findLatestByCodeAndInterval(any(), any(), any())).thenReturn(buildCandles("X", 60))
        whenever(evaluator.evaluate(any(), any<DslNode>(), any())).thenReturn(buyResult())

        val result = service().discover(DaytradeDiscoverRequest(userId = 12345678L, startRealtimeWatch = false))

        // 빌트인 DAYTRADE_* 프리셋만 사용 (default — MA10_RECLAIM_SCALP 는 제외)
        assertThat(result.presets).hasSize(1)
        assertThat(result.presets.first().presetKey).isEqualTo("DAYTRADE_GAP_RVOL_TURNOVER")
        assertThat(result.isBuiltin).isTrue()
        assertThat(result.universeSize).isEqualTo(2)
        assertThat(result.matchedCount).isEqualTo(2)
        assertThat(result.matches.map { it.instrumentCode }).contains("005930", "000660")
        assertThat(result.matches.first().rank).isEqualTo(1)
    }

    @Test
    fun `startRealtimeWatch false면 STRATEGY 수요 발행 0`() {
        whenever(instrumentLookup.findActive(any<ScreenerFilter>())).thenReturn(listOf(instrument("005930", "삼성전자")))
        whenever(candleRepository.findLatestByCodeAndInterval(any(), any(), any())).thenReturn(buildCandles("X", 60))
        whenever(evaluator.evaluate(any(), any<DslNode>(), any())).thenReturn(buyResult())

        val result = service().discover(DaytradeDiscoverRequest(userId = 12345678L, startRealtimeWatch = false))

        assertThat(result.realtimeWatchRegistered).isEqualTo(0)
        // watchlist write 0: 실시간 수요 발행 경로(Kafka)조차 호출되지 않음
        verify(kafkaTemplate, never()).send(any(), any(), any())
    }

    @Test
    fun `startRealtimeWatch true면 STRATEGY owner WS 수요로만 발행한다 - watchlist write 0`() {
        whenever(instrumentLookup.findActive(any<ScreenerFilter>())).thenReturn(listOf(instrument("005930", "삼성전자")))
        whenever(candleRepository.findLatestByCodeAndInterval(any(), any(), any())).thenReturn(buildCandles("X", 60))
        whenever(evaluator.evaluate(any(), any<DslNode>(), any())).thenReturn(buyResult())

        val result = service().discover(DaytradeDiscoverRequest(userId = 12345678L, startRealtimeWatch = true))

        assertThat(result.realtimeWatchRegistered).isEqualTo(1)
        // 발행은 오직 market.realtime.demand.v1 토픽 (owner=STRATEGY) — watchlist 테이블 write 경로 0 (구조적 보장)
        val topicCaptor = org.mockito.kotlin.argumentCaptor<String>()
        val payloadCaptor = org.mockito.kotlin.argumentCaptor<String>()
        verify(kafkaTemplate).send(topicCaptor.capture(), any(), payloadCaptor.capture())
        assertThat(topicCaptor.firstValue).isEqualTo(KafkaTopics.Market.REALTIME_DEMAND)
        assertThat(payloadCaptor.firstValue).contains("\"owner\":\"STRATEGY\"")
        assertThat(payloadCaptor.firstValue).contains("\"source\":\"DAYTRADE_DISCOVER\"")
    }

    @Test
    fun `candle 결손 종목은 skip 매트릭스에 정직하게 표기한다 - mock 합성 0`() {
        whenever(instrumentLookup.findActive(any<ScreenerFilter>())).thenReturn(
            listOf(instrument("005930", "삼성전자"), instrument("000660", "SK하이닉스")),
        )
        whenever(
            candleRepository.findLatestByCodeAndInterval(org.mockito.kotlin.eq("005930"), any(), any()),
        ).thenReturn(buildCandles("005930", 60))
        whenever(candleRepository.findLatestByCodeAndInterval(org.mockito.kotlin.eq("000660"), any(), any())).thenReturn(emptyList())
        whenever(evaluator.evaluate(any(), any<DslNode>(), any())).thenReturn(buyResult())

        val result = service().discover(DaytradeDiscoverRequest(userId = 12345678L))

        assertThat(result.matchedCount).isEqualTo(1)
        assertThat(result.skippedReasons).containsKey("캔들 미수집")
    }

    @Test
    fun `변동성 큰 mover의 마지막 봉 일중 변동이 20퍼센트를 넘어도 ATR 점수는 0이 아니다 (회귀)`() {
        // 회귀: 단일 캔들 (high-low)/close > 0.20 이면 옛 sweet-spot이 0.0으로 clamp 했음.
        // 수정 후엔 14봉 smoothed NATR(약 9~10%)로 평가 → 0보다 큰 점수.
        whenever(instrumentLookup.findActive(any<ScreenerFilter>())).thenReturn(listOf(instrument("089030", "테크윙")))
        whenever(candleRepository.findLatestByCodeAndInterval(any(), any(), any())).thenReturn(buildVolatileMoverCandles("089030", 60))
        whenever(evaluator.evaluate(any(), any<DslNode>(), any())).thenReturn(buyResult())

        val result = service().discover(DaytradeDiscoverRequest(userId = 12345678L, startRealtimeWatch = false))

        assertThat(result.matchedCount).isEqualTo(1)
        val atr = result.matches.first().scoreComponents["atr"]!!
        assertThat(atr).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `같은 거래일 재호출은 캐시 HIT로 universe 재평가를 생략한다 (perf)`() {
        whenever(instrumentLookup.findActive(any<ScreenerFilter>())).thenReturn(
            listOf(instrument("005930", "삼성전자"), instrument("000660", "SK하이닉스")),
        )
        whenever(candleRepository.findLatestByCodeAndInterval(any(), any(), any())).thenReturn(buildCandles("X", 60))
        whenever(evaluator.evaluate(any(), any<DslNode>(), any())).thenReturn(buyResult())

        val svc = service()
        val first = svc.discover(DaytradeDiscoverRequest(userId = 12345678L, startRealtimeWatch = false))
        val second = svc.discover(DaytradeDiscoverRequest(userId = 99999999L, startRealtimeWatch = false))

        // 두 호출 결과의 후보는 동일 (userId 무관 캐시)
        assertThat(second.matches.map { it.instrumentCode }).isEqualTo(first.matches.map { it.instrumentCode })
        // 2차 호출은 evaluator를 1회도 더 호출하지 않음 (캐시 HIT — universe 재평가 생략)
        // 1차: universe 2종목 × 프리셋 평가. 2차가 캐시 HIT면 추가 evaluate 0.
        verify(evaluator, org.mockito.kotlin.times(2)).evaluate(any(), any<DslNode>(), any())
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────────────────────

    private fun buyResult(): StrategyEvalResult =
        StrategyEvalResult(
            signal = Signal.BUY,
            strength = BigDecimal("0.8"),
            reason = mapOf("trigger" to "DAYTRADE_GAP"),
            evaluatedAt = Clock.System.now(),
            evalTimeMs = 5,
        )

    private fun instrument(
        code: String,
        name: String,
    ): Instrument =
        Instrument(
            code = code,
            isin = null,
            name = name,
            nameEn = null,
            market = InstrumentMarket.KOSPI,
            type = InstrumentType.STOCK,
            sector = null,
            listedAt = LocalDate(2010, 1, 1),
            delistedAt = null,
            active = true,
        )

    private fun buildCandles(
        code: String,
        count: Int,
    ): List<Candle> {
        val now = Clock.System.now()
        return (0 until count).map { i ->
            Candle(
                code = code,
                interval = CandleInterval.ONE_DAY,
                ts = now.minus(kotlin.time.Duration.parse("${count - i}d")),
                openRaw = BigDecimal("70000"),
                highRaw = BigDecimal("71000"),
                lowRaw = BigDecimal("69500"),
                closeRaw = BigDecimal("70500"),
                openAdj = BigDecimal("70000"),
                highAdj = BigDecimal("71000"),
                lowAdj = BigDecimal("69500"),
                closeAdj = BigDecimal("70500"),
                volume = 1_000_000L,
                source = "TEST",
            )
        }
    }

    /**
     * 변동성 큰 mover 픽스처 — 마지막 봉의 (high-low)/close 가 0.20을 초과하도록 구성하되,
     * 14봉 평균 TrueRange/close(NATR)는 약 9~10% 수준이 되게 만든다 (실측 089030 패턴 재현).
     */
    private fun buildVolatileMoverCandles(
        code: String,
        count: Int,
    ): List<Candle> {
        val now = Clock.System.now()
        return (0 until count).map { i ->
            val isLast = i == count - 1
            // 평시: 약 6% 범위 (low 9700~high 10300, close 10000). 마지막 봉: 범위 24% (low 8800~high 11200).
            val high = if (isLast) "11200" else "10300"
            val low = if (isLast) "8800" else "9700"
            Candle(
                code = code,
                interval = CandleInterval.ONE_DAY,
                ts = now.minus(kotlin.time.Duration.parse("${count - i}d")),
                openRaw = BigDecimal("10000"),
                highRaw = BigDecimal(high),
                lowRaw = BigDecimal(low),
                closeRaw = BigDecimal("10000"),
                openAdj = BigDecimal("10000"),
                highAdj = BigDecimal(high),
                lowAdj = BigDecimal(low),
                closeAdj = BigDecimal("10000"),
                volume = 5_000_000L,
                source = "TEST",
            )
        }
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        object : ObjectProvider<T> {
            override fun getObject(vararg args: Any?): T = value

            override fun getObject(): T = value

            override fun getIfAvailable(): T = value

            override fun getIfUnique(): T = value
        }
}
