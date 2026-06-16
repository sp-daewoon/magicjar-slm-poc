package kr.spdaewoon.magicjar.domain.market.index

import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * MarketIndexSnapshot · MarketIndexDaily · SectorIndexSnapshot invariant 테스트.
 *
 * require 모두 정합 검증 (mock 합성 0 LOCK + 음수 가격 차단).
 */
class MarketIndexSnapshotTest {
    @Test
    fun `MarketIndexSnapshot 음수 가격 require 위반`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                MarketIndexSnapshot(
                    code = "0001",
                    category = IndexCategory.DOMESTIC_INDEX,
                    ts = Instant.now(),
                    price = BigDecimal("-1.0"),
                )
            }
        ex.message!! shouldContain "음수"
    }

    @Test
    fun `MarketIndexSnapshot 정상 생성 — 모든 필드 정합`() {
        shouldNotThrow<Throwable> {
            MarketIndexSnapshot(
                code = "0001",
                category = IndexCategory.DOMESTIC_INDEX,
                ts = Instant.now(),
                price = BigDecimal("2640.50"),
                changeRate = BigDecimal("0.45"),
                volume = 123456L,
                rawPayload = """{"raw":"json"}""",
            )
        }
    }

    @Test
    fun `MarketIndexDaily high less than low require 위반`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                MarketIndexDaily(
                    code = "0001",
                    category = IndexCategory.DOMESTIC_INDEX,
                    tradeDate = LocalDate.now(),
                    open = BigDecimal("100"),
                    high = BigDecimal("90"),
                    low = BigDecimal("95"),
                    close = BigDecimal("98"),
                )
            }
        ex.message!! shouldContain "high"
    }

    @Test
    fun `SectorIndexSnapshot 거래대금 음수 require 위반`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                SectorIndexSnapshot(
                    sectorCode = "0018",
                    sectorName = "금융업",
                    ts = Instant.now(),
                    price = BigDecimal("500"),
                    changeRate = BigDecimal("1.2"),
                    tradeValue = BigDecimal("-100"),
                )
            }
        ex.message!! shouldContain "거래대금"
    }
}
