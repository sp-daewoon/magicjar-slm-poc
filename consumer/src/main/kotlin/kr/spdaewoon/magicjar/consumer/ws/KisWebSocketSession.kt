package kr.spdaewoon.magicjar.consumer.ws

import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS WebSocket 단일 연결 상태 관리 (ADR 0048 §2-4 정합).
 * thread-safe — AtomicReference·ConcurrentHashMap 사용.
 */
class KisWebSocketSession {
    /** 현재 구독 중인 종목코드 set (H0UNCNT0 통합 체결가) */
    val subscribedCodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 마지막 메시지 수신 시각 (heartbeat 무신호 감지용 — ADR 0048 §2-4-A) */
    val lastMessageAt: AtomicReference<Instant?> = AtomicReference(null)

    /** 연속 재연결 시도 횟수 (지수 백오프 계산용 — ADR 0048 §2-4-B) */
    val reconnectCount: AtomicInteger = AtomicInteger(0)

    /** WebSocket 연결 상태 */
    @Volatile
    var connected: Boolean = false

    /** graceful shutdown 요청 중 */
    @Volatile
    var shuttingDown: Boolean = false

    /**
     * 현재 살아있는 세션 식별자(관리자 스냅샷 sessionCid용) — onConnected 시 새 UUID, 끊김 시 null.
     * 진단 표시 전용이며 구독·재연결 로직과 무관(read-only status).
     */
    val sessionCid: AtomicReference<String?> = AtomicReference(null)

    /** 마지막 연결 수립 시각(epoch ms) — 관리자 스냅샷 lastConnectedAt용. 미연결 이력 시 null. */
    val lastConnectedAtMs: AtomicReference<Long?> = AtomicReference(null)

    fun onMessageReceived(instant: Instant) {
        lastMessageAt.set(instant)
    }

    fun onConnected() {
        connected = true
        reconnectCount.set(0)
        sessionCid.set(
            java.util.UUID
                .randomUUID()
                .toString(),
        )
        lastConnectedAtMs.set(System.currentTimeMillis())
        lastMessageAt.set(
            kotlinx.datetime.Clock.System
                .now(),
        )
    }

    fun onDisconnected() {
        connected = false
        sessionCid.set(null)
    }

    fun incrementReconnect(): Int = reconnectCount.incrementAndGet()

    fun reset() {
        subscribedCodes.clear()
        connected = false
        reconnectCount.set(0)
        lastMessageAt.set(null)
        sessionCid.set(null)
    }
}
