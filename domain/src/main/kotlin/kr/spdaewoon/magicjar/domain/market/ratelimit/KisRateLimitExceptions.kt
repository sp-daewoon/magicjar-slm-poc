package kr.spdaewoon.magicjar.domain.market.ratelimit

/**
 * KIS Rate Limit 위반 sealed exception 계층 (spec PR #564 §2-2 LOCK).
 *
 * sealed 채택 사유:
 *   - 호출자 when 분기 exhaustive 강제 (신규 시그널 추가 시 빌드 게이트)
 *   - 도메인 외부 임의 상속 차단 — Rate Limit 시그널은 본 sealed 트리만 권위
 *
 * 분기 룰 (ADR 0014 — mock 합성 0):
 *   - KisRateLimitTimeoutException: acquire 대기 초과 → 사용자 path는 명시 에러, backfill은 caller skip
 *   - KisCircuitOpenException: Circuit Breaker OPEN (Phase 3 합류) → 사용자 path도 즉시 503
 */
sealed class KisRateLimitException(
    message: String,
) : RuntimeException(message)

/**
 * acquire(timeoutMillis) 대기 초과 — bucket 토큰 부족 + timeout 내 refill 0.
 *
 * 처리 권장:
 *   - USER_PATH: caller가 catch + 명시 에러 응답 (mock 합성 0 — ADR 0014)
 *   - INGESTION / BACKFILL: caller가 catch + skip 또는 다음 cron 대기 + log WARN
 */
class KisRateLimitTimeoutException(
    val priority: KisCallPriority,
    val timeoutMillis: Long,
) : KisRateLimitException("KIS rate limit acquire timeout: priority=$priority timeoutMillis=$timeoutMillis")

/**
 * Circuit Breaker OPEN 상태 — 5분 윈도 50% 이상 EGW00201/429 (Phase 3 합류).
 *
 * Phase 1은 본 예외 throw 0 (Circuit Breaker 통계 미구현).
 * Phase 3에서 adapter가 통계 임계 도달 시 throw + Slack alert P1 발행.
 *
 * 처리 권장:
 *   - 모든 priority: caller가 catch + 즉시 503 응답 (5분 후 HALF_OPEN 자동 전환)
 */
class KisCircuitOpenException(
    val openUntilEpochMillis: Long,
) : KisRateLimitException("KIS rate limit circuit OPEN until=$openUntilEpochMillis")
