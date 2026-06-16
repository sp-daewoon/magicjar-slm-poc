package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.ZoneId
import kotlinx.datetime.Instant as KInstant

/**
 * Consumer-side RiskPositionPort adapter.
 *
 * SignalGeneratedKafkaListener runs in the consumer JVM, so api module adapters are outside its bean scan.
 * This adapter keeps T-RISK current-position checks wired on the actual auto-trading path.
 */
@Component
class ConsumerRiskPositionAdapter(
    private val positionJpaRepository: PositionJpaRepository,
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
) : RiskPositionPort {
    private val kst = ZoneId.of("Asia/Seoul")

    @Transactional(readOnly = true)
    override fun findCurrentByUser(userId: String): List<Position> =
        positionJpaRepository
            .findByUserIdAndSourceAndQuantityGreaterThan(userId, currentSource(), 0)
            .map { toDomain(it) }

    @Transactional(readOnly = true)
    override fun countCurrentByUser(userId: String): Int =
        positionJpaRepository.countByUserIdAndSourceAndQuantityGreaterThan(userId, currentSource(), 0)

    @Transactional(readOnly = true)
    override fun sumDailyRealizedPnl(
        userId: String,
        kstMidnight: KInstant,
    ): BigDecimal {
        val since = kstMidnight.toJavaInstant()
        val source = currentSource()
        val rows =
            jdbcTemplate.query(
                """
                SELECT req.code,
                       req.side,
                       req.mode,
                       oe.executed_qty,
                       oe.executed_price,
                       oe.fee,
                       oe.tax,
                       oe.executed_at,
                       oe.realized_pnl
                  FROM order_execution oe
                  JOIN order_request req ON req.id = oe.order_id
                 WHERE req.user_id = :userId
                   AND req.mode = :mode
                   AND oe.executed_at < now()
                 ORDER BY oe.executed_at ASC, oe.id ASC
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("mode", source),
            ) { rs, _ -> executionRow(rs) }

        if (rows.isEmpty()) return BigDecimal.ZERO

        val targetDate = since.atZone(kst).toLocalDate()
        val stateByCode = mutableMapOf<String, ReplayPositionState>()
        val realizedPnls = mutableListOf<BigDecimal>()

        rows.forEach { row ->
            val state = stateByCode.getOrPut(row.code) { ReplayPositionState() }
            val isTargetDate = row.executedAt.atZone(kst).toLocalDate() == targetDate
            when (row.side) {
                OrderSide.BUY -> applyBuy(state, row)
                OrderSide.SELL -> {
                    val computedPnl = applySell(state, row)
                    val pnl = row.realizedPnl ?: computedPnl
                    if (isTargetDate && pnl != null) realizedPnls += pnl
                }
            }
        }

        return realizedPnls.fold(BigDecimal.ZERO) { acc, pnl -> acc + pnl }.setScale(2, RoundingMode.HALF_UP)
    }

    @Suppress("unused")
    private fun legacyColumnSum(
        userId: String,
        kstMidnight: KInstant,
    ): BigDecimal =
        jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(oe.realized_pnl), 0)
              FROM order_execution oe
              JOIN order_request req ON req.id = oe.order_id
             WHERE req.user_id = :userId
               AND oe.executed_at >= :since
               AND oe.realized_pnl IS NOT NULL
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("since", kstMidnight.toJavaInstant()),
            BigDecimal::class.java,
        ) ?: BigDecimal.ZERO

    private fun currentSource(): String = RuntimeModeParser.accountTypeOrMock(magicjarMode).name

    private data class ReplayPositionState(
        var qty: BigDecimal = BigDecimal.ZERO,
        var avgPrice: BigDecimal = BigDecimal.ZERO,
    )

    private data class ExecutionRow(
        val code: String,
        val side: OrderSide,
        val executedQty: BigDecimal,
        val executedPrice: BigDecimal,
        val fee: BigDecimal,
        val tax: BigDecimal,
        val executedAt: java.time.Instant,
        val realizedPnl: BigDecimal?,
    )

    private fun executionRow(rs: ResultSet): ExecutionRow =
        ExecutionRow(
            code = rs.getString("code"),
            side = runCatching { OrderSide.valueOf(rs.getString("side")) }.getOrDefault(OrderSide.BUY),
            executedQty = rs.getBigDecimal("executed_qty") ?: BigDecimal.ZERO,
            executedPrice = rs.getBigDecimal("executed_price") ?: BigDecimal.ZERO,
            fee = rs.getBigDecimal("fee") ?: BigDecimal.ZERO,
            tax = rs.getBigDecimal("tax") ?: BigDecimal.ZERO,
            executedAt = rs.getTimestamp("executed_at").toInstant(),
            realizedPnl = rs.getBigDecimal("realized_pnl"),
        )

    private fun applyBuy(
        state: ReplayPositionState,
        row: ExecutionRow,
    ) {
        if (row.executedQty <= BigDecimal.ZERO) return
        val currentCost = state.qty.multiply(state.avgPrice)
        val addedCost =
            row.executedQty
                .multiply(row.executedPrice)
                .add(row.fee)
                .add(row.tax)
        val nextQty = state.qty.add(row.executedQty)
        state.qty = nextQty
        state.avgPrice =
            if (nextQty.signum() > 0) {
                currentCost.add(addedCost).divide(nextQty, 8, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
    }

    private fun applySell(
        state: ReplayPositionState,
        row: ExecutionRow,
    ): BigDecimal? {
        if (row.executedQty <= BigDecimal.ZERO || state.qty <= BigDecimal.ZERO) return null
        val closedQty = minOf(row.executedQty, state.qty)
        val expenseRatio = closedQty.divide(row.executedQty, 8, RoundingMode.HALF_UP)
        val expenses = row.fee.add(row.tax).multiply(expenseRatio)
        val realizedPnl =
            row.executedPrice
                .subtract(state.avgPrice)
                .multiply(closedQty)
                .subtract(expenses)
                .setScale(2, RoundingMode.HALF_UP)

        state.qty = state.qty.subtract(closedQty)
        if (state.qty <= BigDecimal.ZERO) {
            state.qty = BigDecimal.ZERO
            state.avgPrice = BigDecimal.ZERO
        }
        return realizedPnl
    }

    private fun toDomain(entity: PositionJpaEntity): Position =
        Position(
            id = entity.id,
            userId = entity.userId,
            code = entity.code,
            quantity = entity.quantity,
            avgPrice = entity.avgPrice,
            updatedAt = entity.updatedAt.toKotlinInstant(),
        )
}
