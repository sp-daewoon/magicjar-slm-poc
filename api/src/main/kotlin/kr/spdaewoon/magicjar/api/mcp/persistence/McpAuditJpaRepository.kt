package kr.spdaewoon.magicjar.api.mcp.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * mcp_audit Spring Data JPA Repository.
 *
 * ## 헌법 §6 + ADR 0051 함정 회피
 * interface 안 body 가진 fun 0건 LOCK — UPSERT/통계 헬퍼는 caller class
 * ([kr.spdaewoon.magicjar.api.mcp.McpAuditService])의 private fun 또는 extension 으로 이동.
 * Spring Data 가 inferred query 로 SQL 생성 시도 → `No property 'X' found` 부팅 실패 risk 차단.
 */
interface McpAuditJpaRepository : JpaRepository<McpAuditJpaEntity, Long>
