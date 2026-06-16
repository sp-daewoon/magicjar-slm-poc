package kr.spdaewoon.magicjar.api.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.mcp.persistence.McpAuditJpaEntity
import kr.spdaewoon.magicjar.api.mcp.persistence.McpAuditJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * McpAuditService — INSERT side effect + response size 측정 + 한국어 error message 보존.
 *
 * 4 lens 영향도 — REQUIRES_NEW 트랜잭션 (test 환경에서 propagation 검증은 @Transactional 통합 테스트 별도 path).
 */
class McpAuditServiceTest {
    private val repo: McpAuditJpaRepository =
        mock {
            whenever(it.save(any<McpAuditJpaEntity>())).thenAnswer { it.arguments[0] }
        }
    private val mapper = ObjectMapper()
    private val service = McpAuditService(repo, mapper)

    @Test
    fun `recordSuccess - response size 계측 + params sanitize`() {
        val params = mapOf("code" to "005930", "interval" to "1d")
        val response = mapOf("data" to listOf(mapOf("ts" to "2026-05-24T00:00:00Z", "close" to "70000")))
        val now = Instant.parse("2026-05-24T10:00:00Z")

        service.recordSuccess(
            userId = "user-1",
            clientId = "default-client",
            tool = "get_candles",
            params = params,
            response = response,
            latencyMs = 123,
            calledAt = now,
        )

        val captor = argumentCaptor<McpAuditJpaEntity>()
        verify(repo).save(captor.capture())
        val saved = captor.firstValue
        assertThat(saved.userId).isEqualTo("user-1")
        assertThat(saved.clientId).isEqualTo("default-client")
        assertThat(saved.tool).isEqualTo("get_candles")
        assertThat(saved.params).isNotNull
        assertThat(saved.params!!["code"]).isEqualTo("005930")
        assertThat(saved.responseSizeBytes).isGreaterThan(0)
        assertThat(saved.latencyMs).isEqualTo(123)
        assertThat(saved.errorMessage).isNull()
        assertThat(saved.calledAt).isEqualTo(now)
    }

    @Test
    fun `recordError - error message 보존 + 길이 cap`() {
        val params = mapOf("scope" to "index")
        val longError = "a".repeat(3000)

        service.recordError(
            userId = "user-1",
            clientId = null,
            tool = "get_market_trend",
            params = params,
            errorMessage = longError,
            latencyMs = 50,
            calledAt = Instant.parse("2026-05-24T10:00:00Z"),
        )

        val captor = argumentCaptor<McpAuditJpaEntity>()
        verify(repo).save(captor.capture())
        val saved = captor.firstValue
        assertThat(saved.errorMessage!!.length).isEqualTo(2000) // MAX_ERROR_MESSAGE_LEN
        assertThat(saved.responseSizeBytes).isNull()
    }

    @Test
    fun `repository 실패 시 graceful — 본체에 예외 전파 0`() {
        val failingRepo: McpAuditJpaRepository = mock()
        whenever(failingRepo.save(any<McpAuditJpaEntity>())).thenThrow(RuntimeException("DB down"))
        val failingService = McpAuditService(failingRepo, mapper)

        // 예외 0건 — runCatching 으로 흡수
        failingService.recordSuccess("user-1", null, "tool", emptyMap(), emptyMap(), 10)
        failingService.recordError("user-1", null, "tool", emptyMap(), "err", 10)
    }
}
