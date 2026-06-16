package kr.spdaewoon.magicjar.api.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioEventJpaEntity
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioEventJpaRepository
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioExecutionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioExecutionJpaRepository
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioOrderJpaEntity
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioOrderJpaRepository
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioPositionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioPositionJpaRepository
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioRunJpaEntity
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioRunJpaRepository
import kr.spdaewoon.magicjar.domain.scenario.ScenarioEvent
import kr.spdaewoon.magicjar.domain.scenario.ScenarioEventType
import kr.spdaewoon.magicjar.domain.scenario.ScenarioReplayRequest
import kr.spdaewoon.magicjar.domain.scenario.ScenarioReplayResult
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

@Component
class ScenarioReplayPersister(
    private val runRepository: ScenarioRunJpaRepository,
    private val eventRepository: ScenarioEventJpaRepository,
    private val orderRepository: ScenarioOrderJpaRepository,
    private val executionRepository: ScenarioExecutionJpaRepository,
    private val positionRepository: ScenarioPositionJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun persist(result: ScenarioReplayResult): ScenarioRunJpaEntity {
        val run = runRepository.save(buildRun(result))
        val events = persistEvents(run, result.events)
        persistOrders(run, result.events)
        persistExecutions(run, result.events)
        persistPositions(run, result)
        return run.copyEventCount(events.size)
    }

    private fun buildRun(result: ScenarioReplayResult): ScenarioRunJpaEntity {
        val request = result.request
        return when (result) {
            is ScenarioReplayResult.Success ->
                ScenarioRunJpaEntity(
                    userId = request.userId,
                    strategyId = request.strategyId,
                    strategyName = request.strategyName,
                    code = request.code,
                    interval = request.interval,
                    rangeFrom = request.from.toJavaInstant(),
                    rangeTo = request.to.toJavaInstant(),
                    initialCash = request.initialCash,
                    fillModel = request.fillModel.name,
                    status = "SUCCESS",
                    eventCount = result.events.size,
                    finalCash = result.ledger.cash,
                    abortReason = null,
                    detailJson = detailJson(request, result),
                    startedAt = result.startedAt.toJavaInstant(),
                    completedAt = result.completedAt.toJavaInstant(),
                    updatedAt = Instant.now(),
                )

            is ScenarioReplayResult.Aborted ->
                ScenarioRunJpaEntity(
                    userId = request.userId,
                    strategyId = request.strategyId,
                    strategyName = request.strategyName,
                    code = request.code,
                    interval = request.interval,
                    rangeFrom = request.from.toJavaInstant(),
                    rangeTo = request.to.toJavaInstant(),
                    initialCash = request.initialCash,
                    fillModel = request.fillModel.name,
                    status = "ABORTED",
                    eventCount = result.events.size,
                    finalCash = null,
                    abortReason = result.reasonCode,
                    detailJson = detailJson(request, result),
                    startedAt = result.startedAt.toJavaInstant(),
                    completedAt = result.completedAt.toJavaInstant(),
                    updatedAt = Instant.now(),
                )
        }
    }

    private fun persistEvents(
        run: ScenarioRunJpaEntity,
        events: List<ScenarioEvent>,
    ): List<ScenarioEventJpaEntity> =
        eventRepository
            .saveAll(
                events.mapIndexed { index, event ->
                    ScenarioEventJpaEntity(
                        run = run,
                        sequenceNo = index,
                        virtualTime = event.virtualTime.toJavaInstant(),
                        type = event.type.name,
                        severity = event.severity.name,
                        title = event.title,
                        message = event.message,
                        strategyId = event.strategyId,
                        strategyName = event.strategyName,
                        code = event.code,
                        payloadJson = payloadJson(event.payload),
                    )
                },
            ).toList()

    private fun persistOrders(
        run: ScenarioRunJpaEntity,
        events: List<ScenarioEvent>,
    ) {
        val entities =
            events
                .filter { it.type in ORDER_EVENT_TYPES }
                .map { event ->
                    ScenarioOrderJpaEntity(
                        run = run,
                        virtualTime = event.virtualTime.toJavaInstant(),
                        side = event.payload.stringValue("side"),
                        quantity = event.payload.longValue("quantity"),
                        priceType = event.payload.stringValue("priceType"),
                        limitPrice = event.payload.bigDecimalValue("limitPrice"),
                        status = orderStatus(event.type),
                        reason = event.message,
                        payloadJson = payloadJson(event.payload),
                    )
                }
        if (entities.isNotEmpty()) {
            orderRepository.saveAll(entities)
        }
    }

    private fun persistExecutions(
        run: ScenarioRunJpaEntity,
        events: List<ScenarioEvent>,
    ) {
        val entities =
            events
                .filter { it.type == ScenarioEventType.ORDER_FILLED }
                .mapNotNull { event ->
                    val quantity = event.payload.longValue("quantity") ?: return@mapNotNull null
                    val fillPrice = event.payload.bigDecimalValue("fillPrice") ?: return@mapNotNull null
                    ScenarioExecutionJpaEntity(
                        run = run,
                        virtualTime = event.virtualTime.toJavaInstant(),
                        side = event.payload.stringValue("side"),
                        quantity = quantity,
                        fillPrice = fillPrice,
                        payloadJson = payloadJson(event.payload),
                    )
                }
        if (entities.isNotEmpty()) {
            executionRepository.saveAll(entities)
        }
    }

    private fun persistPositions(
        run: ScenarioRunJpaEntity,
        result: ScenarioReplayResult,
    ) {
        if (result !is ScenarioReplayResult.Success) return
        val virtualTime =
            result.events
                .lastOrNull()
                ?.virtualTime
                ?.toJavaInstant()
                ?: result.request.to.toJavaInstant()
        val entities =
            result.ledger.positions.map { position ->
                ScenarioPositionJpaEntity(
                    run = run,
                    code = position.code,
                    quantity = position.quantity,
                    averagePrice = position.averagePrice,
                    virtualTime = virtualTime,
                )
            }
        if (entities.isNotEmpty()) {
            positionRepository.saveAll(entities)
        }
    }

    private fun ScenarioRunJpaEntity.copyEventCount(eventCount: Int): ScenarioRunJpaEntity =
        if (this.eventCount == eventCount) {
            this
        } else {
            runRepository.save(
                ScenarioRunJpaEntity(
                    id = id,
                    userId = userId,
                    strategyId = strategyId,
                    strategyName = strategyName,
                    code = code,
                    interval = interval,
                    rangeFrom = rangeFrom,
                    rangeTo = rangeTo,
                    initialCash = initialCash,
                    fillModel = fillModel,
                    status = status,
                    eventCount = eventCount,
                    finalCash = finalCash,
                    abortReason = abortReason,
                    detailJson = detailJson,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    createdAt = createdAt,
                    updatedAt = Instant.now(),
                ),
            )
        }

    private fun orderStatus(type: ScenarioEventType): String =
        when (type) {
            ScenarioEventType.ORDER_REQUESTED -> "REQUESTED"
            ScenarioEventType.ORDER_HELD -> "HELD"
            ScenarioEventType.ORDER_REJECTED -> "REJECTED"
            ScenarioEventType.ORDER_FILLED -> "FILLED"
            else -> error("unsupported scenario order event type: $type")
        }

    private fun detailJson(
        request: ScenarioReplayRequest,
        result: ScenarioReplayResult,
    ): String =
        objectMapper.writeValueAsString(
            mapOf(
                "code" to request.code,
                "interval" to request.interval,
                "faultCount" to request.faults.size,
                "resultType" to result::class.simpleName,
            ),
        )

    private fun payloadJson(payload: Map<String, Any?>): String? =
        payload.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) }

    private fun Map<String, Any?>.stringValue(key: String): String? = this[key]?.toString()

    private fun Map<String, Any?>.longValue(key: String): Long? =
        when (val value = this[key]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }

    private fun Map<String, Any?>.bigDecimalValue(key: String): BigDecimal? =
        when (val value = this[key]) {
            is BigDecimal -> value
            is Number -> BigDecimal(value.toString())
            is String -> value.toBigDecimalOrNull()
            else -> null
        }

    companion object {
        private val ORDER_EVENT_TYPES =
            setOf(
                ScenarioEventType.ORDER_REQUESTED,
                ScenarioEventType.ORDER_HELD,
                ScenarioEventType.ORDER_REJECTED,
                ScenarioEventType.ORDER_FILLED,
            )
    }
}
