package kr.spdaewoon.magicjar.api.persistence.strategyresearch

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 전략 리서치 AI 종목발굴 intent preset.
 *
 * Core wrapper는 서비스 코드가 권위이고, 사용자가 조정하는 발굴 로직 prompt만 이 테이블에서 관리한다.
 */
@Entity
@Table(name = "strategy_research_ai_discovery_preset")
class StrategyResearchAiDiscoveryPresetJpaEntity(
    @Id
    @Column(name = "id", length = 64)
    val id: String,
    @Column(name = "label", nullable = false, length = 80)
    var label: String,
    @Column(name = "description", nullable = false, length = 240)
    var description: String,
    @Column(name = "prompt_text", nullable = false, columnDefinition = "TEXT")
    var promptText: String,
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 100,
    @Column(name = "is_active", nullable = false)
    var active: Boolean = true,
    @Column(name = "is_builtin", nullable = false)
    var builtIn: Boolean = true,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        id = "",
        label = "",
        description = "",
        promptText = "",
    )
}
