package kr.spdaewoon.magicjar.strategy.integrity

/**
 * ADR 0115 신규 ENV 3키의 **default fallback 값** 선언 — R1(순수 계층).
 *
 * ADR 0115 §1-1 자원 audit이 명시한 3개 신규 ENV 키의 기본값을 순수 상수로 둔다. 순수 함수는 이 default를
 * **주입받기만** 하며 ENV를 직접 읽지 않는다(읽기·바인딩은 R2 Spring `@ConfigurationProperties`/`@Value`).
 * R2가 ENV 미설정 시 이 값으로 fallback해 "보수적 현행 동작"을 보장한다(ADR 0115 §4 점진성).
 *
 * | ENV 키 | 상수 | default | 의미 |
 * |--------|------|---------|------|
 * | `magicjar.position.drift-auto-correct.enabled` | [AUTO_CORRECT_ENABLED] | true | drift 초과분 자동 내림 보정 토글. false면 현행 WARN-only. |
 * | `magicjar.autotrading.replay.freshness-threshold-seconds` | [REPLAY_FRESHNESS_THRESHOLD_SECONDS] | 120 | replay 신선도 임계초. age > 이값이면 복구 체결로 보고 TP skip. |
 * | `magicjar.notification.reject-auto-pause.threshold` | [REJECT_AUTO_PAUSE_THRESHOLD] | 10 | 동일 사유 거부 N회 누적 시 종목 자동 정지(P2-2 — R1은 default만 선언, 배선 R2). |
 *
 * ## 순수성 (LOCK)
 * - 컴파일 타임 상수만. Spring·ENV·IO 의존 0. R2가 이 값을 default로 ENV에 바인딩.
 * - ADR 0115 §1-1 명시값과 1:1 — 변경 시 ADR amend 동반.
 */
object DriftCorrectionDefaults {
    /**
     * `magicjar.position.drift-auto-correct.enabled` default — ADR 0115 §1-1.
     * 초과 drift 자동 내림 보정 활성. false 주입 시 [PositionDriftCorrection.evaluate]는 [DriftCorrectionResult.Disabled]
     * 반환 → R2가 현행 WARN-only로 fallback(canary 위험 시 즉시 보수적 복귀).
     */
    const val AUTO_CORRECT_ENABLED: Boolean = true

    /**
     * `magicjar.autotrading.replay.freshness-threshold-seconds` default — ADR 0115 §1-1·§P1-2.
     * `order.filled.v1` executedAt이 평가시각 대비 이 초 초과 과거면 복구된 체결(replay)로 보고 TP 발행 skip.
     * WS 정상 경로는 체결 즉시 발행이라 신선(< 120초). fillSource=REST_BACKFILL 1차 식별의 fail-safe 하한.
     */
    const val REPLAY_FRESHNESS_THRESHOLD_SECONDS: Long = 120

    /**
     * `magicjar.notification.reject-auto-pause.threshold` default — ADR 0115 §1-1·§P2-2.
     * 동일 사유 거부가 이 횟수 누적되면 해당 전략·종목 자동 정지 + 1회 알림(P2-2). R1은 default 선언만,
     * 자동 정지 상태 추적·배선은 R2(`NotificationDispatcher` 확장). 순수 계층에 미사용 — 계약 안정 위해 선언.
     */
    const val REJECT_AUTO_PAUSE_THRESHOLD: Int = 10
}
