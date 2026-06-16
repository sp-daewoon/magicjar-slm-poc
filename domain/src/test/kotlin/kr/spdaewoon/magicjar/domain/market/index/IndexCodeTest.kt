package kr.spdaewoon.magicjar.domain.market.index

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * IndexCode 카탈로그 + invariant 테스트 (ADR 0100 §2-3 LOCK 정합).
 *
 * 본 테스트는 30+ KIS endpoint 매트릭스의 raw 정합 + require 5건 보호.
 */
class IndexCodeTest {
    @Test
    fun `KOSPI KOSDAQ KOSPI200 raw code는 KIS 공식 정합 (0001 1001 2001)`() {
        IndexCode.KOSPI.code shouldBe "0001"
        IndexCode.KOSDAQ.code shouldBe "1001"
        IndexCode.KOSPI200.code shouldBe "2001"
        IndexCode.KOSPI.category shouldBe IndexCategory.DOMESTIC_INDEX
    }

    @Test
    fun `해외 NASDAQ SP500 raw code는 점 prefix + NAS exchange 의무 (ADR 0100 §3-4)`() {
        IndexCode.NASDAQ.code shouldBe ".IXIC"
        IndexCode.NASDAQ.exchange shouldBe "NAS"
        IndexCode.SP500.code shouldBe ".SPX"
        IndexCode.SP500.exchange shouldBe "NAS"
        IndexCode.NASDAQ.category shouldBe IndexCategory.OVERSEAS_INDEX
    }

    @Test
    fun `KOSPI 21 섹터 카탈로그는 LOCK (size 21 + 한국어명 정합)`() {
        IndexCode.KOSPI_SECTORS shouldHaveSize 21
        // 대표 sample 정합 검증
        IndexCode.KOSPI_SECTORS.first { it.code == "0010" }.displayName shouldBe "전기전자"
        IndexCode.KOSPI_SECTORS.first { it.code == "0018" }.displayName shouldBe "금융업"
        // 모든 섹터는 DOMESTIC_SECTOR
        IndexCode.KOSPI_SECTORS.all { it.category == IndexCategory.DOMESTIC_SECTOR } shouldBe true
    }

    @Test
    fun `ALL_FOR_MINUTE_SYNC는 26 endpoint LOCK (3 지수 + 21 섹터 + 2 해외) — ADR 0100 §2-4 정합`() {
        // 26 req/min = 0.43 req/s = 4.3% 한도 점유 안전 검증 (ADR 0100 §6-1 S-4)
        IndexCode.ALL_FOR_MINUTE_SYNC shouldHaveSize 26
    }

    @Test
    fun `DOMESTIC_FOR_DAILY는 24건 (3 지수 + 21 섹터)`() {
        IndexCode.DOMESTIC_FOR_DAILY shouldHaveSize 24
        IndexCode.DOMESTIC_FOR_DAILY.all { it.category != IndexCategory.OVERSEAS_INDEX } shouldBe true
    }

    @Test
    fun `OVERSEAS_FOR_DAILY는 2건 (NASDAQ + SP500)`() {
        IndexCode.OVERSEAS_FOR_DAILY shouldContainExactlyInAnyOrder listOf(IndexCode.NASDAQ, IndexCode.SP500)
    }

    @Test
    fun `code 빈 문자열 require 위반`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                IndexCode("", "테스트", IndexCategory.DOMESTIC_INDEX)
            }
        ex.message!! shouldContain "코드"
    }

    @Test
    fun `해외 지수 exchange null require 위반`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                IndexCode(".TEST", "테스트", IndexCategory.OVERSEAS_INDEX, exchange = null)
            }
        ex.message!! shouldContain "EXCD"
    }
}
