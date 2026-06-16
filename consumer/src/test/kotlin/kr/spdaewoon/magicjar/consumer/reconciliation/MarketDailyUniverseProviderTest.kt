package kr.spdaewoon.magicjar.consumer.reconciliation

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMarketDailyUniverseJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MarketDailyUniverseProvider — 보통주 코드 후처리 + graceful 단위테스트 (ADR 0118 R1).
 *
 * 보통주 필터(securities_group='ST' + 우선주·SPAC 제외)는 native @Query(repository) 책임이므로,
 * 본 단위테스트는 provider의 후처리(6자리 방어·distinct·DB 실패 graceful·count 위임)를 검증한다.
 * 실제 보통주 ~3,000 산출은 dev,real-kis DB 대상 실측 카운트로 evidence 인용(PR 본문).
 *
 * direction-guard LOCK: provider는 WS slot·watchlist·SubscriptionRouter 의존 0 (구독 universe 아님).
 */
class MarketDailyUniverseProviderTest {
    @Test
    fun `resolveCodes - 6자리 코드만 통과 + distinct (2차 방어)`() {
        val repository = mockk<ConsumerMarketDailyUniverseJpaRepository>()
        every { repository.findMarketDailyCommonStockCodes() } returns
            listOf("005930", " 000660 ", "005930", "12", "", "abcdef", "035720")
        val provider = MarketDailyUniverseProvider(repository)

        val codes = provider.resolveCodes()

        assertThat(codes).containsExactly("005930", "000660", "035720")
    }

    @Test
    fun `resolveCodes - DB 조회 실패 시 빈 결과 (mock 합성 0 — 데이터 없음 path)`() {
        val repository = mockk<ConsumerMarketDailyUniverseJpaRepository>()
        every { repository.findMarketDailyCommonStockCodes() } throws RuntimeException("DB down")
        val provider = MarketDailyUniverseProvider(repository)

        assertThat(provider.resolveCodes()).isEmpty()
    }

    @Test
    fun `count - repository 위임 + 실패 시 0`() {
        val ok = mockk<ConsumerMarketDailyUniverseJpaRepository>()
        every { ok.countMarketDailyCommonStock() } returns 3012L
        assertThat(MarketDailyUniverseProvider(ok).count()).isEqualTo(3012L)

        val fail = mockk<ConsumerMarketDailyUniverseJpaRepository>()
        every { fail.countMarketDailyCommonStock() } throws RuntimeException("DB down")
        assertThat(MarketDailyUniverseProvider(fail).count()).isEqualTo(0L)
    }
}
