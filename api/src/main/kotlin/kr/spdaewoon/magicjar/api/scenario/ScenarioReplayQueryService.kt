package kr.spdaewoon.magicjar.api.scenario

import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioEventJpaEntity
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioEventJpaRepository
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioPositionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioPositionJpaRepository
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioRunJpaEntity
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioRunJpaRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class ScenarioReplayQueryService(
    private val runRepository: ScenarioRunJpaRepository,
    private val eventRepository: ScenarioEventJpaRepository,
    private val positionRepository: ScenarioPositionJpaRepository,
) {
    fun listRuns(
        userId: String,
        page: Int,
        size: Int,
    ): Page<ScenarioRunJpaEntity> =
        runRepository.findAllByUserId(
            userId,
            PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "createdAt")),
        )

    fun getRun(
        id: Long,
        userId: String,
    ): ScenarioRunJpaEntity =
        runRepository.findByIdAndUserId(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "시나리오 리플레이 run을 찾을 수 없습니다: id=$id")

    fun listEvents(
        runId: Long,
        userId: String,
        page: Int,
        size: Int,
    ): Page<ScenarioEventJpaEntity> {
        verifyOwnership(runId, userId)
        return eventRepository.findAllByRunId(
            runId,
            PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 500), Sort.by(Sort.Direction.ASC, "sequenceNo")),
        )
    }

    fun listPositions(
        runId: Long,
        userId: String,
    ): List<ScenarioPositionJpaEntity> {
        verifyOwnership(runId, userId)
        return positionRepository.findAllByRunIdOrderByCodeAsc(runId)
    }

    fun deleteRun(
        runId: Long,
        userId: String,
    ) {
        val run = getRun(runId, userId)
        runRepository.delete(run)
    }

    private fun verifyOwnership(
        runId: Long,
        userId: String,
    ) {
        if (!runRepository.existsByIdAndUserId(runId, userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "시나리오 리플레이 run을 찾을 수 없습니다: id=$runId")
        }
    }
}
