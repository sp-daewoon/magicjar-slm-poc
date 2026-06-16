package kr.spdaewoon.magicjar.api.persistence.backtest

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * 백테스트 매매 기록 JPA 엔티티 — Flyway V13 (ADR 0061 §3 B-1 채택).
 *
 * run_id FK → backtest_run(id) ON DELETE CASCADE.
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인 의존.
 *
 * side: BUY / SELL
 * fee: 수수료 + 세금 합산 (detail은 detail_json 참조)
 * rule_signal: 신호 발생 규칙 식별자 (최대 80자)
 */
@Entity
@Table(name = "backtest_trade")
class BacktestTradeJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    val run: BacktestRunJpaEntity,
    @Column(name = "ts", nullable = false)
    val ts: Instant,
    @Column(name = "side", nullable = false, length = 4)
    val side: String,
    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    val price: BigDecimal,
    @Column(name = "quantity", nullable = false)
    val quantity: Long,
    @Column(name = "fee", precision = 19, scale = 4)
    val fee: BigDecimal? = null,
    @Column(name = "rule_signal", length = 80)
    val ruleSignal: String? = null,
)
