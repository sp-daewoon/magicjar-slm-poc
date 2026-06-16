package kr.spdaewoon.magicjar.consumer.infrastructure.session

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SimpleMarketSessionResolver 단위 테스트.
 *
 * PR-9b smoke test 2 (dispatch §3-2-I 정합 — MarketSessionResolver 시간 기반 stub).
 * ADR 0048 §2-5: Regular·PreMarket·PostMarket·Closed session 판별 검증.
 *
 * 공휴일 처리: KIS API에서 휴장일 조회 endpoint 미확인 → 1차는 평일/주말만.
 * (T-MS1에서 공휴일 출처 결정 후 보강 필요 — 사용자 알림 사항)
 */
class SimpleMarketSessionResolverTest {
    private val resolver = SimpleMarketSessionResolver()
    private val kst = TimeZone.of("Asia/Seoul")

    @Test
    fun `평일 09시00분 → Regular`() {
        val instant = LocalDateTime(2026, 5, 7, 9, 0, 0).toInstant(kst) // 목요일
        val session = resolver.resolve(instant)
        assertThat(session).isInstanceOf(MarketSession.Regular::class.java)
    }

    @Test
    fun `평일 15시29분 → Regular`() {
        val instant = LocalDateTime(2026, 5, 7, 15, 29, 0).toInstant(kst)
        val session = resolver.resolve(instant)
        assertThat(session).isInstanceOf(MarketSession.Regular::class.java)
    }

    @Test
    fun `평일 15시30분 → Closed (장 마감)`() {
        // 15:30 = timeInt 1530 — Regular 범위 900..1529 벗어남
        val instant = LocalDateTime(2026, 5, 7, 15, 30, 0).toInstant(kst)
        val session = resolver.resolve(instant)
        // 15:30 이후 15:40 전은 Closed (gap)
        assertThat(session).isInstanceOf(MarketSession.Closed::class.java)
    }

    @Test
    fun `평일 08시35분 → PreMarket`() {
        val instant = LocalDateTime(2026, 5, 7, 8, 35, 0).toInstant(kst)
        val session = resolver.resolve(instant)
        assertThat(session).isInstanceOf(MarketSession.PreMarket::class.java)
    }

    @Test
    fun `평일 15시45분 → PostMarket`() {
        val instant = LocalDateTime(2026, 5, 7, 15, 45, 0).toInstant(kst)
        val session = resolver.resolve(instant)
        assertThat(session).isInstanceOf(MarketSession.PostMarket::class.java)
    }

    @Test
    fun `토요일 → Closed`() {
        val instant = LocalDateTime(2026, 5, 9, 10, 0, 0).toInstant(kst) // 토요일
        val session = resolver.resolve(instant)
        assertThat(session).isInstanceOf(MarketSession.Closed::class.java)
        val closed = session as MarketSession.Closed
        assertThat(closed.reason).contains("주말")
    }

    @Test
    fun `일요일 → Closed`() {
        val instant = LocalDateTime(2026, 5, 10, 10, 0, 0).toInstant(kst) // 일요일
        val session = resolver.resolve(instant)
        assertThat(session).isInstanceOf(MarketSession.Closed::class.java)
    }

    @Test
    fun `isOpen — 평일 10시 → true`() {
        val instant = LocalDateTime(2026, 5, 7, 10, 0, 0).toInstant(kst)
        assertThat(resolver.isOpen(instant)).isTrue
    }

    @Test
    fun `isOpen — 토요일 → false`() {
        val instant = LocalDateTime(2026, 5, 9, 10, 0, 0).toInstant(kst)
        assertThat(resolver.isOpen(instant)).isFalse
    }
}
