package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.consumer.account.AccountSnapshotCoordinator
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisBalanceRateLimitedException
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * KIS 잔고 기반 [CapitalSnapshotProvider] 실제 어댑터 — 주문경로 자본 산정
 * (ADR 0081 §2-5 + ADR 0116 R1-3 + **F10 hotfix 2026-06-12**).
 *
 * ### 책임 — 주문경로 전용 fresh 조회 (ADR 0116 R1-3 자본캐시 제거)
 * 본 provider의 유일한 호출처는 [SignalGeneratedKafkaListener.buildContext]의 `currentCapital` —
 * **주문 수량/한도(POSITION_LIMIT 예산·DAILY_LOSS_LIMIT) 산정 경로**다. stale 잔고는 오버주문 위험이므로
 * (사용자 지시 — 주문 관련 캐시 제거) Redis 표시용 cache를 **우회하고 KIS [KisBalanceQueryPort]로 직접 조회**한다.
 * 단, 2026-06-15 REAL 장중에서 1분 신호가 직전 성공 잔고 직후에도 매번 KIS 잔고를 직접 조회해 `EGW00215`
 * 를 재유발했다. 그래서 아주 짧은 fresh-cache reuse window(default 90초)를 둔다. 이 창 안의 직전 성공
 * snapshot 은 KIS 원장 TPS 보호를 위해 재사용한다(0이면 비활성). 직접 조회가 실패한 직후에는
 * cooldown(default 120초) 동안 같은 endpoint 재시도를 생략해 cache miss 상태의 매분 재시도 폭주도 막는다.
 *
 * ### F10 — 조회 실패 시 0 fabricate 폐기 → 재시도 → stale-cache → skip (2026-06-12 최고 관리자 결재)
 * 14:12 라이브 incident: 잔고조회가 rate limit(EGW00215)로 실패하자 **capital=0 을 fabricate** →
 * "총자산 0원 × 70% = 한도 0원"으로 POSITION_LIMIT 거짓 차단 + AutoPause 오염 → 실계좌 자산이 있는데도
 * 정상 매수 기회 상실. 결재 지침에 따라 어떤 실패에서도 0 을 만들지 않고 다음 3단으로 처리한다:
 *
 * 1. **재시도** — rate limit 류는 하부 [KisBalanceQueryPort] 구현이 backoff 재시도(default 2회, 1s+ 간격).
 *    소진 시 [KisBalanceRateLimitedException] 으로 드러난다.
 * 2. **최근 성공 stale-cache** — 재시도도 실패하면 유효 TTL(default 5분) 내 최근 성공 snapshot 을
 *    `stale=true` 로 재사용([CapitalOutcome.Available]). "0" 보다 "직전 실제 잔고"가 훨씬 사실에 가깝다.
 * 3. **그래도 없으면 skip** — TTL 초과·캐시 없음이면 [CapitalOutcome.Unavailable] 반환 → 호출부가
 *    이번 시그널만 조용히 audit(skip) 종결(POSITION_LIMIT 위반·Slack·AutoPause 전부 제외).
 *
 * 성공 조회 때마다 capital snapshot 을 in-memory 로 갱신해 다음 실패 시 재료로 쓴다(주문 직전 stale 우려는
 * "장중 정상 조회는 fresh, 실패 N분만 직전값" 이므로 0-fabricate 보다 안전 — 결재 §2 명시).
 *
 * ### 모듈 경계 정합 (ADR 0004 LOCK)
 * - consumer → domain (`CapitalSnapshotProvider`·`CapitalOutcome`·`KisBalanceQueryPort` port) ✅
 * - consumer → api 의존 0 LOCK ✅
 *
 * ### 진보 페르소나 (ADR 0087)
 * - ✨ [CapitalOutcome] sealed 로 "값 있음 / 조회 불가" 를 타입 분리 — 0 fabricate 경로를 컴파일에서 제거.
 * - 🔄 재시도는 하부 adapter(DRY — `ConsumerKisBalanceQueryAdapter` 단일 retry 로직 재사용), 본 provider 는
 *   stale-cache·skip 정책만 책임(SRP).
 *
 * ### mock 합성 0 LOCK (feedback_no_mock_data)
 * 실패 시 mock 합성 0 금지 — Unavailable(skip) 또는 직전 실제 성공값(stale)만.
 *
 * ### AI 호출 0 LOCK (룰 5 + ADR 0023 §2-4)
 */
@Component
class KisCapitalSnapshotProviderAdapter(
    private val balanceQueryPortProvider: ObjectProvider<KisBalanceQueryPort>? = null,
    private val accountSnapshotCoordinatorProvider: ObjectProvider<AccountSnapshotCoordinator>? = null,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
    // F10 — 최근 성공 capital snapshot 유효 시간(분). 이 시간 내면 조회 실패 시 직전 성공값(stale)을 재사용.
    @Value("\${magicjar.risk.capital-stale-cache-ttl-minutes:5}")
    private val staleCacheTtlMinutes: Long = 5,
    @Value("\${magicjar.risk.capital-fresh-cache-reuse-seconds:90}")
    private val freshCacheReuseSeconds: Long = 90,
    @Value("\${magicjar.risk.capital-direct-failure-cooldown-seconds:120}")
    private val directFailureCooldownSeconds: Long = 120,
    private val clock: () -> Instant = Instant::now,
) : CapitalSnapshotProvider {
    private val log = LoggerFactory.getLogger(KisCapitalSnapshotProviderAdapter::class.java)

    /** key=userId → 최근 성공 capital snapshot(값 + 시각). 같은 partition(userId) 단일 consumer thread 처리 — in-memory 충분. */
    private val recentCapital = ConcurrentHashMap<String, CapitalSnapshot>()
    private val recentFailures = ConcurrentHashMap<String, CapitalFailure>()

    private data class CapitalSnapshot(
        val capital: BigDecimal,
        val orderableCash: BigDecimal,
        val at: Instant,
    )

    private data class CapitalFailure(
        val reason: String,
        val at: Instant,
    )

    /**
     * 레거시 시그니처 — 하위호환 위임. 주문경로는 [capitalOutcome] 을 직접 쓴다.
     * Unavailable 은 레거시 의미(값 없음)대로 0 환원하되, **주문경로 호출부는 이 메서드를 쓰지 않는다**.
     */
    override fun getCapital(userId: String): BigDecimal =
        when (val outcome = capitalOutcome(userId)) {
            is CapitalOutcome.Available -> outcome.capital
            is CapitalOutcome.Unavailable -> BigDecimal.ZERO
        }

    /**
     * 자본 산출(F10 권위 진입점) — fresh 조회 → (실패 시) stale-cache → (없으면) Unavailable.
     *
     * **0 fabricate 절대 금지**: 어떤 실패에서도 [CapitalOutcome.Available] 0 을 만들지 않는다. 실제 0 잔고는
     * KIS 가 성공적으로 0 을 반환한 경우에만 발생(그 경우만 정상 POSITION_LIMIT 평가 대상).
     */
    override fun capitalOutcome(userId: String): CapitalOutcome {
        val accountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        accountSnapshotCoordinatorProvider?.getIfAvailable()?.let { coordinator ->
            return try {
                val snapshot =
                    coordinator.getSnapshot(
                        userId = userId,
                        accountType = accountType,
                        reason = "RISK_CAPITAL",
                    )
                val netAsset = snapshot.balance.netAssetValue
                val orderableCash = resolveOrderableCash(snapshot.balance)
                recentCapital[userId] = CapitalSnapshot(netAsset, orderableCash, clock())
                recentFailures.remove(userId)
                val ageSeconds = Duration.between(snapshot.fetchedAt, clock()).seconds
                log.info(
                    "KisCapitalSnapshotProvider account snapshot 사용 — netAsset={} orderableCash={} cacheAgeSec={} (userId={} type={})",
                    netAsset,
                    orderableCash,
                    ageSeconds,
                    maskUserId(userId),
                    accountType.name,
                )
                CapitalOutcome.Available(capital = netAsset, orderableCash = orderableCash, stale = false)
            } catch (e: KisBalanceRateLimitedException) {
                log.warn(
                    "KisCapitalSnapshotProvider account snapshot rate limit — stale-cache 시도(userId={})",
                    maskUserId(userId),
                )
                markFailure(userId, "KIS_RATE_LIMIT")
                staleCacheOr(userId, reason = "KIS_RATE_LIMIT")
            } catch (e: Exception) {
                val reason = "KIS_LOOKUP_FAILED:${e.message ?: "UNKNOWN"}"
                log.warn(
                    "KisCapitalSnapshotProvider account snapshot 조회 실패 — stale-cache 시도(userId={} err={})",
                    maskUserId(userId),
                    e.message,
                )
                markFailure(userId, reason)
                staleCacheOr(userId, reason = reason)
            }
        }

        freshCacheOrNull(userId)?.let { return it }
        failureCooldownOrNull(userId)?.let { return it }

        val port = balanceQueryPortProvider?.getIfAvailable()
        if (port == null) {
            log.error(
                "KisCapitalSnapshotProvider KIS 조회 bean 부재 — capital 산출 불가(skip · 0 fabricate 금지) userId={}",
                maskUserId(userId),
            )
            return staleCacheOr(userId, reason = "PROVIDER_UNAVAILABLE")
        }

        return try {
            // 캐시 우회 — 주문 수량/한도 산정에 stale 잔고 금지(ADR 0116 R1-3). USER_PATH acquire 는 port 구현이 보장.
            // rate limit backoff 재시도(default 2회)도 port 구현이 캡슐화 — 소진 시 KisBalanceRateLimitedException.
            val snapshot = port.fetchFullBalance(userId, accountType)
            val netAsset = snapshot.balance.netAssetValue
            val orderableCash = resolveOrderableCash(snapshot.balance)
            recentCapital[userId] = CapitalSnapshot(netAsset, orderableCash, clock())
            recentFailures.remove(userId)
            log.info(
                "KisCapitalSnapshotProvider KIS fresh 조회 — netAsset={} orderableCash={} (userId={} type={}, 캐시 우회 — 주문경로)",
                netAsset,
                orderableCash,
                maskUserId(userId),
                accountType.name,
            )
            CapitalOutcome.Available(capital = netAsset, orderableCash = orderableCash, stale = false)
        } catch (e: KisBalanceRateLimitedException) {
            // 재시도까지 모두 소진된 rate limit — 0 fabricate 금지. stale-cache → skip.
            log.warn(
                "KisCapitalSnapshotProvider 잔고 rate limit 재시도 소진 — stale-cache 시도(userId={})",
                maskUserId(userId),
            )
            markFailure(userId, "KIS_RATE_LIMIT")
            staleCacheOr(userId, reason = "KIS_RATE_LIMIT")
        } catch (e: Exception) {
            val reason = "KIS_LOOKUP_FAILED:${e.message ?: "UNKNOWN"}"
            log.warn(
                "KisCapitalSnapshotProvider KIS 직접 조회 실패 — stale-cache 시도(userId={} err={})",
                maskUserId(userId),
                e.message,
            )
            markFailure(userId, reason)
            staleCacheOr(userId, reason = reason)
        }
    }

    /**
     * 조회 실패 fallback — 유효 TTL 내 최근 성공 snapshot 이 있으면 stale 로 재사용, 없으면 Unavailable(skip).
     * **0 fabricate 금지** — 어떤 경로도 capital=0 을 합성하지 않는다.
     */
    private fun staleCacheOr(
        userId: String,
        reason: String,
    ): CapitalOutcome {
        val cached = recentCapital[userId]
        if (cached != null) {
            val ageMinutes = Duration.between(cached.at, clock()).toMinutes()
            if (ageMinutes <= staleCacheTtlMinutes) {
                log.info(
                    "KisCapitalSnapshotProvider capital stale-cache 사용 — capital={} orderableCash={} ageMin={} ttlMin={} reason={} (userId={})",
                    cached.capital,
                    cached.orderableCash,
                    ageMinutes,
                    staleCacheTtlMinutes,
                    reason,
                    maskUserId(userId),
                )
                return CapitalOutcome.Available(capital = cached.capital, orderableCash = cached.orderableCash, stale = true)
            }
            log.warn(
                "KisCapitalSnapshotProvider stale-cache 만료(ageMin={} > ttlMin={}) — 시그널 skip reason={} (userId={})",
                ageMinutes,
                staleCacheTtlMinutes,
                reason,
                maskUserId(userId),
            )
        } else {
            log.warn(
                "KisCapitalSnapshotProvider stale-cache 없음 — 시그널 skip reason={} (userId={})",
                reason,
                maskUserId(userId),
            )
        }
        return CapitalOutcome.Unavailable(reason)
    }

    /**
     * 매우 짧은 성공 snapshot 재사용. 장중 1분 신호가 KIS 원장 TPS를 매번 소모하지 않게 하는 완충이다.
     * stale=true 로 반환해 caller 로그와 audit에서 직접 fresh 조회가 아니었음을 보존한다.
     */
    private fun freshCacheOrNull(userId: String): CapitalOutcome.Available? {
        val reuseSeconds = freshCacheReuseSeconds.coerceAtLeast(0)
        if (reuseSeconds <= 0) return null
        val cached = recentCapital[userId] ?: return null
        val ageSeconds = Duration.between(cached.at, clock()).seconds
        if (ageSeconds in 0..reuseSeconds) {
            log.info(
                "KisCapitalSnapshotProvider capital fresh-cache 재사용 — capital={} orderableCash={} ageSec={} reuseSec={} (userId={})",
                cached.capital,
                cached.orderableCash,
                ageSeconds,
                reuseSeconds,
                maskUserId(userId),
            )
            return CapitalOutcome.Available(capital = cached.capital, orderableCash = cached.orderableCash, stale = true)
        }
        return null
    }

    /**
     * 직전 KIS 직접 조회가 실패했으면 짧은 cooldown 동안 같은 실패를 반복하지 않는다.
     *
     * 2026-06-15 REAL 1분 신호에서는 재기동 직후 cache miss 상태에서 매분 잔고조회가 재시도되며
     * EGW00215를 증폭했다. cooldown 중에는 직전 성공 stale-cache가 있으면 그 값을, 없으면 skip을 반환한다.
     */
    private fun failureCooldownOrNull(userId: String): CapitalOutcome? {
        val cooldownSeconds = directFailureCooldownSeconds.coerceAtLeast(0)
        if (cooldownSeconds <= 0) return null
        val failure = recentFailures[userId] ?: return null
        val ageSeconds = Duration.between(failure.at, clock()).seconds
        if (ageSeconds in 0..cooldownSeconds) {
            log.info(
                "KisCapitalSnapshotProvider KIS 직접 조회 cooldown — direct lookup skip: ageSec={} cooldownSec={} reason={} (userId={})",
                ageSeconds,
                cooldownSeconds,
                failure.reason,
                maskUserId(userId),
            )
            return staleCacheOr(userId, reason = "KIS_LOOKUP_COOLDOWN:${failure.reason}")
        }
        recentFailures.remove(userId, failure)
        return null
    }

    private fun markFailure(
        userId: String,
        reason: String,
    ) {
        recentFailures[userId] = CapitalFailure(reason = reason, at = clock())
    }

    /**
     * 주문가능 예수금 산정 (매수 버짓 기준 — 사용자 결재 2026-06-16). **새 KIS 호출 없이** 이미 받은 잔고 snapshot 에서 추출.
     *
     * - 권위 = `prvs_rcdl_excc_amt`(D+2 실가용 예수금, [AccountBalance.cashD2]). 미수·증거금 없이 실제 출금/매수 가능한 현금.
     * - cashD2 가 0/음수 등 비정상이면 `dnca_tot_amt`(예수금총액, [AccountBalance.cashBalance])로 fallback.
     * - 둘 다 비정상(0 이하)이면 0 을 반환(실제 예수금 0 = 살 수 없음 — fabricate 아님, KIS 가 준 사실).
     *
     * 0 합성·mock 금지(feedback_no_mock_data): 여기서의 0 은 "KIS 가 성공 응답으로 준 현금 잔고가 실제 0"인 경우뿐이다.
     */
    private fun resolveOrderableCash(balance: AccountBalance): BigDecimal {
        if (balance.cashD2 > BigDecimal.ZERO) return balance.cashD2
        if (balance.cashBalance > BigDecimal.ZERO) return balance.cashBalance
        return BigDecimal.ZERO
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
