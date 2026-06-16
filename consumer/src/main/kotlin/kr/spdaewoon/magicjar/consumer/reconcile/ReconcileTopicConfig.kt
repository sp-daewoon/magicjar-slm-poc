package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * T-RECON Phase 1 신규 Kafka 토픽 3건 (ADR 0070 §4-1 LOCK).
 *
 * | topic                        | publisher                              | listener (Phase 1 + 2)              | retention |
 * |------------------------------|----------------------------------------|-------------------------------------|-----------|
 * | reconcile.run.v1             | strategy-engine BalanceReconcileJob    | api StompReconcileNotifier (Phase 2)| 7d        |
 * | reconcile.drift.v1           | strategy-engine BalanceReconcileJob    | strategy-engine KillSwitchAutoToggler (Phase 1) + api STOMP (Phase 2) + notify (T-NOTIFY) | 30d |
 * | killswitch.engaged.v1        | strategy-engine KillSwitchAutoToggler  | api STOMP (Phase 2) + notify (T-NOTIFY) | 30d   |
 *
 * 모든 토픽 partition key = userId — 사용자별 ordering 보장.
 *
 * AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2 fitness 검증)
 */
@Configuration
class ReconcileTopicConfig {
    companion object {
        private const val RETENTION_7D_MS = "604800000" // 7d
        private const val RETENTION_30D_MS = "2592000000" // 30d
    }

    /**
     * Reconcile run lifecycle 통보 (RUNNING·RECONCILED·DRIFT·DRIFT_PRICE·ERROR).
     *
     * Phase 1 strategy-engine BalanceReconcileJob → publish.
     * Phase 2 backend-core StompReconcileNotifier → /topic/recon.user.{userId} fanout.
     *
     * 부하 예측: 활성 user N명 × 5분 1회 = 분당 N/5 = ~12 msg/h/user.
     */
    @Bean
    fun reconcileRunTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Reconcile.RUN)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_7D_MS)
            .build()

    /**
     * Drift 검출 통보 (자본 사고 직결 — KillSwitch auto-ON 트리거).
     *
     * Phase 1 KillSwitchAutoToggler listen 후 KillSwitch engage + killswitch.engaged.v1 publish.
     * Phase 2 STOMP fanout + T-NOTIFY Slack P0 push.
     *
     * 부하 예측: drift 발생률 매우 낮음 — 정상 운영 시 0건/일 가정.
     */
    @Bean
    fun reconcileDriftTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Reconcile.DRIFT)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_30D_MS)
            .build()

    /**
     * KillSwitch auto-ON 통보 (ADR 0043 baseline + ADR 0070 §2-11 step 6).
     *
     * Phase 1 KillSwitchAutoToggler → publish (drift listener 내부 trigger).
     * Phase 2 STOMP fanout + T-NOTIFY Slack P0 push.
     *
     * triggeredBy: AUTO (drift) / USER (수동) — 본 Phase 1은 AUTO만 publish.
     */
    @Bean
    fun killSwitchEngagedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Risk.KILLSWITCH_ENGAGED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_30D_MS)
            .build()
}
