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
 * 백테스트 포지션 JPA 엔티티 — Flyway V13 (ADR 0061 §3 B-1 채택).
 *
 * run_id FK → backtest_run(id) ON DELETE CASCADE.
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인 의존.
 *
 * close_ts: null이면 강제 청산 미완료(시뮬 종료 후 존재하지 않음, JvmBacktestEngine은 항상 강제 청산)
 * pnl: 실현 손익 (KRW, 수수료·세금 차감 후)
 * return_pct: (exit_price - entry_price) / entry_price — 슬리피지·비용 적용 수익률
 */
@Entity
@Table(name = "backtest_position")
class BacktestPositionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    val run: BacktestRunJpaEntity,
    @Column(name = "open_ts", nullable = false)
    val openTs: Instant,
    @Column(name = "close_ts")
    val closeTs: Instant? = null,
    @Column(name = "entry_price", nullable = false, precision = 19, scale = 4)
    val entryPrice: BigDecimal,
    @Column(name = "exit_price", precision = 19, scale = 4)
    val exitPrice: BigDecimal? = null,
    @Column(name = "quantity", nullable = false)
    val quantity: Long,
    @Column(name = "pnl", precision = 19, scale = 4)
    val pnl: BigDecimal? = null,
    @Column(name = "return_pct", precision = 7, scale = 4)
    val returnPct: BigDecimal? = null,
)
