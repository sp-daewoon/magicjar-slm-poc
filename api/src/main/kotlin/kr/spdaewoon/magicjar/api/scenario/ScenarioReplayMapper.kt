package kr.spdaewoon.magicjar.api.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioEventJpaEntity
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioPositionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioRunJpaEntity

object ScenarioReplayMapper {
    fun toSummary(entity: ScenarioRunJpaEntity): ScenarioRunSummaryDto =
        ScenarioRunSummaryDto(
            id = requireNotNull(entity.id),
            userId = entity.userId,
            strategyId = entity.strategyId,
            strategyName = entity.strategyName,
            code = entity.code,
            interval = entity.interval,
            rangeFrom = entity.rangeFrom.toString(),
            rangeTo = entity.rangeTo.toString(),
            initialCash = entity.initialCash,
            fillModel = entity.fillModel,
            status = entity.status,
            eventCount = entity.eventCount,
            finalCash = entity.finalCash,
            abortReason = entity.abortReason,
            createdAt = entity.createdAt.toString(),
        )

    fun toEvent(
        entity: ScenarioEventJpaEntity,
        objectMapper: ObjectMapper,
    ): ScenarioEventDto =
        ScenarioEventDto(
            id = entity.id,
            sequenceNo = entity.sequenceNo,
            virtualTime = entity.virtualTime.toString(),
            type = entity.type,
            severity = entity.severity,
            title = entity.title,
            message = entity.message,
            strategyId = entity.strategyId,
            strategyName = entity.strategyName,
            code = entity.code,
            payload = parsePayload(entity.payloadJson, objectMapper),
        )

    fun toPosition(entity: ScenarioPositionJpaEntity): ScenarioPositionDto =
        ScenarioPositionDto(
            id = entity.id,
            code = entity.code,
            quantity = entity.quantity,
            averagePrice = entity.averagePrice,
            virtualTime = entity.virtualTime.toString(),
        )

    private fun parsePayload(
        value: String?,
        objectMapper: ObjectMapper,
    ): Map<String, Any?> =
        value
            ?.let {
                @Suppress("UNCHECKED_CAST")
                runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull()
            } ?: emptyMap()
}
