package kr.spdaewoon.magicjar.api.persistence.strategyresearch

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

/**
 * 전략 리서치 후보 전략 (V74 `strategy_research_candidate`).
 *
 * 진입 조건은 [entryDsl](strategy-engine 평가기 실행)이 권위. 청산은 target/stop/maxHold 파라미터로 표현하고,
 * 엔진이 exit profile 스윕으로 best를 고른다. 사용자가 본인 전략을 후보로 추가할 수 있다(is_builtin=false).
 */
@Entity
@Table(name = "strategy_research_candidate")
class StrategyResearchCandidateJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "candidate_key", nullable = false, length = 64, unique = true)
    val candidateKey: String,
    @Column(name = "title", nullable = false, length = 120)
    var title: String,
    @Column(name = "market_fit", nullable = false, length = 400)
    var marketFit: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entry_rules", nullable = false, columnDefinition = "jsonb")
    var entryRules: String = "[]",
    @Column(name = "entry_dsl", nullable = false, columnDefinition = "TEXT")
    var entryDsl: String,
    @Column(name = "target_profit_pct", precision = 9, scale = 4)
    var targetProfitPct: BigDecimal? = null,
    @Column(name = "stop_loss_pct", precision = 9, scale = 4)
    var stopLossPct: BigDecimal? = null,
    @Column(name = "max_hold_bars")
    var maxHoldBars: Int? = null,
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
        candidateKey = "",
        title = "",
        marketFit = "",
        entryDsl = "",
    )
}
