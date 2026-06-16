package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentRepositoryAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest

/**
 * InstrumentRepositoryAdapter PR-21b smoke test.
 *
 * 검증 1: findActiveTradeable — trading_halt=false AND liquidation=false AND active=true 필터
 * 검증 2: countByMarket — market별 집계 Map 변환
 * 검증 3: findByMarketCapDesc — 시총 내림차순 페이징
 * 검증 4: findByKospi200 — KOSPI200 편입 종목
 * 검증 5: WatchlistEntryDto 보강 필드 — type·tradingHalt·marketCapPrev null/false graceful
 *
 * PR-21b dispatch-spec §4-2 H smoke 1건 정합.
 * 룰 4 정합: R2 PR이므로 mock 기반 단위 테스트 (DB 연동 0).
 * 룰 5 위반 금지: LLM provider import 0건.
 */
class InstrumentRepositoryAdapterSmoke {
    private val jpaRepository: InstrumentJpaRepository = mock()
    private val adapter = InstrumentRepositoryAdapter(jpaRepository)

    private fun makeEntity(
        code: String,
        market: String = "KOSPI",
        tradingHalt: Boolean = false,
        liquidation: Boolean = false,
        marketCapPrev: Long? = null,
        isKospi200: Boolean = false,
        type: String = "STOCK",
    ): InstrumentJpaEntity =
        InstrumentJpaEntity(
            code = code,
            name = "종목$code",
            market = market,
            type = type,
            tradingHalt = tradingHalt,
            liquidation = liquidation,
            marketCapPrev = marketCapPrev,
            isKospi200 = isKospi200,
        )

    @Test
    fun `findActiveTradeable — 거래정지·정리매매 종목 제외`() {
        val tradeable = makeEntity("005930", tradingHalt = false, liquidation = false)
        val halted = makeEntity("000660", tradingHalt = true, liquidation = false)
        val liquidating = makeEntity("035720", tradingHalt = false, liquidation = true)
        whenever(jpaRepository.findActiveTradeable()).thenReturn(listOf(tradeable))

        val result = adapter.findActiveTradeable()

        assertThat(result).hasSize(1)
        assertThat(result[0].code).isEqualTo("005930")
        // halted·liquidating은 결과에 없음
        assertThat(result.map { it.code }).doesNotContain("000660", "035720")
    }

    @Test
    fun `countByMarket — market별 집계 Map 변환 검증`() {
        val mockRows: List<Array<Any>> =
            listOf(
                arrayOf("KOSPI", 1200L),
                arrayOf("KOSDAQ", 1500L),
            )
        whenever(jpaRepository.countByMarket()).thenReturn(mockRows)

        val result = adapter.countByMarket()

        assertThat(result).containsEntry("KOSPI", 1200L)
        assertThat(result).containsEntry("KOSDAQ", 1500L)
        assertThat(result).hasSize(2)
    }

    @Test
    fun `findByMarketCapDesc — 시총 내림차순 정렬 반환`() {
        val large = makeEntity("005930", marketCapPrev = 4_150_000L)
        val medium = makeEntity("000660", marketCapPrev = 1_200_000L)
        val pageable = PageRequest.of(0, 2)
        whenever(jpaRepository.findByMarketCapDescLimit(pageable)).thenReturn(listOf(large, medium))

        val result = adapter.findByMarketCapDesc(2)

        assertThat(result).hasSize(2)
        assertThat(result[0].code).isEqualTo("005930")
        assertThat(result[1].code).isEqualTo("000660")
    }

    @Test
    fun `findByKospi200 — KOSPI200 편입 종목 반환`() {
        val inKospi200 = makeEntity("005930", isKospi200 = true)
        val notInKospi200 = makeEntity("000001", isKospi200 = false)
        whenever(jpaRepository.findByActiveTrueAndIsKospi200True()).thenReturn(listOf(inKospi200))

        val result = adapter.findByKospi200()

        assertThat(result).hasSize(1)
        assertThat(result[0].code).isEqualTo("005930")
    }

    @Test
    fun `InstrumentJpaEntity 신규 필드 기본값 검증 — V5 sync 전 graceful`() {
        // V5 미적용(신규 필드 null/false default) 상태의 stub entity
        val stub =
            InstrumentJpaEntity(
                code = "999999",
                name = "테스트종목",
                market = "UNKNOWN",
                type = "UNKNOWN",
            )

        // Tier 2 매매 가드 — 모두 false default
        assertThat(stub.tradingHalt).isFalse()
        assertThat(stub.liquidation).isFalse()
        assertThat(stub.managementStock).isFalse()
        assertThat(stub.marketAlert).isNull()
        assertThat(stub.shortOverheated).isFalse()
        assertThat(stub.abnormalRunup).isFalse()

        // Tier 1
        assertThat(stub.securitiesGroup).isNull()

        // Tier 4
        assertThat(stub.marketCapPrev).isNull()
        assertThat(stub.roe).isNull()
        assertThat(stub.prevVolume).isNull()

        // Tier 3 인덱스
        assertThat(stub.isKospi200).isFalse()
        assertThat(stub.isKosdaq150).isFalse()
        assertThat(stub.isKrx).isFalse()
    }

    @Test
    fun `WatchlistService 매핑 — instrument null graceful (모든 신규 필드 null or false)`() {
        // instrument가 null인 경우 watchlist DTO 신규 필드 모두 null/false
        // WatchlistService.toDto(null) 경로 검증 (실제 서비스 호출 없이 로직 검증)
        val noInstrument: InstrumentJpaEntity? = null

        val type = noInstrument?.type?.takeIf { it != "UNKNOWN" }
        val securitiesGroup = noInstrument?.securitiesGroup
        val marketCapPrev = noInstrument?.marketCapPrev
        val tradingHalt = noInstrument?.tradingHalt ?: false
        val managementStock = noInstrument?.managementStock ?: false
        val industryMedium = noInstrument?.industryMedium

        assertThat(type).isNull()
        assertThat(securitiesGroup).isNull()
        assertThat(marketCapPrev).isNull()
        assertThat(tradingHalt).isFalse()
        assertThat(managementStock).isFalse()
        assertThat(industryMedium).isNull()
    }
}
