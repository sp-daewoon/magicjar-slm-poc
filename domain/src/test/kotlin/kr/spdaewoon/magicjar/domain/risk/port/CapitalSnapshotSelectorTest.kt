package kr.spdaewoon.magicjar.domain.risk.port

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * ADR 0122 R1 — capital 분기 selector 계약 테스트 (spec §3-R1 "선택 분기 LOCK").
 *
 * R1 범위 = 포트 + 분기 시그니처. 실 listener 배선은 R2. 따라서 본 테스트는 selector 계약 수준
 * (페이퍼/비페이퍼 분기 + 실 잔고 오염 0 + fail-fast)만 검증한다.
 */
class CapitalSnapshotSelectorTest {
    // 실 CapitalSnapshotProvider는 일반 interface(R1 범위 밖이라 무변경) → 익명 object.
    private val real =
        object : CapitalSnapshotProvider {
            override fun getCapital(userId: String): BigDecimal = BigDecimal("20000000") // 실 KIS 잔고 2천만
        }

    // PaperCapitalSnapshotProvider는 fun interface → SAM 람다. 가상 자본 1천만(ADR 0122 §8-2).
    private val paper = PaperCapitalSnapshotProvider { BigDecimal("10000000") }

    @Test
    fun `비페이퍼 전략은 실 CapitalSnapshotProvider 잔고를 쓴다(기존 동작 동일)`() {
        val selector = CapitalSnapshotSelector.forStrategy(real, paper)

        // F10 — 반환 타입 CapitalOutcome. 비페이퍼는 realProvider.capitalOutcome 경로(default=Available(getCapital)).
        selector.capitalFor(userId = "u1", isPaper = false) shouldBe CapitalOutcome.Available(BigDecimal("20000000"))
    }

    @Test
    fun `페이퍼 전략은 가상 자본 provider를 쓴다 - 실 잔고 미호출(오염 0)`() {
        var realCalls = 0
        val trackedReal =
            object : CapitalSnapshotProvider {
                override fun getCapital(userId: String): BigDecimal {
                    realCalls++
                    return BigDecimal("20000000")
                }
            }
        val selector = CapitalSnapshotSelector.forStrategy(trackedReal, paper)

        selector.capitalFor(userId = "u1", isPaper = true) shouldBe CapitalOutcome.Available(BigDecimal("10000000"))
        // spec §1-3 격리 LOCK — 페이퍼 평가가 실 KIS 잔고 provider를 건드리지 않는다
        realCalls shouldBe 0
    }

    @Test
    fun `F10 — 비페이퍼 실 provider가 조회 불가면 Unavailable 을 그대로 전달한다(0 fabricate 금지)`() {
        val unavailableReal =
            object : CapitalSnapshotProvider {
                override fun getCapital(userId: String): BigDecimal = BigDecimal.ZERO

                override fun capitalOutcome(userId: String): CapitalOutcome = CapitalOutcome.Unavailable("KIS_RATE_LIMIT")
            }
        val selector = CapitalSnapshotSelector.forStrategy(unavailableReal, paper)

        selector.capitalFor(userId = "u1", isPaper = false) shouldBe CapitalOutcome.Unavailable("KIS_RATE_LIMIT")
    }

    @Test
    fun `페이퍼 provider 미배선 + 페이퍼 요청이면 fail-fast(실 폴백 금지 - 헌법 §11)`() {
        val selector = CapitalSnapshotSelector.forStrategy(realProvider = real, paperProvider = null)

        val ex =
            shouldThrow<PaperCapitalProviderUnavailableException> {
                selector.capitalFor(userId = "u9", isPaper = true)
            }
        ex.message shouldContain "u9"
        ex.message shouldContain "폴백 금지"
    }

    @Test
    fun `페이퍼 provider 미배선이어도 비페이퍼 경로는 정상(페이퍼 비활성 환경 zero-regression)`() {
        val selector = CapitalSnapshotSelector.forStrategy(realProvider = real, paperProvider = null)

        selector.capitalFor(userId = "u1", isPaper = false) shouldBe CapitalOutcome.Available(BigDecimal("20000000"))
    }

    @Test
    fun `PaperCapitalSnapshotProvider는 CapitalSnapshotProvider와 동일 시그니처지만 별 빈 계약이다`() {
        // 별 빈 — 페이퍼는 가상 자본만 노출(실 KIS 잔고 미참조). spec §3-R1 계약 2 LOCK.
        paper.getCapital("u1") shouldBe BigDecimal("10000000")
        real.getCapital("u1") shouldBe BigDecimal("20000000")
    }
}
