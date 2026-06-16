package kr.spdaewoon.magicjar.domain.trend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * TrendPreference invariant 5건 edge case 단위 테스트 (ADR 0101 §2-1-A).
 *
 * 의무 6 케이스:
 * 1. maPeriods empty
 * 2. maPeriods size > 5
 * 3. period out of 1..252
 * 4. sorted ASC 위반 (duplicates · descending)
 * 5. rsPeriod out of range
 * 6. crossPair invariant (short ≥ long · maPeriods 외 값)
 */
class TrendPreferenceTest {
    private val now: Instant = Instant.parse("2026-05-24T10:00:00Z")
    private val userId = "12345678"

    @Test
    fun `empty maPeriods 면 require 위반`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                TrendPreference(userId = userId, maPeriods = emptyList(), updatedAt = now)
            }
        ex.message!! shouldContain "1개 이상"
    }

    @Test
    fun `maPeriods size 6 이상 면 require 위반`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                TrendPreference(userId = userId, maPeriods = listOf(1, 2, 3, 4, 5, 6), updatedAt = now)
            }
        ex.message!! shouldContain "5개 초과"
    }

    @Test
    fun `period 0 or 253 면 require 위반`() {
        shouldThrow<IllegalArgumentException> {
            TrendPreference(userId = userId, maPeriods = listOf(0, 5, 10), updatedAt = now)
        }
        shouldThrow<IllegalArgumentException> {
            TrendPreference(userId = userId, maPeriods = listOf(5, 10, 253), updatedAt = now)
        }
    }

    @Test
    fun `maPeriods sorted ASC 위반 또는 중복이면 require 위반`() {
        // descending
        shouldThrow<IllegalArgumentException> {
            TrendPreference(userId = userId, maPeriods = listOf(60, 20, 5), updatedAt = now)
        }
        // duplicates
        val ex =
            shouldThrow<IllegalArgumentException> {
                TrendPreference(userId = userId, maPeriods = listOf(5, 5, 20), updatedAt = now)
            }
        ex.message!! shouldContain "오름차순"
    }

    @Test
    fun `rsPeriod out of 1 to 252 면 require 위반`() {
        shouldThrow<IllegalArgumentException> {
            TrendPreference(userId = userId, maPeriods = listOf(5, 20), rsPeriod = 0, updatedAt = now)
        }
        shouldThrow<IllegalArgumentException> {
            TrendPreference(userId = userId, maPeriods = listOf(5, 20), rsPeriod = 253, updatedAt = now)
        }
    }

    @Test
    fun `crossPair short greater equal than long 면 require 위반`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                TrendPreference(
                    userId = userId,
                    maPeriods = listOf(5, 20, 60),
                    crossPair = 60 to 5,
                    updatedAt = now,
                )
            }
        ex.message!! shouldContain "short < long"
    }

    @Test
    fun `crossPair maPeriods 외 값 면 require 위반`() {
        val ex =
            shouldThrow<IllegalArgumentException> {
                TrendPreference(
                    userId = userId,
                    maPeriods = listOf(5, 20, 60),
                    crossPair = 3 to 10,
                    updatedAt = now,
                )
            }
        ex.message!! shouldContain "maPeriods 내"
    }

    @Test
    fun `default forUser 는 isDefault true`() {
        val pref = TrendPreferenceDefaults.forUser(userId, now)
        pref.isDefault() shouldBe true
        pref.maPeriods shouldBe listOf(5, 20, 60)
        pref.rsPeriod shouldBe 60
        pref.shortMa() shouldBe 5
        pref.longMa() shouldBe 60
    }

    @Test
    fun `crossPair 명시 시 shortMa longMa 본 값 반환`() {
        val pref =
            TrendPreference(
                userId = userId,
                maPeriods = listOf(5, 20, 60),
                crossPair = 20 to 60,
                updatedAt = now,
            )
        pref.shortMa() shouldBe 20
        pref.longMa() shouldBe 60
        pref.isDefault() shouldBe false
    }

    @Test
    fun `valid all argument 는 invariant 통과`() {
        val pref =
            TrendPreference(
                userId = userId,
                maPeriods = listOf(5, 20, 60),
                rsPeriod = 60,
                crossPair = null,
                updatedAt = now,
            )
        pref.maPeriods shouldBe listOf(5, 20, 60)
        pref.rsPeriod shouldBe 60
        pref.isDefault() shouldBe true
    }
}
