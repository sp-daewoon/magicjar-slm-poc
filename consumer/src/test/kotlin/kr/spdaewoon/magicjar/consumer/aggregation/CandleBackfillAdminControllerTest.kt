package kr.spdaewoon.magicjar.consumer.aggregation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * CandleBackfillAdminController 단위 테스트.
 *
 * 2026-06-05 readiness P0-1 — admin도 BackfillThrottleGate를 통과(runBackfillThrottled). 운영자 우회 폐기.
 */
class CandleBackfillAdminControllerTest {
    private val worker = mockk<CandleBackfillWorker>()
    private val controller = CandleBackfillAdminController(worker)

    @Test
    fun `admin throttle 통과 — dry-run true 경로 응답 LOCK (dryRun true + status SUCCESS + 3분류)`() {
        every {
            worker.runBackfillThrottled(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                dryRun = true,
                windowDays = 30,
                reason = "admin",
            )
        } returns
            CandleBackfillWorker.BackfillResult(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                backfillBatchId = "candle-backfill:004090:1d:1700000000000-uuid",
                dryRun = true,
                gapCount = 5,
                gapsSample = listOf(Instant.ofEpochSecond(1_700_000_000L)),
                insertedCount = 0,
                uncoveredCount = 0,
                failedGapsCount = 0,
                durationMs = 12L,
                status = CandleBackfillWorker.BackfillStatus.SUCCESS,
            )

        val response = controller.trigger("004090", "1d", dryRun = true, windowDays = 30)

        assertThat(response.statusCode.is2xxSuccessful).isTrue
        val body = response.body!!
        assertThat(body.dryRun).isTrue
        assertThat(body.status).isEqualTo("SUCCESS")
        assertThat(body.gapCount).isEqualTo(5)
        assertThat(body.insertedCount).isEqualTo(0)
        assertThat(body.queued).isFalse
        assertThat(body.backfillBatchId).startsWith("candle-backfill:")
        // admin도 throttle gate 통과 path(runBackfillThrottled) 위임 LOCK — 운영자 우회 폐기
        verify(exactly = 1) {
            worker.runBackfillThrottled(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                dryRun = true,
                windowDays = 30,
                reason = "admin",
            )
        }
    }

    @Test
    fun `(P0-1) throttle permit timeout 또는 circuit OPEN — 202 THROTTLED + queued true`() {
        every {
            worker.runBackfillThrottled(
                code = "004090",
                interval = CandleInterval.ONE_MIN,
                dryRun = false,
                windowDays = 30,
                reason = "admin",
            )
        } returns null // throttle/circuit skip

        val response = controller.trigger("004090", "1m", dryRun = false, windowDays = 30)

        assertThat(response.statusCode.value()).isEqualTo(202)
        val body = response.body!!
        assertThat(body.status).isEqualTo("THROTTLED")
        assertThat(body.queued).isTrue
        assertThat(body.error).contains("circuit")
    }

    @Test
    fun `(P0-2) SUCCESS + insertedCount 0 + uncoveredCount 명시 — 3분류 응답`() {
        every {
            worker.runBackfillThrottled(
                code = "004090",
                interval = CandleInterval.ONE_MIN,
                dryRun = false,
                windowDays = 30,
                reason = "admin",
            )
        } returns
            CandleBackfillWorker.BackfillResult(
                code = "004090",
                interval = CandleInterval.ONE_MIN,
                backfillBatchId = "candle-backfill:004090:1m:1700000000000-uuid",
                dryRun = false,
                gapCount = 10,
                gapsSample = emptyList(),
                insertedCount = 0,
                uncoveredCount = 10, // 전부 거래 없는 분 — SUCCESS인데 inserted=0 설명됨
                failedGapsCount = 0,
                durationMs = 30L,
                status = CandleBackfillWorker.BackfillStatus.SUCCESS,
            )

        val response = controller.trigger("004090", "1m", dryRun = false, windowDays = 30)

        assertThat(response.statusCode.is2xxSuccessful).isTrue
        val body = response.body!!
        assertThat(body.status).isEqualTo("SUCCESS")
        assertThat(body.insertedCount).isEqualTo(0)
        assertThat(body.uncoveredCount).isEqualTo(10)
        assertThat(body.failedGapsCount).isEqualTo(0)
    }

    @Test
    fun `interval 미지원 (5m) — 400 BAD_REQUEST + error message`() {
        val response = controller.trigger("004090", "5m", dryRun = false, windowDays = 30)

        assertThat(response.statusCode.is4xxClientError).isTrue
        assertThat(response.body!!.status).isEqualTo("BAD_REQUEST")
        assertThat(response.body!!.error).contains("Phase 2-A")
    }

    @Test
    fun `unknown interval — 400 BAD_REQUEST`() {
        val response = controller.trigger("004090", "unknown", dryRun = false, windowDays = 30)

        assertThat(response.statusCode.is4xxClientError).isTrue
        assertThat(response.body!!.error).contains("Unknown interval")
    }

    @Test
    fun `windowDays out of range — 400 BAD_REQUEST`() {
        val response = controller.trigger("004090", "1d", dryRun = false, windowDays = 1000)

        assertThat(response.statusCode.is4xxClientError).isTrue
        assertThat(response.body!!.error).contains("windowDays out of range")
    }

    @Test
    fun `code blank — 400 BAD_REQUEST`() {
        val response = controller.trigger("", "1d", dryRun = false, windowDays = 30)

        assertThat(response.statusCode.is4xxClientError).isTrue
        assertThat(response.body!!.error).contains("Invalid code")
    }
}
