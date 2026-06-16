package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.service.InstrumentSearchService
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentSearchResult
import kr.spdaewoon.magicjar.domain.instrument.port.InstrumentRepository
import kr.spdaewoon.magicjar.domain.instrument.port.InstrumentSearchIndex
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * InstrumentSearchService smoke test (T-IM1 spec §I — 룰 4 정합).
 *
 * 검증 1: Redis 히트 시 DB fallback 미호출
 * 검증 2: Redis miss(0건) → DB fallback 호출
 */
class InstrumentSearchServiceTest {
    private val searchIndex: InstrumentSearchIndex = mock()
    private val instrumentRepository: InstrumentRepository = mock()
    private val service = InstrumentSearchService(searchIndex, instrumentRepository)

    private val samsungResult =
        InstrumentSearchResult(
            code = "005930",
            name = "삼성전자",
            market = InstrumentMarket.KOSPI,
            sector = "전기전자",
            score = 1.0,
        )

    @Test
    fun `Redis 히트 시 DB fallback 미호출`() {
        // given: Redis에 검색 결과 있음
        whenever(searchIndex.search(any(), any(), anyOrNull())).thenReturn(listOf(samsungResult))

        // when
        val results = service.search("삼성전", limit = 10, market = null)

        // then
        assertThat(results).hasSize(1)
        assertThat(results[0].code).isEqualTo("005930")
        assertThat(results[0].name).isEqualTo("삼성전자")
        // DB fallback 미호출 확인
        verify(instrumentRepository, never()).searchByPgTrgm(any(), any(), anyOrNull())
    }

    @Test
    fun `Redis miss(0건) 시 DB fallback 호출`() {
        // given: Redis 결과 없음
        whenever(searchIndex.search(any(), any(), anyOrNull())).thenReturn(emptyList())
        // DB fallback 결과
        whenever(instrumentRepository.searchByPgTrgm(any(), any(), anyOrNull())).thenReturn(
            listOf(samsungResult),
        )

        // when
        val results = service.search("삼성전", limit = 10, market = null)

        // then
        assertThat(results).hasSize(1)
        assertThat(results[0].code).isEqualTo("005930")
        // DB fallback 호출 확인
        verify(instrumentRepository).searchByPgTrgm(any(), any(), anyOrNull())
    }
}
