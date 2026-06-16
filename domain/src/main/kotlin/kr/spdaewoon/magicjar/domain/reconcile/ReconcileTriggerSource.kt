package kr.spdaewoon.magicjar.domain.reconcile

/**
 * Reconcile 트리거 출처 (ADR 0070 §2-9 + §3-1 trigger_source 컬럼).
 *
 * - CRON: `@Scheduled` 시장 OPEN 5분 / CLOSED 1시간
 * - MANUAL: 사용자 REST `POST /api/risk/reconcile/{userId}` (Phase 2)
 * - API: 시스템 내부 호출 (예: 다른 트랙에서 검증 요청)
 */
enum class ReconcileTriggerSource {
    CRON,
    MANUAL,
    API,
}
