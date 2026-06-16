package kr.spdaewoon.magicjar.api.service.backtest

import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestPositionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestPositionJpaRepository
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaEntity
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestTradeJpaEntity
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestTradeJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * 백테스트 조회 서비스 (ADR 0061 §4 — C-1 5 endpoint 정합).
 *
 * ### 책임
 * 1. 사용자별 백테스트 실행 목록 조회 (페이징)
 * 2. 단건 메타 + KPI 조회
 * 3. 매매·포지션 시계열 조회 (페이징)
 * 4. 백테스트 삭제 (CASCADE 정합)
 *
 * ### 헌법 §6 정합
 * - Repository interface에 body 가진 fun 0건
 * - 조회 helper는 본 class private fun 위치
 *
 * ### 멱등성 보장
 * - DELETE 시 존재하지 않는 runId는 404 반환 (중복 호출 안전)
 * - CASCADE 삭제로 trade·position 일괄 삭제
 */
@Service
class BacktestQueryService(
    private val runRepository: BacktestRunJpaRepository,
    private val tradeRepository: BacktestTradeJpaRepository,
    private val positionRepository: BacktestPositionJpaRepository,
) {
    private val log = LoggerFactory.getLogger(BacktestQueryService::class.java)

    /**
     * 사용자 백테스트 목록을 페이징 조회한다.
     *
     * @param userId 사용자 ID
     * @param page 0-indexed 페이지 번호
     * @param size 페이지 크기 (최대 100)
     */
    fun listByUser(
        userId: String,
        page: Int,
        size: Int,
    ): Page<BacktestRunJpaEntity> {
        val validSize = size.coerceIn(1, 100)
        val pageable = PageRequest.of(page, validSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        return runRepository.findAllByUserId(userId, pageable)
    }

    /**
     * 단건 백테스트 메타 + KPI를 조회한다.
     * 해당 사용자 소유가 아니면 404 반환.
     *
     * @param id backtest_run.id
     * @param userId 요청 사용자 ID
     */
    fun getByIdAndUser(
        id: Long,
        userId: String,
    ): BacktestRunJpaEntity =
        runRepository.findByIdAndUserId(id, userId)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "백테스트를 찾을 수 없습니다: id=$id",
            )

    /**
     * 매매 기록 시계열을 페이징 조회한다.
     * run 소유권 검증 포함.
     *
     * @param runId backtest_run.id
     * @param userId 요청 사용자 ID
     * @param page 0-indexed 페이지 번호
     * @param size 페이지 크기
     */
    fun listTradesByRunId(
        runId: Long,
        userId: String,
        page: Int,
        size: Int,
    ): Page<BacktestTradeJpaEntity> {
        verifyOwnership(runId, userId)
        val validSize = size.coerceIn(1, 500)
        val pageable = PageRequest.of(page, validSize, Sort.by(Sort.Direction.ASC, "ts"))
        return tradeRepository.findAllByRunId(runId, pageable)
    }

    /**
     * 포지션 기록 시계열을 페이징 조회한다.
     * run 소유권 검증 포함.
     *
     * @param runId backtest_run.id
     * @param userId 요청 사용자 ID
     * @param page 0-indexed 페이지 번호
     * @param size 페이지 크기
     */
    fun listPositionsByRunId(
        runId: Long,
        userId: String,
        page: Int,
        size: Int,
    ): Page<BacktestPositionJpaEntity> {
        verifyOwnership(runId, userId)
        val validSize = size.coerceIn(1, 500)
        val pageable = PageRequest.of(page, validSize, Sort.by(Sort.Direction.ASC, "openTs"))
        return positionRepository.findAllByRunId(runId, pageable)
    }

    /**
     * 백테스트를 삭제한다.
     * CASCADE 삭제로 trade·position 일괄 삭제.
     * 존재하지 않거나 소유권 불일치 시 404.
     *
     * @param id backtest_run.id
     * @param userId 요청 사용자 ID
     */
    fun deleteByIdAndUser(
        id: Long,
        userId: String,
    ) {
        val run =
            runRepository.findByIdAndUserId(id, userId)
                ?: throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "백테스트를 찾을 수 없습니다: id=$id",
                )
        runRepository.delete(run)
        log.info("백테스트 삭제: runId={}, userId={}", id, userId)
    }

    // =====================================================================
    // private helpers — 헌법 §6 정합: caller class 내부 위치
    // =====================================================================

    private fun verifyOwnership(
        runId: Long,
        userId: String,
    ) {
        if (!runRepository.existsByIdAndUserId(runId, userId)) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "백테스트를 찾을 수 없습니다: id=$runId",
            )
        }
    }
}
