package kr.spdaewoon.magicjar.consumer.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 관리자 실시간 구독 상태 스냅샷 발행기 (read-only — 구독·매매 로직 변경 0).
 *
 * ## 책임
 * KIS WS 세션·슬롯 회계·체결가/호가 구독 owner·체결통보 상태를 모아 Redis `ops:realtime:status`에
 * **5초 주기**로 직렬화 발행한다. api 모듈 `RealtimeStatusController`가 본 키를 read → 종목명 join +
 * stale 판정 + ACTIVE 전략/실시간 체결 구독 종목 1m 캔들 신선도를 덧붙여 관리자 화면에 반환한다.
 *
 * "구독 중인 모든 토픽·내부 상태를 한 화면에서 보고 장애를 처리"하기 위한 관측 전용 레이어 — 본 발행기는
 * [RealtimeSubscriptionRouter]·[OrderbookSubscriptionRouter]·[WsSlotBudgetLedger]·[KisWebSocketBridge]의
 * read-only snapshot 접근자만 호출한다(구독 demand 맵·슬롯 회계 변경 0).
 *
 * ## 발행 주기 (계약 LOCK)
 * - 5초 `@Scheduled` — 기본 cadence.
 * - 구독 변경 즉시 — 라우터가 owner 등록/해제 직후 [publishNow]를 호출(lock 밖 추가 emit, 회계 무관).
 *
 * ## graceful (헌법 §6 — ObjectProvider 패턴)
 * 모든 의존은 ObjectProvider — qa fixture 등 빈 부재 환경에서도 부팅·no-op 보장. publish 실패는
 * runCatching으로 흡수해 스케줄러 cascade 차단을 막는다(관측 레이어가 본 흐름을 죽이지 않는다).
 *
 * // AI 호출 0 — 관측 read-only (ADR 0023 §2-4)
 */
@Component
class RealtimeStatusSnapshotPublisher(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val bridgeProvider: ObjectProvider<KisWebSocketBridge>,
    private val realtimeRouterProvider: ObjectProvider<RealtimeSubscriptionRouter>,
    private val orderbookRouterProvider: ObjectProvider<OrderbookSubscriptionRouter>,
    private val slotLedgerProvider: ObjectProvider<WsSlotBudgetLedger>,
) {
    private val log = LoggerFactory.getLogger(RealtimeStatusSnapshotPublisher::class.java)

    companion object {
        /** api·frontend 계약 LOCK — 관리자 실시간 상태 스냅샷 Redis 키. */
        val REDIS_KEY: String = RedisKeyRegistry.Ops.realtimeStatus()

        /** 키 TTL — 발행 정지(consumer down) 시 stale 키 자동 만료 → api가 부재로 인지. */
        private val TTL: Duration = Duration.ofSeconds(60)
    }

    /**
     * 5초 주기 발행 (계약 LOCK — api stale 판정 임계 15초보다 충분히 촘촘).
     *
     * 결함④ fix(2026-06-15): `scheduler` 를 [RealtimeStatusSchedulerConfiguration.REALTIME_STATUS_SCHEDULER]
     * 전용 단일 스레드로 격리한다. 기본 `scheduling-1` 단일 스레드에서는 무거운 polling/backfill(과거
     * `WatchlistPollingWorker` 시세 polling 등 — ADR 0124 amend 로 폐지) 이 스레드를 점유하는 동안 본 발행이
     * 밀려 `ops:realtime:status` TTL(60s) 만료 → 빨간 배너 false positive 가 발생했다. 전용 스케줄러
     * 격리로 어떤 polling 이 오래 걸려도 5초 cadence 가 유지된다.
     */
    @Scheduled(
        fixedDelayString = "\${magicjar.kis.ws.admin-status.publish-interval-ms:5000}",
        scheduler = RealtimeStatusSchedulerConfiguration.REALTIME_STATUS_SCHEDULER,
    )
    fun publishScheduled() {
        publishNow()
    }

    /**
     * 즉시 발행 — 구독 변경 직후 라우터가 호출(read-only snapshot 직렬화 → Redis SET).
     *
     * 구독/회계 상태를 변경하지 않는다(스냅샷 read만). 라우터 lock 밖에서 호출 전제.
     */
    fun publishNow() {
        runCatching {
            val snapshot = buildSnapshot()
            val json = objectMapper.writeValueAsString(snapshot)
            redisTemplate.opsForValue().set(REDIS_KEY, json, TTL)
        }.onFailure { e ->
            log.debug("ops:realtime:status 발행 실패(관측 레이어 — 흐름 무영향): err={}", e.message)
        }
    }

    private fun buildSnapshot(): RealtimeStatusSnapshot {
        val bridge =
            bridgeProvider.getIfAvailable()
                ?: return RealtimeStatusSnapshot(updatedAt = Instant.now().toString())
        val ws = bridge.wsStatusSnapshot()

        val slot = slotLedgerProvider.getIfAvailable()?.snapshot()
        val realtimeDemands = realtimeRouterProvider.getIfAvailable()?.snapshotDemands() ?: emptyList()
        val orderbookDemands = orderbookRouterProvider.getIfAvailable()?.snapshotDemands() ?: emptyList()

        // tr_id별 구독 투영 — 체결가 + 호가(H0UNASP0). code별 owner 합집합 + since.
        // ADR 0124 결정 4 — 체결가 trId 는 하드코딩 대신 실제 활성 채널을 표기한다(통합 flag ON 시 H0UNCNT0).
        val tickTrId = bridge.activeTickTrId()
        val subscriptions = mutableListOf<SubscriptionView>()
        realtimeDemands.forEach {
            subscriptions.add(
                SubscriptionView(
                    trId = tickTrId,
                    code = it.code,
                    owners = it.owners,
                    sinceMs = it.sinceMs,
                ),
            )
        }
        orderbookDemands.forEach {
            subscriptions.add(
                SubscriptionView(
                    trId = "H0UNASP0",
                    code = it.code,
                    owners = it.owners,
                    sinceMs = it.sinceMs,
                ),
            )
        }
        bridge.subscribedMarketStatusCodes().forEach { code ->
            subscriptions.add(
                SubscriptionView(
                    trId = "H0UNMKO0",
                    code = code,
                    owners = listOf("vi:held"),
                    sinceMs = null,
                ),
            )
        }

        return RealtimeStatusSnapshot(
            updatedAt = Instant.now().toString(),
            ws =
                WsView(
                    connected = ws.connected,
                    sessionCid = ws.sessionCid,
                    reconnects24h = ws.reconnects24h,
                    lastConnectedAtMs = ws.lastConnectedAtMs,
                ),
            slots =
                slot?.let {
                    SlotView(used = it.used, max = it.max, byTrId = it.byTrId, codesByTrId = it.codesByTrId)
                } ?: SlotView(used = 0, max = 0, byTrId = emptyMap(), codesByTrId = emptyMap()),
            subscriptions = subscriptions.sortedWith(compareBy({ it.trId }, { it.code })),
            executionNotice =
                ExecutionNoticeView(
                    subscribed = ws.executionSubscribed,
                    mode = ws.executionMode,
                    trId = ws.executionTrId,
                    userIdMasked = ws.executionUserIdMasked,
                ),
        )
    }
}

/**
 * Redis `admin:realtime-status` 직렬화 페이로드 (consumer → api 계약).
 *
 * api가 본 페이로드를 read → updatedAt 기반 stale 판정 + subscriptions code 종목명 join +
 * ACTIVE 전략/실시간 체결 구독 종목 1m 캔들 신선도(candleFreshness)를 덧붙여 관리자 응답으로 변환한다.
 */
data class RealtimeStatusSnapshot(
    val updatedAt: String,
    val ws: WsView = WsView(),
    val slots: SlotView = SlotView(),
    val subscriptions: List<SubscriptionView> = emptyList(),
    val executionNotice: ExecutionNoticeView = ExecutionNoticeView(),
)

data class WsView(
    val connected: Boolean = false,
    val sessionCid: String? = null,
    val reconnects24h: Int = 0,
    val lastConnectedAtMs: Long? = null,
)

data class SlotView(
    val used: Int = 0,
    val max: Int = 0,
    val byTrId: Map<String, Int> = emptyMap(),
    // tr_id별 점유 종목코드 목록 drill-down. default 필수 — 롤링 배포 호환(옛 consumer 페이로드엔 부재).
    val codesByTrId: Map<String, List<String>> = emptyMap(),
)

data class SubscriptionView(
    val trId: String,
    val code: String,
    val owners: List<String>,
    val sinceMs: Long?,
)

data class ExecutionNoticeView(
    val subscribed: Boolean = false,
    val mode: String = "MOCK",
    val trId: String = "H0STCNI9",
    val userIdMasked: String? = null,
)
