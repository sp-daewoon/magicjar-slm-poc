package kr.spdaewoon.magicjar.persistence.shared.metric

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * persistence-shared Micrometer 메트릭 단일 노출 어댑터 (ADR 0094 P0-OPS-6 + ADR 0095 SLO 측정 source).
 *
 * ### 책임
 * - **SystemEvent 누적 Counter** — `magicjar.system.events` (tag: category·severity·action)
 *   ADR 0095 §2-1 S-2(WS_DISCONNECTED) · S-4(KIS_RATELIMIT) 측정 source.
 *   Prometheus query: `sum(increase(magicjar_system_events_total{event_type="WS_DISCONNECTED"}[5m]))`
 * - **Outbox drain lag Gauge** — `magicjar.outbox.drain.lag.seconds` (tag: outbox=order|notification)
 *   가장 오래된 PENDING row의 createdAt → now 차이 (초). 30s 초과 시 ADR 0095 S 관측 위반 trigger.
 *   각 OutboxRepositoryAdapter가 주기적으로 [updateDrainLag] 호출 의무.
 * - **signal→order latency Timer** — `magicjar.signal.to.order.latency`
 *   ADR 0095 §2-1 S-1 측정 source. histogram 분포 export — P50·P95·P99 분리 계산 가능.
 *   Prometheus query: `histogram_quantile(0.95, sum by (le) (rate(magicjar_signal_to_order_latency_seconds_bucket[5m])))`
 *   `OrderIntentKafkaProducer.publish` 시점에 [recordSignalToOrderLatency] 호출 wiring.
 *
 * ### Micrometer 자동 노출 항목 (본 클래스 wiring 0 — 참고)
 * - **HikariCP** — `hikaricp_connections_active`·`hikaricp_connections_idle`·`hikaricp_connections_pending` (Spring Boot 자동)
 * - **JPA query latency** — `hibernate.query.execution` (spring.jpa.properties.hibernate.generate_statistics=true 시 자동)
 * - **HTTP server requests** — `http.server.requests` (Spring Actuator default)
 * - **Kafka topic** — `kafka_consumer_records_consumed_total{topic}` (Spring Kafka default)
 *   ADR 0095 §2-1 S-5(killswitch.engaged.v1) 측정은 이 자동 metric reuse.
 *
 * ### 헌법 §6 (ADR 0051) 정합
 * Repository interface 내부 body 가진 fun 0건 — 본 helper는 별도 @Component 위치.
 *
 * ### graceful 정책
 * - MeterRegistry 부재 (test fixture 등) 시 NoopMeterRegistry 자동 fallback — Spring Boot Actuator 미존재 모듈에서도 안전.
 * - metric record 실패는 caller logic을 절대 죽이지 않음 — runCatching wrap + log.trace.
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Component
class PersistenceMetrics(
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(PersistenceMetrics::class.java)

    companion object {
        const val METRIC_SYSTEM_EVENTS = "magicjar.system.events"
        const val METRIC_OUTBOX_DRAIN_LAG = "magicjar.outbox.drain.lag.seconds"
        const val METRIC_SIGNAL_TO_ORDER_LATENCY = "magicjar.signal.to.order.latency"

        const val TAG_OUTBOX_ORDER = "order"
        const val TAG_OUTBOX_NOTIFICATION = "notification"
    }

    // Outbox drain lag — Gauge AtomicLong 등록 (Adapter가 주기적 update)
    private val outboxDrainLagSeconds: MutableMap<String, AtomicLong> = mutableMapOf()

    // signal→order Timer — registry 1회 등록 후 reuse
    private val signalToOrderTimer: Timer by lazy {
        Timer
            .builder(METRIC_SIGNAL_TO_ORDER_LATENCY)
            .description("Signal emit ~ order intent publish latency (ADR 0095 S-1)")
            .publishPercentileHistogram()
            .register(meterRegistry)
    }

    /**
     * SystemEvent emit 시점 Counter increment — ADR 0084 SystemEventLoggerAdapter wiring.
     * tag: category·severity·action·event_type (action을 event_type alias로 동시 등록 — ADR 0095 query 호환).
     *
     * 실패 graceful — runCatching wrap.
     */
    fun incrementSystemEvent(event: SystemEvent) {
        runCatching {
            counter(event).increment()
        }.onFailure { e ->
            log.trace("incrementSystemEvent skip: action={} err={}", event.action, e.message)
        }
    }

    /**
     * Outbox drain lag Gauge 갱신 — 가장 오래된 PENDING row의 createdAt → now (seconds).
     * PENDING 0건 시 0 갱신.
     *
     * @param outboxType "order" | "notification"
     * @param lagSeconds 가장 오래된 PENDING age (seconds). null/0 = drain 양호.
     */
    fun updateOutboxDrainLag(
        outboxType: String,
        lagSeconds: Long,
    ) {
        runCatching {
            outboxDrainLagSeconds
                .getOrPut(outboxType) {
                    val holder = AtomicLong(0)
                    meterRegistry.gauge(
                        METRIC_OUTBOX_DRAIN_LAG,
                        Tags.of("outbox", outboxType),
                        holder,
                    ) { it.get().toDouble() }
                    holder
                }.set(lagSeconds.coerceAtLeast(0))
        }.onFailure { e ->
            log.trace("updateOutboxDrainLag skip: outbox={} lag={} err={}", outboxType, lagSeconds, e.message)
        }
    }

    /**
     * signal emit (ts) → order intent publish 시점 latency 측정 (ADR 0095 S-1).
     *
     * @param signalEmittedAtEpochSeconds Signal.ts.epochSeconds — 시그널 도메인 생성 시점
     * @param orderIntentEmittedAtEpochSeconds OrderIntent publish 시점 (== now epochSecond)
     */
    fun recordSignalToOrderLatency(
        signalEmittedAtEpochSeconds: Long,
        orderIntentEmittedAtEpochSeconds: Long,
    ) {
        runCatching {
            val deltaSeconds = (orderIntentEmittedAtEpochSeconds - signalEmittedAtEpochSeconds).coerceAtLeast(0)
            signalToOrderTimer.record(Duration.ofSeconds(deltaSeconds))
        }.onFailure { e ->
            log.trace("recordSignalToOrderLatency skip: err={}", e.message)
        }
    }

    // --- private helper ---

    /**
     * (category, severity, action) 조합별 Counter — 헌법 §6 정합 private helper.
     * 동일 tag set은 Micrometer 내부에서 캐시되므로 매번 builder.register 호출 안전.
     * event_type alias는 ADR 0095 §2-1 Prometheus query 호환 의무.
     */
    private fun counter(event: SystemEvent): Counter =
        Counter
            .builder(METRIC_SYSTEM_EVENTS)
            .description("SystemEvent emit count by category/severity/action (ADR 0095 S-2/S-4 source)")
            .tags(
                Tags.of(
                    "category",
                    event.category.name,
                    "severity",
                    event.severity.name,
                    "action",
                    event.action,
                    "event_type",
                    event.action,
                ),
            ).register(meterRegistry)
}
