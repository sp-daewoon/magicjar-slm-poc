package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.strategy.ResolvedEntryPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 미체결 진입(BUY LIMIT) 취소 워커 — ADR 0114 §3-2 (R2-C2a).
 *
 * ## 정책 (ADR 0114 §3-2 LOCK)
 * LIMIT 진입 주문이 `entryTimeoutSec`(전략별 resolved) 경과해도 미체결이면 **취소**한다.
 * - **시장가 전환 절대 금지**(슬리피지 캡 자기 부정 회피).
 * - **재시도 0**(추격 금지 — 취소만 하고 재발행하지 않는다. 재진입은 다음 시그널이 결정).
 *
 * ## entryTimeoutSec 산정 (전역 default — R2 scope · §9 U-4)
 * 본 워커는 **전역 default 타임아웃**(env `magicjar.order.entry.timeout-sec`, default 15초)을 취소 기준으로 쓴다.
 * 전략별 override(§5)는 `Enter` 결정 시점에 [kr.spdaewoon.magicjar.consumer.risk.EntryOrderDecision.Enter.entryTimeoutSec]로
 * 산정되나, order_request 행에 timeout 을 비정규화 저장하지 않으며(스키마 add-only 최소화) 워커가 strategy 를 재조회하지도 않는다
 * (interval 정보가 행에 없어 lookup 부정확 — ADR §9 U-4가 R2 트리거를 위임한 범위. 행별 정밀화는 post-MVP).
 *
 * ## ACK 미확정 가드 (Codex 흡수)
 * KIS 취소는 `kisOrderNo`(ODNO) + `kisFwdgOrgno`(KRX_FWDG_ORD_ORGNO)가 둘 다 있어야 가능하다(KIS order-rvsecncl 스펙).
 * REQUESTED 상태(ACK 미수신)거나 ACK 정보가 비면 **취소 불가 → 보류(다음 tick 재평가)**한다.
 * 단번에 REJECTED/CANCELLED 마킹하면 KIS가 실제 접수했을 수 있어 자본 audit 불일치 risk(OrderReconciliationWorker 철학 정합).
 *
 * ## 이미 체결 무시
 * lookup 자체가 `status IN (REQUESTED, ACK, PARTIALLY_FILLED)` BUY LIMIT만 반환하므로 FILLED/CANCELLED는 후보에서 빠진다
 * (체결통보 WS가 먼저 FILLED 마킹하면 자연히 제외 — race-safe).
 *
 * ## cron (fixedDelay 5초)
 * application.yml `magicjar.order.entry.cancel-worker.enabled` toggle(default true). 비활성 시 no-op.
 * 평상시 BUY LIMIT 미체결 0건이면 즉시 종료 → 부담 0.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4)
 */
@Component
class EntryTimeoutCancelWorker(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val kisOrderPort: KisOrderPort,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.order.entry.cancel-worker.enabled:true}") private val enabled: Boolean = true,
    @Value("\${magicjar.order.entry.timeout-sec:15}") private val defaultTimeoutSec: Int = 15,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(EntryTimeoutCancelWorker::class.java)

    companion object {
        /** 한 tick 처리 row 상한 — KIS rate limit 보호(ADR 0037). */
        internal const val BATCH_LIMIT: Int = 50
    }

    /**
     * 5초 cron — fixedDelay. 후보(BUY LIMIT 미체결)는 1차로 최소 타임아웃 cutoff로 거른 뒤 행별 정확 타임아웃을 재판정한다.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    fun cancelTick() {
        if (!enabled) return
        val repo = orderRequestRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.warn("EntryTimeoutCancelWorker — OrderRequestRepository 미합류 — skip")
            return
        }

        val now = Instant.now(clock)
        // 1차 cutoff = now - 최소 타임아웃(전역 default). 행별 전략 override 가 더 길면 tick 에서 재판정으로 보류한다.
        val cutoff = now.minus(Duration.ofSeconds(effectiveDefaultTimeoutSec().toLong()))
        val candidates =
            try {
                repo.findOpenBuyLimitBefore(cutoff, BATCH_LIMIT)
            } catch (e: Exception) {
                log.error("EntryTimeoutCancelWorker 후보 lookup 실패: err={}", e.message, e)
                return
            }
        if (candidates.isEmpty()) {
            log.trace("EntryTimeoutCancelWorker — 미체결 BUY LIMIT 0건 (tick skip)")
            return
        }

        log.debug("EntryTimeoutCancelWorker tick — candidates={} cutoff={}", candidates.size, cutoff)
        for (row in candidates) {
            try {
                processRow(repo, row, now)
            } catch (e: Exception) {
                log.error(
                    "EntryTimeoutCancelWorker.processRow 실패 (다음 tick 재시도): orderId={} userId={} err={}",
                    row.id,
                    row.userId,
                    e.message,
                    e,
                )
            }
        }
    }

    internal fun processRow(
        repo: OrderRequestRepository,
        row: OrderRequest,
        now: Instant,
    ) {
        val orderId = row.id ?: return
        val timeoutSec = effectiveDefaultTimeoutSec()

        // ACK 미확정 가드(Codex 흡수) — ODNO/FWDG 부재면 취소 불가. 보류(다음 tick 재평가).
        if (row.kisOrderNo.isNullOrBlank() || row.kisFwdgOrgno.isNullOrBlank()) {
            log.info(
                "EntryTimeoutCancelWorker ACK 미확정 — 취소 보류(다음 tick): orderId={} userId={} status={} kisOrderNo={} requestedAt={}",
                orderId,
                row.userId,
                row.status,
                row.kisOrderNo,
                row.requestedAt,
            )
            return
        }

        log.info(
            "EntryTimeoutCancelWorker 미체결 진입 취소 시도: orderId={} userId={} code={} timeoutSec={} elapsed={}s",
            orderId,
            row.userId,
            row.code,
            timeoutSec,
            Duration.between(row.requestedAt, now).seconds,
        )

        when (val result = kisOrderPort.cancel(row, row.mode)) {
            is OrderResult.Right -> {
                repo.markCompleted(orderId, now, OrderStatus.CANCELLED)
                writeAudit(
                    orderId = orderId,
                    userId = row.userId,
                    eventType = OrderAuditEventType.KIS_CANCEL_ACK,
                    payload =
                        mapOf(
                            "trigger" to "ENTRY_TIMEOUT",
                            "source" to "ENTRY_TIMEOUT_CANCEL_WORKER",
                            "timeoutSec" to timeoutSec,
                            "code" to row.code,
                            "limitPrice" to row.limitPrice?.toPlainString(),
                            "kisOrderNo" to row.kisOrderNo,
                        ),
                )
                log.info("EntryTimeoutCancelWorker 취소 완료(CANCELLED): orderId={} userId={}", orderId, row.userId)
            }
            is OrderResult.Left -> {
                // 취소 실패 — DB status 유지(거짓 CANCELLED 금지, 자본 path 1순위). 다음 tick 재시도. 시장가 전환 0.
                writeAudit(
                    orderId = orderId,
                    userId = row.userId,
                    eventType = OrderAuditEventType.KIS_CANCEL_REJECT,
                    payload =
                        mapOf(
                            "trigger" to "ENTRY_TIMEOUT",
                            "source" to "ENTRY_TIMEOUT_CANCEL_WORKER",
                            "failure" to result.value.toString(),
                            "kisOrderNo" to row.kisOrderNo,
                        ),
                )
                log.warn(
                    "EntryTimeoutCancelWorker 취소 실패(상태 유지·다음 tick 재시도): orderId={} userId={} reason={}",
                    orderId,
                    row.userId,
                    result.value,
                )
            }
        }
    }

    private fun effectiveDefaultTimeoutSec(): Int = defaultTimeoutSec.takeIf { it > 0 } ?: ResolvedEntryPolicy.DEFAULTS.entryTimeoutSec

    private fun writeAudit(
        orderId: Long,
        userId: String,
        eventType: OrderAuditEventType,
        payload: Map<String, Any?>,
    ) {
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        if (auditRepo == null) {
            log.warn(
                "EntryTimeoutCancelWorker writeAudit skip — OrderAuditEventRepository 미합류: orderId={} eventType={}",
                orderId,
                eventType,
            )
            return
        }
        runCatching {
            auditRepo.save(
                OrderAuditEvent(
                    orderId = orderId,
                    userId = userId,
                    eventType = eventType,
                    payloadJson = objectMapper.writeValueAsString(payload),
                    createdAt = Instant.now(clock),
                ),
            )
        }.onFailure { e ->
            log.error("EntryTimeoutCancelWorker writeAudit 실패: orderId={} eventType={} err={}", orderId, eventType, e.message)
        }
    }
}
