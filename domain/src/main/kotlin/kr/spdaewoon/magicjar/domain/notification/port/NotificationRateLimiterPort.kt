package kr.spdaewoon.magicjar.domain.notification.port

/**
 * 알림 rate limit 포트 — Redis ZSET sliding window (ADR 0070 §2-4 + spec §5 LOCK).
 *
 * Redis 키 패턴: `notification:ratelimit:{userId}:{topicHash}` (ZSET)  TTL 60m
 * - P1 슬라이딩 윈도 카운터 — 윈도 내 누적 건수 ≥ threshold 시 1회만 발송
 *
 * 헌법 §6 (ADR 0051) — abstract fun만.
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
interface NotificationRateLimiterPort {
    /**
     * P1 슬라이딩 윈도 rate limit 체크.
     *
     * @param userId     사용자 ID
     * @param topicHash  토픽 식별자 (예: "order.filled", "order.rejected")
     * @param threshold  윈도 내 발송 허용 임계 건수 (이 값 이상 누적 시 true 반환)
     * @param windowMin  슬라이딩 윈도 분 단위 (기본 60분)
     * @return true = 발송 허용 (윈도 내 첫 도달 또는 임계 초과 도달), false = 아직 임계 미달
     */
    fun shouldEmit(
        userId: String,
        topicHash: String,
        threshold: Int,
        windowMin: Int = 60,
    ): Boolean

    /**
     * 동일 키에 대해 윈도우당 1회만 발송한다.
     *
     * 반복되는 보호 차단(POSITION_LIMIT 등)은 risk_event DB에는 모두 남기되,
     * Slack은 첫 1회 이후 윈도우가 만료될 때까지 묶어 알림 피로도를 낮춘다.
     */
    fun shouldEmitOncePerWindow(
        userId: String,
        topicHash: String,
        windowMin: Int,
    ): Boolean
}
