package kr.spdaewoon.magicjar.api.persistence.screener

import kr.spdaewoon.magicjar.api.controller.screener.ScreeningRunDetailDto
import kr.spdaewoon.magicjar.api.controller.screener.ScreeningRunDto
import kr.spdaewoon.magicjar.domain.screener.ScreenerMatch
import kr.spdaewoon.magicjar.domain.screener.ScreenerRunResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * screening_run 영속 어댑터 (ADR 0071 §3 D-2 + spec §2-3).
 *
 * 옵션 영속 — env toggle `MAGICJAR_SCREENER_PERSIST=true` 시 INSERT.
 * persist property가 false면 저장·조회 모두 skip (no-op).
 *
 * 헌법 §6: Repository interface body fun 0건 LOCK.
 * 모든 helper 메서드는 본 클래스 private fun으로 작성.
 *
 * AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5).
 */
@Component
class ScreeningRunRepositoryAdapter(
    private val screeningRunJpaRepository: ScreeningRunJpaRepository,
    @Value("\${magicjar.screener.persist:false}") private val persistEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(ScreeningRunRepositoryAdapter::class.java)

    /**
     * screening 실행 결과 영속 저장.
     * persistEnabled=false 시 no-op.
     *
     * @param result 평가 결과
     * @param userId 사용자 ID
     * @param strategyId 전략 ID
     * @param universeFilterHash universe 필터 해시
     * @param dslHash DSL 해시
     * @return 저장된 run ID (persistEnabled=false 시 null)
     */
    @Transactional
    fun persist(
        result: ScreenerRunResult,
        userId: Long,
        strategyId: Long,
        universeFilterHash: String,
        dslHash: String,
    ): Long? {
        if (!persistEnabled) return null

        val now = Instant.now()
        val run =
            ScreeningRunJpaEntity(
                userId = userId,
                strategyId = strategyId,
                universeFilterHash = universeFilterHash,
                dslHash = dslHash,
                startedAt = now.minusMillis(result.elapsedMs),
                finishedAt = now,
                universeSize = result.universeSize,
                evaluatedCount = result.evaluatedCount,
                matchedCount = result.matchedCount,
                skippedCount = result.skippedCount,
                status = "COMPLETED",
                triggerSource = "API",
            )

        val saved = screeningRunJpaRepository.save(run)

        // screening_match 저장
        for (match in result.matches) {
            val matchEntity = buildMatchEntity(saved, match)
            saved.matches.add(matchEntity)
        }

        // cascade save (matches)
        screeningRunJpaRepository.save(saved)

        log.info(
            "[screener] persist: runId={} userId={} strategyId={} matchedCount={}",
            saved.id,
            userId,
            strategyId,
            result.matchedCount,
        )
        return saved.id
    }

    /**
     * 사용자별 screening 실행 이력 조회 (페이징, 최신순).
     * persistEnabled=false 시 empty page 반환.
     */
    @Transactional(readOnly = true)
    fun listRuns(
        userId: Long,
        page: Int,
        size: Int,
    ): Page<ScreeningRunDto> {
        if (!persistEnabled) {
            return org.springframework.data.domain
                .PageImpl(emptyList())
        }
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"))
        return screeningRunJpaRepository
            .findByUserIdOrderByStartedAtDesc(userId, pageable)
            .map { toRunDto(it) }
    }

    /**
     * screening 단건 상세 조회.
     * persistEnabled=false 또는 userId mismatch 시 null 반환.
     */
    @Transactional(readOnly = true)
    fun findDetail(
        id: Long,
        userId: Long,
    ): ScreeningRunDetailDto? {
        if (!persistEnabled) return null
        val run = screeningRunJpaRepository.findByIdAndUserId(id, userId) ?: return null
        return toDetailDto(run)
    }

    // ── private helpers ──────────────────────────────────────────────────

    private fun buildMatchEntity(
        run: ScreeningRunJpaEntity,
        match: ScreenerMatch,
    ): ScreeningMatchJpaEntity =
        ScreeningMatchJpaEntity(
            screeningRun = run,
            instrumentCode = match.instrumentCode,
            rank = match.rank,
            score = match.score,
            scoreComponents =
                match.breakdown
                    .toComponentMap()
                    .mapValues { (_, v) -> v as Any },
            reason = match.reason,
        )

    private fun toRunDto(run: ScreeningRunJpaEntity): ScreeningRunDto =
        ScreeningRunDto(
            id = run.id!!,
            userId = run.userId,
            strategyId = run.strategyId,
            universeFilterHash = run.universeFilterHash,
            dslHash = run.dslHash,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt,
            universeSize = run.universeSize,
            evaluatedCount = run.evaluatedCount,
            matchedCount = run.matchedCount,
            skippedCount = run.skippedCount,
            status = run.status,
            lastError = run.lastError,
            triggerSource = run.triggerSource,
        )

    private fun toDetailDto(run: ScreeningRunJpaEntity): ScreeningRunDetailDto =
        ScreeningRunDetailDto(
            id = run.id!!,
            userId = run.userId,
            strategyId = run.strategyId,
            universeFilterHash = run.universeFilterHash,
            dslHash = run.dslHash,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt,
            universeSize = run.universeSize,
            evaluatedCount = run.evaluatedCount,
            matchedCount = run.matchedCount,
            skippedCount = run.skippedCount,
            status = run.status,
            lastError = run.lastError,
            triggerSource = run.triggerSource,
            matches = run.matches.map { toMatchItem(it) },
        )

    private fun toMatchItem(match: ScreeningMatchJpaEntity): ScreeningRunDetailDto.MatchItem =
        ScreeningRunDetailDto.MatchItem(
            id = match.id!!,
            instrumentCode = match.instrumentCode,
            rank = match.rank,
            score = match.score,
            scoreComponents = match.scoreComponents,
            reason = match.reason,
        )
}
