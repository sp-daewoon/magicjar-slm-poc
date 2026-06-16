package kr.spdaewoon.magicjar.api.persistence.strategyresearch

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 전략 리서치 AI 종목발굴 core(wrapper) 전역 system prompt.
 *
 * 단일행(id=[SINGLETON_ID]) 운영. 행이 존재하면 DB가 권위이고, 없으면 서비스 코드 기본값으로 fallback 한다.
 */
@Entity
@Table(name = "strategy_research_ai_discovery_core_prompt")
class StrategyResearchAiDiscoveryCorePromptJpaEntity(
    @Id
    @Column(name = "id")
    val id: Short = SINGLETON_ID,
    @Column(name = "prompt_text", nullable = false, columnDefinition = "TEXT")
    var promptText: String,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        id = SINGLETON_ID,
        promptText = "",
    )

    companion object {
        const val SINGLETON_ID: Short = 1
    }
}
