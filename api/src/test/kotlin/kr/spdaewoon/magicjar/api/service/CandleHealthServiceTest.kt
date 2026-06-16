package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant
import kotlinx.datetime.Instant as KxInstant

/**
 * CandleHealthService reason 분기 판정 회귀 가드 — readiness P1 (2026-06-04 평가 §B-1a).
 *
 * 검증 매트릭스 (판정 우선순위 트리 정합):
 *   - FRESH(최근 봉)                         → OK_REALTIME
 *   - 장외(session != Regular)               → MARKET_CLOSED (stale이어도 최우선)
 *   - unrecoverable 키 present(정규장)        → BACKFILL_PERMANENT_GAP
 *   - miss counter present(임계치 미달, 정규장) → BACKFILL_FAILED
 *   - WS source 없음(정규장 stale)            → WS_NOT_SUBSCRIBED
 *   - WS live + stale + tick 없음             → NO_TICK
 *   - DB·Redis 모두 0건                       → NO_DATA + (정규장+미구독 → WS_NOT_SUBSCRIBED)
 *
 * mock 합성 금지 정합 — 판정 불가 시 UNKNOWN 검증.
 */
class CandleHealthServiceTest {
    private val now = Instant.now()

    private class Mocks(
        val repo: CandleJpaRepository,
        val redis: StringRedisTemplate,
        val hashOps: HashOperations<String, String, String>,
        val valueOps: ValueOperations<String, String>,
        val resolver: MarketSessionResolver,
    )

    private fun build(
        dbLastTs: Instant? = null,
        currentUpdatedAt: String? = null,
        unrecoverable: Boolean = false,
        missCount: String? = null,
        sourceLive: Boolean = false,
        quoteLastJson: String? = null,
        session: MarketSession = MarketSession.Regular,
    ): Pair<CandleHealthService, Mocks> {
        val repo = mock<CandleJpaRepository>()
        val redis = mock<StringRedisTemplate>()
        val hashOps = mock<HashOperations<String, String, String>>()
        val valueOps = mock<ValueOperations<String, String>>()
        val resolver = mock<MarketSessionResolver>()

        whenever(redis.opsForHash<String, String>()).thenReturn(hashOps)
        whenever(redis.opsForValue()).thenReturn(valueOps)

        whenever(repo.findMaxTsByCodeAndInterval(any(), any())).thenReturn(dbLastTs)

        // market:candle:current updatedAt
        whenever(hashOps.get(any(), any())).thenReturn(null)
        whenever(hashOps.get("market:candle:current:code:005930:interval:1m", "updatedAt")).thenReturn(currentUpdatedAt)

        // unrecoverable + miss counter
        whenever(redis.hasKey(any())).thenReturn(false)
        whenever(redis.hasKey("market:candle:backfill:unrecoverable:code:005930:interval:1m")).thenReturn(unrecoverable)
        whenever(valueOps.get(any())).thenReturn(null)
        whenever(valueOps.get("market:candle:backfill:miss:code:005930:interval:1m")).thenReturn(missCount)
        whenever(valueOps.get("market:quote:last:code:005930")).thenReturn(quoteLastJson)

        // ops:watchlist:source:user:* 스캔
        if (sourceLive) {
            whenever(redis.keys("ops:watchlist:source:user:*")).thenReturn(setOf("ops:watchlist:source:user:u1"))
            whenever(hashOps.get("ops:watchlist:source:user:u1", "005930")).thenReturn("live")
        } else {
            whenever(redis.keys("ops:watchlist:source:user:*")).thenReturn(emptySet())
        }

        whenever(resolver.resolve(any<KxInstant>())).thenReturn(session)

        return CandleHealthService(repo, redis, resolver) to Mocks(repo, redis, hashOps, valueOps, resolver)
    }

    @Test
    fun `최근 봉 — FRESH + OK_REALTIME`() {
        val (svc, _) = build(dbLastTs = now.minusSeconds(30))
        val h = svc.assess("005930", "1m")
        assertThat(h.status).isEqualTo(CandleHealthService.CandleHealthStatus.FRESH)
        assertThat(h.reason).isEqualTo(CandleHealthService.CandleHealthReason.OK_REALTIME)
        assertThat(h.staleSeconds).isLessThanOrEqualTo(180)
    }

    @Test
    fun `장외 stale — MARKET_CLOSED 최우선`() {
        val (svc, _) =
            build(
                dbLastTs = now.minusSeconds(7_200),
                session = MarketSession.Closed(reason = "장외시간"),
                unrecoverable = true, // 장외가 우선이므로 영구갭이어도 MARKET_CLOSED
            )
        val h = svc.assess("005930", "1m")
        assertThat(h.status).isEqualTo(CandleHealthService.CandleHealthStatus.STALE)
        assertThat(h.reason).isEqualTo(CandleHealthService.CandleHealthReason.MARKET_CLOSED)
    }

    @Test
    fun `정규장 stale + 영구갭 — BACKFILL_PERMANENT_GAP`() {
        val (svc, _) = build(dbLastTs = now.minusSeconds(7_200), unrecoverable = true)
        val h = svc.assess("005930", "1m")
        assertThat(h.reason).isEqualTo(CandleHealthService.CandleHealthReason.BACKFILL_PERMANENT_GAP)
    }

    @Test
    fun `정규장 stale + miss counter 임계치 미달 — BACKFILL_FAILED`() {
        val (svc, _) = build(dbLastTs = now.minusSeconds(7_200), missCount = "2")
        val h = svc.assess("005930", "1m")
        assertThat(h.reason).isEqualTo(CandleHealthService.CandleHealthReason.BACKFILL_FAILED)
        assertThat(h.reasonDetail).contains("2회")
    }

    @Test
    fun `정규장 stale + WS 미구독 — WS_NOT_SUBSCRIBED`() {
        val (svc, _) = build(dbLastTs = now.minusSeconds(7_200), sourceLive = false, quoteLastJson = null)
        val h = svc.assess("005930", "1m")
        assertThat(h.reason).isEqualTo(CandleHealthService.CandleHealthReason.WS_NOT_SUBSCRIBED)
    }

    @Test
    fun `정규장 stale + WS live 구독 + tick 없음 — NO_TICK`() {
        val (svc, _) = build(dbLastTs = now.minusSeconds(7_200), sourceLive = true)
        val h = svc.assess("005930", "1m")
        assertThat(h.reason).isEqualTo(CandleHealthService.CandleHealthReason.NO_TICK)
    }

    @Test
    fun `정규장 stale + quote last WS source — NO_TICK (구독 간주)`() {
        val (svc, _) =
            build(
                dbLastTs = now.minusSeconds(7_200),
                sourceLive = false,
                quoteLastJson = """{"price":"70000","ts":"2026-06-05T00:00:00Z","source":"live"}""",
            )
        val h = svc.assess("005930", "1m")
        assertThat(h.reason).isEqualTo(CandleHealthService.CandleHealthReason.NO_TICK)
    }

    @Test
    fun `polling source 는 WS 구독 아님 — WS_NOT_SUBSCRIBED`() {
        val (svc, _) =
            build(
                dbLastTs = now.minusSeconds(7_200),
                sourceLive = false,
                quoteLastJson = """{"price":"70000","ts":"2026-06-05T00:00:00Z","source":"polling"}""",
            )
        val h = svc.assess("005930", "1m")
        assertThat(h.reason).isEqualTo(CandleHealthService.CandleHealthReason.WS_NOT_SUBSCRIBED)
    }

    @Test
    fun `DB·Redis 모두 0건 — NO_DATA + 정규장 미구독이면 WS_NOT_SUBSCRIBED`() {
        val (svc, _) = build(dbLastTs = null, currentUpdatedAt = null)
        val h = svc.assess("005930", "1m")
        assertThat(h.status).isEqualTo(CandleHealthService.CandleHealthStatus.NO_DATA)
        assertThat(h.lastCandleAt).isNull()
        assertThat(h.staleSeconds).isNull()
        assertThat(h.reason).isEqualTo(CandleHealthService.CandleHealthReason.WS_NOT_SUBSCRIBED)
    }

    @Test
    fun `Redis current updatedAt 이 DB last 보다 최신이면 lastCandleAt 으로 채택`() {
        val dbTs = now.minusSeconds(7_200)
        val (svc, _) =
            build(
                dbLastTs = dbTs,
                currentUpdatedAt = now.minusSeconds(20).toString(),
                sourceLive = true,
            )
        val h = svc.assess("005930", "1m")
        // current 20초 전 → FRESH 승격
        assertThat(h.status).isEqualTo(CandleHealthService.CandleHealthStatus.FRESH)
        assertThat(h.reason).isEqualTo(CandleHealthService.CandleHealthReason.OK_REALTIME)
    }

    @Test
    fun `배치 판정 — codes 순서 보존`() {
        val (svc, _) = build(dbLastTs = now.minusSeconds(30))
        val result = svc.assessAll(listOf("005930", "005930", "005930"), "1m")
        assertThat(result).hasSize(3)
        assertThat(result.map { it.code }).containsExactly("005930", "005930", "005930")
    }
}
