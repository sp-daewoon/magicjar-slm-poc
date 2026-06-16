package kr.spdaewoon.magicjar.api.service.risk

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kr.spdaewoon.magicjar.api.controller.risk.ConcurrentUsage
import kr.spdaewoon.magicjar.api.controller.risk.DailyLossUsage
import kr.spdaewoon.magicjar.api.controller.risk.PositionUsage
import kr.spdaewoon.magicjar.api.controller.risk.RiskUsageResponse
import kr.spdaewoon.magicjar.api.controller.risk.TradeFrequencyUsage
import kr.spdaewoon.magicjar.api.persistence.position.PositionJpaAdapter
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.risk.RiskBudgetCalculator
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * 리스크 한도 현재 사용량 조회 서비스 — GET /api/risk/usage (읽기 전용 · 새 KIS 호출 0).
 *
 * ## 단일 진실원 — 사이징(PR #1011)과 동일 소스
 * position 섹션은 매수 사이징(consumer `RiskGatewayEvaluator.resolveBuyBudget`)이 호출하는 domain
 * [RiskBudgetCalculator] 를 그대로 호출한다. 표시값(화면)과 실제 판정(게이트)이 같은 함수로 산출돼
 * "표시값 ≠ 실제판정" 갭이 컴파일 단위에서 사라진다(목적 §). dailyLoss·concurrent 도 RiskGatewayEvaluator
 * 와 동일 식(amount/percent xor·positions.size)으로 계산한다.
 *
 * ## 데이터 소스 (RiskContext 빌드 경로와 동일 · 새 KIS 호출 금지)
 * | 섹션          | 소스                                                                  | 비고 |
 * |--------------|----------------------------------------------------------------------|------|
 * | config       | [RiskConfigService.getOrDefault] (DB risk_config, 부재 시 H-1 seed)    |      |
 * | capital      | Redis `account:balance:*` cache **read-only** (KIS 미호출 — cache miss=unavailable) | netAsset·예수금 |
 * | positions    | [PositionJpaAdapter.findCurrentByUser] (DB, RiskPositionPort)          | 전체노출·동시보유 |
 * | dailyPnl     | [PositionJpaAdapter.sumDailyRealizedPnl] (DB, KST 자정 기준)            | DLL |
 * | recentTrades | Redis trade-frequency ZSET (consumer 가 record 한 동일 키)              | 거래빈도 |
 *
 * ## capital unavailable 시 — config 파생값은 채우고 순자산 의존 필드만 null (B 카드 정합)
 * Redis 잔고 cache 가 비어 있으면(주문 경로가 아직 캐시를 채우지 않음) capital 을 0 으로 합성하지 않는다
 * (feedback_no_mock_data · F10 정신). 단, position 섹션을 통째로 null 로 떨어뜨리지 않고 dailyLoss 카드와
 * 동일하게 **부분 렌더**한다 — config·positions 만으로 산출되는 절대한도(maxPositionAmount)·maxPositionPercent·
 * totalExposure 는 그대로 채우고, 순자산(netAssetValue) 의존 6필드(percentBase·percentLimit·totalLimit·
 * remaining·orderableCash·buyable)만 null 로 내린다. 새 KIS 잔고조회는 여전히 트리거하지 않는다(TPS 보호).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Service
class RiskUsageService(
    private val riskConfigService: RiskConfigService,
    private val positionJpaAdapter: PositionJpaAdapter,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(RiskUsageService::class.java)

    companion object {
        private val KST_KT = TimeZone.of("Asia/Seoul")
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val MINUTE_WINDOW_SEC = 60L
        private const val DAY_WINDOW_SEC = 86400L
    }

    /**
     * 사용량 snapshot 산출.
     *
     * @param userId     사용자 ID
     * @param strategyId 거래빈도 평가 대상 전략 (null 이면 tradeFrequency 섹션 null)
     */
    @Transactional(readOnly = true)
    fun getUsage(
        userId: String,
        strategyId: Long?,
    ): RiskUsageResponse {
        val config = riskConfigService.getOrDefault(userId)
        val positions = positionJpaAdapter.findCurrentByUser(userId)
        val capital = readCapitalFromCacheOrNull(userId)

        return RiskUsageResponse(
            // capital 미상이어도 position 섹션은 항상 non-null — config·positions 파생값은 채우고 순자산 의존 필드만 null.
            position = buildPositionUsage(config, capital, positions),
            dailyLoss = buildDailyLossUsage(config, capital?.netAssetValue, userId),
            concurrent = buildConcurrentUsage(config, positions),
            tradeFrequency = strategyId?.let { buildTradeFrequencyUsage(config, userId, it) },
        )
    }

    // ── position (매수 한도) — RiskBudgetCalculator 단일 진실원 ───────────────────

    /**
     * position 섹션 조립 — capital 미상이어도 절대 null 로 떨어지지 않는 부분 렌더.
     *
     * - capital != null: 매수 사이징(PR #1011)이 호출하는 domain [RiskBudgetCalculator.resolveBuyBudget] 를
     *   그대로 호출해 전 필드 채움(표시값 == 실제판정, 산식 1바이트 불변).
     * - capital == null: config·positions 파생값(absoluteLimit·maxPositionPercent·totalExposure)만 채우고
     *   순자산 의존 6필드는 null (0 fabricate 금지 · 새 KIS 호출 0).
     */
    private fun buildPositionUsage(
        config: RiskConfig,
        capital: CapitalSnapshot?,
        positions: List<Position>,
    ): PositionUsage {
        // totalExposure 는 capital 무관 — config·positions 만으로 항상 산출(domain 단일 진실원).
        val totalExposure = RiskBudgetCalculator.totalExposure(positions)
        return capital
            ?.let { snapshot ->
                // 매수 사이징(PR #1011)이 호출하는 domain 계산기를 그대로 호출 — capital 있을 때 산식·결과 불변.
                val budget =
                    RiskBudgetCalculator.resolveBuyBudget(
                        config = config,
                        currentCapital = snapshot.netAssetValue,
                        orderableCash = snapshot.orderableCash,
                        positions = positions,
                    )
                PositionUsage(
                    absoluteLimit = budget.absoluteLimit,
                    maxPositionPercent = config.maxPositionPercent,
                    percentBase = budget.currentCapital,
                    percentLimit = budget.percentLimit,
                    totalLimit = budget.totalLimit,
                    totalExposure = budget.totalExposure,
                    remaining = budget.remainingLimit,
                    orderableCash = budget.orderableCash,
                    buyable = budget.buyable,
                )
            }
            // capital 미상 — config 파생 한도·totalExposure 만 채우고 순자산 의존 필드는 null.
            ?: PositionUsage(
                absoluteLimit = config.maxPositionAmount,
                maxPositionPercent = config.maxPositionPercent,
                percentBase = null,
                percentLimit = null,
                totalLimit = null,
                totalExposure = totalExposure,
                remaining = null,
                orderableCash = null,
                buyable = null,
            )
    }

    // ── dailyLoss ──────────────────────────────────────────────────────────────

    private fun buildDailyLossUsage(
        config: RiskConfig,
        netAssetValue: BigDecimal?,
        userId: String,
    ): DailyLossUsage {
        val todayPnl = readDailyRealizedPnl(userId)
        // percent 한도는 capital 필요 — 없으면 remaining 미산정(null). amount 한도는 capital 무관.
        val remaining =
            RiskBudgetCalculator.dailyLossRemaining(
                config = config,
                currentCapital = netAssetValue ?: BigDecimal.ZERO,
                todayRealizedPnl = todayPnl,
            )
        return DailyLossUsage(
            enabled = config.isDailyLossLimitActive,
            limitAmount = config.dailyLossLimitAmount,
            limitPercent = config.dailyLossLimitPercent,
            todayRealizedPnl = todayPnl,
            // percent 한도인데 capital 미상이면 remaining=null (환산 불가) — RiskBudgetCalculator 가 null 반환.
            remaining = remaining,
        )
    }

    /**
     * 당일(KST) 실현 손익 — RiskContext.dailyRealizedPnl 과 동일 출처(RiskPositionPort.sumDailyRealizedPnl).
     * 조회 실패 시 0 (DAILY_LOSS 룰의 fallback 정합 — DailyPnlCalculator MOCK fallback 0 과 동일 보수값).
     */
    private fun readDailyRealizedPnl(userId: String): BigDecimal =
        try {
            val kstDate = clock.now().toLocalDateTime(KST_KT).date
            val kstMidnight = kstDate.atTime(LocalTime(0, 0, 0)).toInstant(KST_KT)
            positionJpaAdapter.sumDailyRealizedPnl(userId, kstMidnight)
        } catch (e: Exception) {
            log.warn("RiskUsage dailyRealizedPnl 조회 실패 (fallback 0): userId={} err={}", userId, e.message)
            BigDecimal.ZERO
        }

    // ── concurrent ─────────────────────────────────────────────────────────────

    private fun buildConcurrentUsage(
        config: RiskConfig,
        positions: List<Position>,
    ): ConcurrentUsage {
        // RiskGatewayEvaluator MAX_CONCURRENT 는 currentPositions.size 를 본다(findCurrentByUser = quantity>0 행).
        val current = positions.count { it.quantity > 0 }
        val limit = config.maxConcurrentPositions
        return ConcurrentUsage(
            limit = limit,
            current = current,
            remaining = (limit - current).coerceAtLeast(0),
        )
    }

    // ── tradeFrequency ─────────────────────────────────────────────────────────

    private fun buildTradeFrequencyUsage(
        config: RiskConfig,
        userId: String,
        strategyId: Long,
    ): TradeFrequencyUsage {
        val nowEpoch = clock.now().epochSeconds
        val perMinuteUsed = countTradeFrequency(strategyId, userId, "minute", nowEpoch, MINUTE_WINDOW_SEC, floorEpoch = null)
        val perDayUsed =
            countTradeFrequency(strategyId, userId, "day", nowEpoch, DAY_WINDOW_SEC, floorEpoch = kstMidnightEpoch(nowEpoch))
        return TradeFrequencyUsage(
            strategyId = strategyId,
            perMinuteLimit = config.maxTradesPerMinute,
            perMinuteUsed = perMinuteUsed,
            perDayLimit = config.maxTradesPerDay,
            perDayUsed = perDayUsed,
            perDayRemaining = (config.maxTradesPerDay - perDayUsed).coerceAtLeast(0),
        )
    }

    /**
     * Redis ZSET sliding window count — TradeFrequencyRedisGuard.count 와 동일 키·동일 윈도 식.
     *
     * day 윈도 하한은 당일(KST) 자정과 롤링 24h 중 더 보수적인 값(canary D2 hotfix #3 정합).
     * 표시 전용이라 조회 실패 시 0 으로 처리(게이트의 fail-closed Int.MAX_VALUE 와 의도가 다름 — 화면을 막지 않음).
     */
    private fun countTradeFrequency(
        strategyId: Long,
        userId: String,
        window: String,
        nowEpoch: Long,
        windowSec: Long,
        floorEpoch: Long?,
    ): Int =
        try {
            val key = RedisKeyRegistry.Risk.tradeFrequency(strategyId, userId, window)
            val rollingFrom = (nowEpoch - windowSec).toDouble()
            val fromEpoch = floorEpoch?.let { maxOf(rollingFrom, it.toDouble()) } ?: rollingFrom
            redisTemplate.opsForZSet().count(key, fromEpoch, nowEpoch.toDouble())?.toInt() ?: 0
        } catch (e: Exception) {
            log.warn("RiskUsage tradeFrequency count 실패 (표시 0): strategyId={} window={} err={}", strategyId, window, e.message)
            0
        }

    private fun kstMidnightEpoch(nowEpoch: Long): Long =
        LocalDate
            .ofInstant(java.time.Instant.ofEpochSecond(nowEpoch), KST)
            .atStartOfDay(KST)
            .toEpochSecond()

    // ── capital (Redis cache read-only — 새 KIS 호출 0) ──────────────────────────

    /**
     * Redis `account:balance` cache 에서 capital snapshot 을 **읽기 전용** 으로 추출.
     *
     * - cache hit → netAssetValue + orderableCash(cashD2 fallback cashBalance — KisCapitalSnapshotProviderAdapter
     *   .resolveOrderableCash 와 동일 식) 반환.
     * - cache miss / 역직렬화 실패 → null (KIS 미호출 — 0 fabricate 금지, position 섹션 null 처리).
     */
    private fun readCapitalFromCacheOrNull(userId: String): CapitalSnapshot? {
        val accountType: AccountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        val cacheKey = RedisKeyRegistry.Account.balance(userId, accountType)
        val cached =
            try {
                redisTemplate.opsForValue().get(cacheKey)
            } catch (e: Exception) {
                log.warn("RiskUsage capital cache 조회 실패 (unavailable): userId={} err={}", maskUserId(userId), e.message)
                null
            } ?: run {
                log.debug(
                    "RiskUsage capital cache miss — position 섹션 null (KIS 미호출): userId={} type={}",
                    maskUserId(userId),
                    accountType.name,
                )
                return null
            }
        val snapshot =
            runCatching { objectMapper.readValue<KisAccountBalance>(cached) }
                .onFailure { log.warn("RiskUsage capital cache 역직렬화 실패 (unavailable): userId={} err={}", maskUserId(userId), it.message) }
                .getOrNull() ?: return null
        return CapitalSnapshot(
            netAssetValue = snapshot.balance.netAssetValue,
            orderableCash = resolveOrderableCash(snapshot.balance),
        )
    }

    /**
     * 주문가능 예수금 — KisCapitalSnapshotProviderAdapter.resolveOrderableCash 와 동일 식(매수 버짓 동일 소스).
     * cashD2(D+2 실가용) > 0 이면 그것을, 아니면 cashBalance(예수금총액) > 0 이면 그것을, 둘 다 비정상이면 0.
     */
    private fun resolveOrderableCash(balance: AccountBalance): BigDecimal {
        if (balance.cashD2 > BigDecimal.ZERO) return balance.cashD2
        if (balance.cashBalance > BigDecimal.ZERO) return balance.cashBalance
        return BigDecimal.ZERO
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    private data class CapitalSnapshot(
        val netAssetValue: BigDecimal,
        val orderableCash: BigDecimal,
    )
}
