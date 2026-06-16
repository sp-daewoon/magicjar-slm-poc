package kr.spdaewoon.magicjar.consumer.account

import kr.spdaewoon.magicjar.consumer.infrastructure.kis.ConsumerKisBalanceQueryAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshot
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshotPort
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 계좌 availability snapshot refresh orchestrator.
 *
 * KIS 호출은 주문 hot path 밖에서만 수행한다. 요청은 `userId+mode` 단위로 coalesce/debounce 되고,
 * refresh 결과만 Redis snapshot 으로 LWW 저장된다.
 */
@Service
class AccountAvailabilityRefreshService(
    private val balanceQueryAdapter: ConsumerKisBalanceQueryAdapter,
    private val snapshotPort: AccountAvailabilitySnapshotPort,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val accountSnapshotCoordinatorProvider: ObjectProvider<AccountSnapshotCoordinator>? = null,
    @Value("\${magicjar.kis.account-availability.refresh-enabled:false}")
    private val refreshEnabled: Boolean = false,
    @Value("\${magicjar.kis.account-availability.refresh-debounce-ms:2000}")
    private val refreshDebounceMillis: Long = 2000,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(AccountAvailabilityRefreshService::class.java)
    private val pending = ConcurrentHashMap<RefreshKey, PendingRefresh>()

    fun requestRefresh(
        userId: String,
        mode: TradingMode,
        reason: String,
    ) {
        val now = clock.millis()
        pending[RefreshKey(userId, mode)] = PendingRefresh(requestedAtMillis = now, reason = reason.take(80))
        log.debug(
            "account availability refresh queued: userId={} mode={} reason={}",
            maskUserId(userId),
            mode.name,
            reason,
        )
    }

    @Scheduled(fixedDelayString = "\${magicjar.kis.account-availability.refresh-debounce-ms:2000}", initialDelay = 5_000)
    fun drainDueRefreshRequests() {
        val now = clock.millis()
        pending.forEach { (key, pendingRefresh) ->
            if (now - pendingRefresh.requestedAtMillis < refreshDebounceMillis) return@forEach
            val removed = pending.remove(key, pendingRefresh)
            if (removed) refreshNow(key, pendingRefresh)
        }
    }

    @Scheduled(
        fixedDelayString = "\${magicjar.kis.account-availability.refresh-fixed-delay-ms:300000}",
        initialDelay = 30_000,
    )
    fun enqueuePeriodicRefresh() {
        if (!refreshEnabled) return
        credentialRepository.findAll().forEach { credential ->
            val mode = runCatching { TradingMode.valueOf(credential.id.accountType) }.getOrNull() ?: return@forEach
            requestRefresh(credential.id.userId, mode, reason = "PERIODIC_5M")
        }
    }

    private fun refreshNow(
        key: RefreshKey,
        pendingRefresh: PendingRefresh,
    ) {
        if (!refreshEnabled) {
            log.debug(
                "account availability refresh disabled — skip: userId={} mode={} reason={}",
                maskUserId(key.userId),
                key.mode.name,
                pendingRefresh.reason,
            )
            return
        }
        val accountType = AccountType.valueOf(key.mode.name)
        try {
            val balance =
                accountSnapshotCoordinatorProvider
                    ?.getIfAvailable()
                    ?.getSnapshot(
                        userId = key.userId,
                        accountType = accountType,
                        reason = "ACCOUNT_AVAILABILITY_REFRESH:${pendingRefresh.reason}",
                        path = AccountSnapshotCoordinator.QueryPath.BACKGROUND_REFRESH,
                    )
                    ?: balanceQueryAdapter.fetchFullBalanceForAvailabilityRefresh(key.userId, accountType)
            val refreshedAt = Instant.now(clock)
            val snapshot =
                AccountAvailabilitySnapshot.from(
                    balance = balance,
                    mode = key.mode,
                    versionMillis = clock.millis(),
                    refreshedAt = refreshedAt,
                )
            val stored = snapshotPort.putIfNewer(snapshot)
            log.info(
                "account availability refresh {}: userId={} mode={} holdings={} sellable={} cashD2={} reason={}",
                if (stored) "stored" else "lww-skip",
                maskUserId(key.userId),
                key.mode.name,
                snapshot.holdingQtyByCode.size,
                snapshot.sellableQtyByCode.size,
                snapshot.cashD2,
                pendingRefresh.reason,
            )
        } catch (e: Exception) {
            log.warn(
                "account availability refresh failed — snapshot unchanged: userId={} mode={} reason={} err={}",
                maskUserId(key.userId),
                key.mode.name,
                pendingRefresh.reason,
                e.message,
            )
        }
    }

    private data class RefreshKey(
        val userId: String,
        val mode: TradingMode,
    )

    private data class PendingRefresh(
        val requestedAtMillis: Long,
        val reason: String,
    )

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
