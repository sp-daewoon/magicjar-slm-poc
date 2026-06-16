package kr.spdaewoon.magicjar.api.persistence.strategyresearch

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * 전략 리서치 AI 종목발굴 current-state.
 *
 * 사용자별 마지막 발굴 결과 1건만 보관한다. 히스토리가 아니라 재발굴 UX를 위한 상태 저장소다.
 */
@Entity
@Table(name = "strategy_research_ai_discovery_state")
class StrategyResearchAiDiscoveryStateJpaEntity(
    @Id
    @Column(name = "user_id", length = 40)
    val userId: String,
    @Column(name = "query", nullable = false, columnDefinition = "TEXT")
    var query: String,
    @Column(name = "market", length = 16)
    var market: String? = null,
    @Column(name = "preset_id", length = 64)
    var presetId: String? = null,
    @Column(name = "candidate_limit", nullable = false)
    var candidateLimit: Int,
    @Column(name = "provider", nullable = false, length = 64)
    var provider: String,
    @Column(name = "generated_at", nullable = false)
    var generatedAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", columnDefinition = "jsonb", nullable = false)
    var warningsJson: String = "[]",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items", columnDefinition = "jsonb", nullable = false)
    var itemsJson: String = "[]",
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
