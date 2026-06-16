package kr.spdaewoon.magicjar.consumer.config

import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * T-O Phase 1 신규 주문 4 토픽 등록 (ADR 0068 §14-1 LOCK + §9-2 매트릭스).
 *
 * | topic                  | publisher                       | listener (Phase 2 합류) | retention |
 * |------------------------|---------------------------------|------------------------|-----------|
 * | order.requested.v1     | OrderRouterKafkaListener step 6 | backend-core STOMP     | 30d       |
 * | order.filled.v1        | KisExecutionWsListener          | backend-core STOMP     | 30d       |
 * | order.rejected.v1      | OrderRouterKafkaListener REJECT | backend-core STOMP     | 30d       |
 * | order.cancelled.v1     | KisExecutionWsListener ACPT=3   | backend-core STOMP     | 30d       |
 *
 * 모든 토픽 partition key = userId — 사용자별 ordering 보장.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Configuration
class OrderTopicConfig {
    companion object {
        private const val RETENTION_30D_MS = "2592000000" // 30d
        private const val RETENTION_7D_MS = "604800000" // 7d (DLT replay 검토 충분)
    }

    /**
     * 주문 접수 통보 (ADR 0068 §9-2 + §14-2 step 6).
     *
     * OrderRouterKafkaListener step 5 INSERT 직후 publish — ADR 0044 NotificationDispatcher 진입 +
     * Phase 2 backend-core STOMP `/topic/order.user.{userId}` REQUESTED fanout.
     *
     * 부하 예측 (§9-2): ~50 msg/day/사용자.
     */
    @Bean
    fun orderRequestedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Order.REQUESTED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_30D_MS)
            .build()

    /**
     * 체결 통보 (ADR 0068 §9-2 + §14-6 흐름 분기 CNTG_YN="2").
     *
     * KisExecutionWsListener publish — partial/full 양쪽 — T-RECON 합류점 + STOMP FILLED fanout.
     */
    @Bean
    fun orderFilledTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Order.FILLED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_30D_MS)
            .build()

    /**
     * 주문 거절 통보 (ADR 0068 §9-2 + §14-2 step 3·4·7).
     *
     * payload reason: REAL_GATE_DENIED:{cond} / KILLSWITCH_ON / {rt_cd}:{msg} / KIS_5XX 등.
     */
    @Bean
    fun orderRejectedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Order.REJECTED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_30D_MS)
            .build()

    /**
     * 주문 취소 통보 (ADR 0068 §9-2 + §14-6 ACPT_YN="3").
     *
     * KisExecutionWsListener 또는 Phase 2 OrderCancelService 성공 시 publish.
     */
    @Bean
    fun orderCancelledTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Order.CANCELLED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_30D_MS)
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0084 P0-4 — Kafka DLQ + 재시도 정책 + DLT 모니터링 (spec §2-4)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * order.intent.v1 DLT — DefaultErrorHandler + DeadLetterPublishingRecoverer 합류점.
     *
     * 외부 코드 리뷰 #7 흡수 — 무한 재시도 risk fix.
     * - 재시도 정책: FixedBackOff(1000ms, 3회) — 1초 간격 3회 재시도 후 DLT 발사
     * - 재시도 불가 예외 (DeserializationException 등): 즉시 DLT (재시도 0회 skip)
     * - DLT 수신 시 P0 알림 (OrderDltMonitorListener — 자본 path)
     *
     * partition key = userId (원본 record key 그대로 forward).
     * retention: 7d (운영 audit + DLT replay 검토)
     *
     * AI 호출 0 — T-O 트랙 정책 (ADR 0084 §2-8)
     */
    @Bean
    fun orderIntentDltTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Dlt.ORDER_INTENT)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_7D_MS)
            .build()

    /**
     * order.requested.v1 DLT — STOMP REQUESTED fanout consumer 또는 NotificationDispatcher 실패 시 격리.
     * 자본 path 직접 영향은 낮음(이미 INSERT 완료)이나 사용자 알림 누락 risk → DLT 모니터링 의무.
     */
    @Bean
    fun orderRequestedDltTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Dlt.ORDER_REQUESTED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_7D_MS)
            .build()

    /**
     * order.rejected.v1 DLT — NotificationDispatcher 또는 STOMP REJECTED fanout 실패 시 격리.
     * 자본 audit 깨짐 가능성 (사용자가 거절 사실 미인지) → DLT 모니터링 의무.
     */
    @Bean
    fun orderRejectedDltTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Dlt.ORDER_REJECTED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_7D_MS)
            .build()

    /**
     * order.filled.v1 DLT — NotificationDispatcher 또는 STOMP FILLED fanout 실패 시 격리.
     * 자본 잠재 risk (사용자가 체결 사실 미인지) → DLT 모니터링 의무 + P0 알림.
     */
    @Bean
    fun orderFilledDltTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Dlt.ORDER_FILLED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", RETENTION_7D_MS)
            .build()
}
