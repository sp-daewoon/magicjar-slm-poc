package kr.spdaewoon.magicjar.api.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.mcp.persistence.McpAuditJpaEntity
import kr.spdaewoon.magicjar.api.mcp.persistence.McpAuditJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * mcp_audit INSERT 단일 진입점 (ADR 0027 §2-5 정합).
 *
 * ## 호출 정책
 * - [McpController] 가 tool execute() 전·후 [recordSuccess] 또는 [recordError] 호출
 * - 본 service 실패가 tool 응답 실패로 전파되지 않도록 try-catch 로 graceful — audit 누락은 운영 log 만 (자본 path 안전성 우선)
 *
 * ## 4 lens 정합
 * - 세심함: secret 평문 0 (params 는 client 가 보낸 input 그대로 — API key 등 secret 은 별도 header 로 분리 LOCK)
 * - 영향도: REQUIRES_NEW 트랜잭션 — tool 본체 트랜잭션과 분리 (rollback 영향 0)
 * - 연결: [McpAuditJpaRepository] 단독 reuse · ObjectMapper 기존 Bean reuse
 * - 확장성: response_size_bytes 계측 path · client_id 1차 nullable · post-MVP per-client 권한 매핑 path
 */
@Service
class McpAuditService(
    private val repository: McpAuditJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(McpAuditService::class.java)

    /**
     * tool 호출 성공 audit.
     *
     * @param userId 호출 사용자 id
     * @param clientId API key 매핑 client (1차 nullable)
     * @param tool tool 이름 (예: get_candles)
     * @param params input map (null safe · secret 평문 금지)
     * @param response 응답 map — 직렬화 byte size 측정용
     * @param latencyMs tool execute 경과 ms
     * @param calledAt 호출 시점 (test fixture 주입 가능)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordSuccess(
        userId: String,
        clientId: String?,
        tool: String,
        params: Map<String, Any?>,
        response: Map<String, Any?>,
        latencyMs: Int,
        calledAt: Instant = Instant.now(),
    ) {
        runCatching {
            val responseSize = measureResponseSize(response)
            repository.save(
                McpAuditJpaEntity(
                    userId = userId,
                    clientId = clientId,
                    tool = tool,
                    params = sanitizeParams(params),
                    responseSizeBytes = responseSize,
                    latencyMs = latencyMs.coerceAtLeast(0),
                    errorMessage = null,
                    calledAt = calledAt,
                ),
            )
        }.onFailure { ex ->
            log.warn("[mcp.audit] success 기록 실패 — tool={} userId={} err={}", tool, userId, ex.message)
        }
    }

    /**
     * tool 호출 실패 audit.
     *
     * @param errorMessage 사용자 친화 한국어 message (stacktrace 0 LOCK)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordError(
        userId: String,
        clientId: String?,
        tool: String,
        params: Map<String, Any?>,
        errorMessage: String,
        latencyMs: Int,
        calledAt: Instant = Instant.now(),
    ) {
        runCatching {
            repository.save(
                McpAuditJpaEntity(
                    userId = userId,
                    clientId = clientId,
                    tool = tool,
                    params = sanitizeParams(params),
                    responseSizeBytes = null,
                    latencyMs = latencyMs.coerceAtLeast(0),
                    errorMessage = errorMessage.take(MAX_ERROR_MESSAGE_LEN),
                    calledAt = calledAt,
                ),
            )
        }.onFailure { ex ->
            log.warn("[mcp.audit] error 기록 실패 — tool={} userId={} err={}", tool, userId, ex.message)
        }
    }

    private fun measureResponseSize(response: Map<String, Any?>): Int? =
        runCatching { objectMapper.writeValueAsBytes(response).size }
            .onFailure { log.debug("[mcp.audit] response size 측정 실패: {}", it.message) }
            .getOrNull()

    /**
     * params 직렬화 가능 형태로 정규화 (secret 평문 차단 · BigDecimal 등 안전).
     *
     * 1차 — Jackson roundtrip 으로 정규화 (Map<String, Any?> 변환 보장).
     * 실패 시 fallback: `{ "raw": "<toString>" }`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun sanitizeParams(params: Map<String, Any?>): Map<String, Any?> =
        runCatching {
            val json = objectMapper.writeValueAsString(params)
            objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
        }.getOrElse { ex ->
            log.debug("[mcp.audit] params 정규화 실패: {}", ex.message)
            mapOf("raw" to params.toString().take(MAX_RAW_LEN))
        }

    private companion object {
        const val MAX_ERROR_MESSAGE_LEN = 2000
        const val MAX_RAW_LEN = 4000
    }
}
