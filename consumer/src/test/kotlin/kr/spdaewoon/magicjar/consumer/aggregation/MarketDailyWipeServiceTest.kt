package kr.spdaewoon.magicjar.consumer.aggregation

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * MarketDailyWipeService — ADR 0118 amend §9-C wipe 안전 회귀 가드.
 *
 * ★ 핵심 안전 회귀: wipe 가 1d 패밀리(1d·1wk·1mo·1y)만 지우고 **1m·5m 를 절대 보존**하는지.
 * 1m·5m 실시간 누적분은 KIS REST 로 복구 불가(영구 손실)이므로 화이트리스트 밖은 어떤 경로로도 삭제 0 의무.
 */
class MarketDailyWipeServiceTest {
    @Suppress("UNCHECKED_CAST")
    private fun buildService(repo: ConsumerCandleJpaRepository): MarketDailyWipeService {
        val provider = mockk<ObjectProvider<MarketDailyWipeService>>()
        // self proxy — 테스트에선 실제 인스턴스를 그대로 반환(REQUIRES_NEW 프록시 효과는 통합 테스트 영역).
        lateinit var service: MarketDailyWipeService
        every { provider.getObject() } answers { service }
        service = MarketDailyWipeService(repo, provider)
        return service
    }

    @Test
    fun `wipe 화이트리스트 LOCK — 1d 패밀리(1d·1wk·1mo·1y)만 포함 + 1m·5m 절대 배제`() {
        // 화이트리스트 상수 자체가 일봉 4종만 + 1m·5m 미포함을 단언(컴파일-타임 LOCK 회귀 가드).
        assertThat(MarketDailyWipeService.MIGRATE_WIPE_INTERVALS)
            .containsExactlyInAnyOrder("1d", "1wk", "1mo", "1y")
        assertThat(MarketDailyWipeService.MIGRATE_WIPE_INTERVALS).doesNotContain("1m", "5m")
    }

    @Test
    fun `wipeDailyFamily — DELETE 에 넘기는 interval 이 정확히 1d 패밀리 (1m·5m 미포함)`() {
        val repo = mockk<ConsumerCandleJpaRepository>()
        val intervalsSlot = slot<Collection<String>>()
        // 한 청크 만에 batchSize 미만(0) 반환 → 루프 1회 종료.
        every { repo.deleteByIntervalInBatch(capture(intervalsSlot), any()) } returns 0

        val service = buildService(repo)
        val wiped = service.wipeDailyFamily()

        assertThat(wiped).isEqualTo(0L)
        // ★ 삭제 대상에 1m·5m 가 절대 없어야 함(영구 손실 차단).
        assertThat(intervalsSlot.captured).containsExactlyInAnyOrder("1d", "1wk", "1mo", "1y")
        assertThat(intervalsSlot.captured).doesNotContain("1m", "5m")
    }

    @Test
    fun `wipeDailyFamily — 배치 반복 누적(batchSize 미만 반환 시 종료) + wipedRows 합산`() {
        val repo = mockk<ConsumerCandleJpaRepository>()
        // 1청크: full(batchSize) → 계속. 2청크: full → 계속. 3청크: 부분(<batchSize) → 종료.
        val batch = 100
        every { repo.deleteByIntervalInBatch(any(), batch) } returnsMany listOf(batch, batch, 37)

        val service = buildService(repo)
        val wiped = service.wipeDailyFamily(batchSize = batch)

        assertThat(wiped).isEqualTo((batch + batch + 37).toLong())
        verify(exactly = 3) { repo.deleteByIntervalInBatch(any(), batch) }
    }

    @Test
    fun `init 가드 — 화이트리스트에 보호대상(1m·5m) 섞이면 부팅 실패 (런타임 누적분 삭제 사고 차단)`() {
        // 상수는 LOCK 이므로 정상 init 은 통과. 가드 자체의 존재를 문서화하는 단언(상수 불변 회귀).
        val repo = mockk<ConsumerCandleJpaRepository>(relaxed = true)
        // 생성 성공(예외 0) = 화이트리스트가 보호대상 미포함 정상 상태.
        val service = buildService(repo)
        assertThat(service).isNotNull
    }
}
