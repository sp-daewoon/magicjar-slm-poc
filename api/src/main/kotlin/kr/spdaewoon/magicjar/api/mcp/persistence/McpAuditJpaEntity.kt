package kr.spdaewoon.magicjar.api.mcp.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * mcp_audit 표 JPA entity — Flyway V52 정합 (ADR 0027 §2-5).
 *
 * - `params` JSONB column ([SqlTypes.JSON]) — null safe + secret 평문 금지 (feedback_secret_handling 정합)
 * - `errorMessage` 한국어 message LOCK — stacktrace 외부 노출 차단
 *
 * 본 entity 는 [McpAuditJpaRepository] 단독 접근 — 다른 영역 import 금지 (mcp 패키지 캡슐화).
 */
@Entity
@Table(name = "mcp_audit")
class McpAuditJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 64)
    var userId: String = "",
    @Column(name = "client_id", length = 64)
    var clientId: String? = null,
    @Column(name = "tool", nullable = false, length = 64)
    var tool: String = "",
    @Column(name = "params", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    var params: Map<String, Any?>? = null,
    @Column(name = "response_size_bytes")
    var responseSizeBytes: Int? = null,
    @Column(name = "latency_ms")
    var latencyMs: Int? = null,
    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null,
    @Column(name = "called_at", nullable = false)
    var calledAt: Instant = Instant.EPOCH,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
