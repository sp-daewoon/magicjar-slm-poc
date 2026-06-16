package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.service.CandleHealthService
import kr.spdaewoon.magicjar.api.service.runtime.RealtimeStatusService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant

/**
 * RealtimeStatusService 단위 테스트 — Redis 스냅샷 + 종목명 join + stale 판정 + 1m 캔들 신선도.
 *
 * 검증:
 * - 신선한 스냅샷(updatedAt 최근) → stale=false + 종목명 join + ACTIVE 전략/실시간 체결 구독 종목 신선도.
 * - 오래된 스냅샷(15초 초과) → stale=true.
 * - 스냅샷 부재(consumer 미발행) → stale=true + 빈 컬렉션.
 */
class RealtimeStatusServiceTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val redis = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()
    private val instrumentRepo = mockk<InstrumentJpaRepository>()
    private val strategyRepo = mockk<StrategyJpaRepository>()
    private val candleHealth = mockk<CandleHealthService>()

    private fun service() =
        RealtimeStatusService(
            redisTemplate = redis,
            objectMapper = objectMapper,
            instrumentJpaRepository = instrumentRepo,
            strategyJpaRepository = strategyRepo,
            candleHealthService = candleHealth,
        )

    private fun instrument(
        code: String,
        name: String,
    ): InstrumentJpaEntity =
        mockk {
            every { this@mockk.code } returns code
            every { this@mockk.name } returns name
        }

    private fun activeStrategy(codesJson: String): StrategyJpaEntity = mockk { every { codes } returns codesJson }

    @Test
    fun `신선 스냅샷 — stale false + 종목명 join + ACTIVE 1m 신선도`() {
        val json =
            """
            {"updatedAt":"${Instant.now()}",
             "ws":{"connected":true,"sessionCid":"cid-1","reconnects24h":2,"lastConnectedAtMs":1700000000000},
             "slots":{"used":2,"max":40,"byTrId":{"H0UNCNT0":1,"H0UNASP0":1},
                      "codesByTrId":{"H0UNCNT0":["005930"],"H0UNASP0":["005930"]}},
             "subscriptions":[{"trId":"H0UNCNT0","code":"005930","owners":["strategy:86"],"sinceMs":1700000000000}],
             "executionNotice":{"subscribed":true,"mode":"MOCK","trId":"H0STCNI9","userIdMasked":"7468****"}}
            """.trimIndent()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get("ops:realtime:status") } returns json
        every { instrumentRepo.findAllById(any<Iterable<String>>()) } returns listOf(instrument("005930", "삼성전자"))
        every { strategyRepo.findAllByStatus("ACTIVE") } returns listOf(activeStrategy("[\"005930\"]"))
        every { candleHealth.assess("005930", "1m") } returns
            CandleHealthService.CandleHealth(
                code = "005930",
                interval = "1m",
                lastCandleAt = Instant.now(),
                staleSeconds = 12L,
                status = CandleHealthService.CandleHealthStatus.FRESH,
                reason = CandleHealthService.CandleHealthReason.OK_REALTIME,
                reasonDetail = "ok",
                checkedAt = Instant.now(),
            )

        val res = service().getStatus()

        assertThat(res.stale).isFalse()
        assertThat(res.ws.connected).isTrue()
        assertThat(res.slots.used).isEqualTo(2)
        // 슬롯 drill-down — channelsByTrId 종목명 join (배치 1회 조회로 코드+종목명 합성).
        assertThat(res.slots.channelsByTrId["H0UNCNT0"]).hasSize(1)
        assertThat(
            res.slots.channelsByTrId["H0UNCNT0"]!!
                .first()
                .code,
        ).isEqualTo("005930")
        assertThat(
            res.slots.channelsByTrId["H0UNCNT0"]!!
                .first()
                .codeName,
        ).isEqualTo("삼성전자")
        assertThat(res.subscriptions).hasSize(1)
        assertThat(res.subscriptions.first().codeName).isEqualTo("삼성전자")
        assertThat(res.subscriptions.first().since).isNotNull()
        assertThat(res.executionNotice.subscribed).isTrue()
        assertThat(res.candleFreshness).hasSize(1)
        assertThat(res.candleFreshness.first().ageSeconds).isEqualTo(12L)
        assertThat(res.candleFreshness.first().codeName).isEqualTo("삼성전자")
    }

    @Test
    fun `체결가 구독 종목은 ACTIVE 전략이 아니어도 1m 신선도에 포함한다`() {
        val json =
            """
            {"updatedAt":"${Instant.now()}",
             "ws":{"connected":true,"sessionCid":"cid-1","reconnects24h":0,"lastConnectedAtMs":1700000000000},
             "slots":{"used":2,"max":40,"byTrId":{"H0UNCNT0":1,"H0UNMKO0":1},
                      "codesByTrId":{"H0UNCNT0":["005930"],"H0UNMKO0":["005930"]}},
             "subscriptions":[
                {"trId":"H0UNCNT0","code":"005930","owners":["holding:005930"],"sinceMs":1700000000000},
                {"trId":"H0UNMKO0","code":"005930","owners":["vi:held"],"sinceMs":null}
             ],
             "executionNotice":{"subscribed":true,"mode":"MOCK","trId":"H0STCNI9","userIdMasked":"7468****"}}
            """.trimIndent()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get("ops:realtime:status") } returns json
        every { instrumentRepo.findAllById(any<Iterable<String>>()) } returns listOf(instrument("005930", "삼성전자"))
        every { strategyRepo.findAllByStatus("ACTIVE") } returns emptyList()
        every { candleHealth.assess("005930", "1m") } returns
            CandleHealthService.CandleHealth(
                code = "005930",
                interval = "1m",
                lastCandleAt = Instant.now(),
                staleSeconds = 7L,
                status = CandleHealthService.CandleHealthStatus.FRESH,
                reason = CandleHealthService.CandleHealthReason.OK_REALTIME,
                reasonDetail = "ok",
                checkedAt = Instant.now(),
            )

        val res = service().getStatus()

        assertThat(res.candleFreshness).hasSize(1)
        assertThat(res.candleFreshness.first().code).isEqualTo("005930")
        assertThat(res.candleFreshness.first().codeName).isEqualTo("삼성전자")
        assertThat(res.candleFreshness.first().ageSeconds).isEqualTo(7L)
    }

    @Test
    fun `오래된 스냅샷 — 15초 초과 stale true`() {
        val old = Instant.now().minusSeconds(30)
        val json =
            """
            {"updatedAt":"$old","ws":{"connected":true},"slots":{"used":0,"max":40,"byTrId":{}},
             "subscriptions":[],"executionNotice":{"subscribed":false,"mode":"MOCK","trId":"H0STCNI9"}}
            """.trimIndent()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get("ops:realtime:status") } returns json
        every { instrumentRepo.findAllById(any<Iterable<String>>()) } returns emptyList()
        every { strategyRepo.findAllByStatus("ACTIVE") } returns emptyList()

        val res = service().getStatus()

        assertThat(res.stale).isTrue()
        assertThat(res.ws.connected).isTrue()
    }

    @Test
    fun `스냅샷 부재 — stale true + 빈 상태`() {
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get("ops:realtime:status") } returns null

        val res = service().getStatus()

        assertThat(res.stale).isTrue()
        assertThat(res.updatedAt).isNull()
        assertThat(res.subscriptions).isEmpty()
        assertThat(res.candleFreshness).isEmpty()
    }
}
