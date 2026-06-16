package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.controller.DisclosureItem
import kr.spdaewoon.magicjar.api.controller.SymbolDisclosureListResponse
import kr.spdaewoon.magicjar.api.persistence.DisclosureJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 종목별 DART 공시 조회 서비스 (T-NEWS Phase 1 — ADR 0075 §5).
 *
 * use case:
 *   1. stockCode 유효성 검증 (InstrumentJpaRepository)
 *   2. from/to 기간 검증 (90일 한도 — ADR 0075 §5-2)
 *   3. disclosure 테이블 페이징 조회 → DisclosureItem 매핑
 *   4. 반환: SymbolDisclosureListResponse (stockCode, items, page, size, totalElements, totalPages)
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
@Service
class SymbolDisclosureService(
    private val disclosureJpaRepository: DisclosureJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
) {
    private val log = LoggerFactory.getLogger(SymbolDisclosureService::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    companion object {
        private const val MAX_RANGE_DAYS = 90L
    }

    /**
     * 종목별 공시 리스트 조회.
     *
     * @param stockCode  KIS 종목코드 6자리
     * @param from       조회 시작 (기본값: T-7일)
     * @param to         조회 종료 (기본값: now)
     * @param onlyCorrection 정정공시만 필터 (default false)
     * @param page       페이지 번호 (0-based)
     * @param size       페이지 크기 (1~100)
     */
    fun getDisclosures(
        stockCode: String,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        onlyCorrection: Boolean,
        page: Int,
        size: Int,
    ): SymbolDisclosureListResponse {
        // 종목 존재 여부 검증
        if (!instrumentJpaRepository.existsByCode(stockCode)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다: $stockCode")
        }

        val now = OffsetDateTime.now(kst)
        val effectiveTo = to ?: now
        val effectiveFrom = from ?: now.minusDays(7)

        // 기간 검증
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from이 to보다 클 수 없습니다")
        }
        val rangeDays =
            java.time.temporal.ChronoUnit.DAYS
                .between(effectiveFrom, effectiveTo)
        if (rangeDays > MAX_RANGE_DAYS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 기간은 최대 90일입니다")
        }

        val fromInstant: Instant = effectiveFrom.toInstant()
        val toInstant: Instant = effectiveTo.toInstant()

        val pageable = PageRequest.of(page, size)

        val resultPage =
            if (onlyCorrection) {
                disclosureJpaRepository
                    .findByStockCodeAndIsCorrectionTrueAndIngestedAtBetweenOrderByIngestedAtDesc(
                        stockCode,
                        fromInstant,
                        toInstant,
                        pageable,
                    )
            } else {
                disclosureJpaRepository
                    .findByStockCodeAndIngestedAtBetweenOrderByIngestedAtDesc(
                        stockCode,
                        fromInstant,
                        toInstant,
                        pageable,
                    )
            }

        log.debug(
            "[SymbolDisclosureService] 조회 — stockCode={} onlyCorrection={} totalElements={}",
            stockCode,
            onlyCorrection,
            resultPage.totalElements,
        )

        return SymbolDisclosureListResponse(
            stockCode = stockCode,
            items =
                resultPage.content.map { entity ->
                    DisclosureItem(
                        id = entity.id ?: 0L,
                        rceptNo = entity.rceptNo,
                        corpCode = entity.corpCode,
                        corpName = entity.corpName,
                        reportNm = entity.reportNm,
                        flrNm = entity.flrNm,
                        rm = entity.rm,
                        isCorrection = entity.isCorrection,
                        url = entity.url,
                        rceptDt = entity.rceptDt,
                        ingestedAt = OffsetDateTime.ofInstant(entity.ingestedAt, kst),
                    )
                },
            page = resultPage.number,
            size = resultPage.size,
            totalElements = resultPage.totalElements,
            totalPages = resultPage.totalPages,
        )
    }
}
