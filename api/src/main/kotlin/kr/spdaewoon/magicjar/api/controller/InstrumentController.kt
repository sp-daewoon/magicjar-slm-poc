package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.criteria.Predicate
import kr.spdaewoon.magicjar.api.persistence.InstrumentAliasJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.service.InstrumentSearchService
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 종목 마스터 REST 컨트롤러 (ADR 0017 — T-IM1).
 *
 * - GET  /api/instruments/search?q=&limit=&market=  — 자동완성·검색
 * - GET  /api/instruments/{code}                    — 단일 종목 조회
 * - GET  /api/instruments?q=&market=&active=&limit=&offset= — 전체 universe 조회
 * - POST /api/instruments/sync                      — 수동 sync trigger (검색 인덱스 재구성)
 *
 * OnboardingGuardFilter 통과 의무 (PR-6 정합 — 자동).
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합 — 한국어 + example).
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Tag(name = "Instrument", description = "종목 마스터 REST — 검색·상세·universe·sync (T-IM1)")
@RestController
@RequestMapping("/api/instruments")
class InstrumentController(
    private val instrumentSearchService: InstrumentSearchService,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val instrumentAliasJpaRepository: InstrumentAliasJpaRepository,
) {
    private val log = LoggerFactory.getLogger(InstrumentController::class.java)

    // ────────────────────────────────────────────────────────────────────
    // GET /api/instruments/search — 자동완성·검색
    // ────────────────────────────────────────────────────────────────────

    @Operation(
        summary = "종목 검색 (자동완성)",
        description =
            "종목명·종목코드·테마 부분 일치 검색. " +
                "1순위: Redis sorted set ZRANGEBYLEX (< 50ms). " +
                "2순위: DB JPQL LIKE fallback. " +
                "한글 IME 조합 중에는 프론트엔드에서 호출 차단 의무 (design §11-6). " +
                "최소 1자, 최대 결과 20건.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "검색 성공 (결과 0건 포함)"),
        ApiResponse(
            responseCode = "400",
            description = "q 파라미터 누락 또는 빈 값",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(responseCode = "401", description = "셋업 미완 — OnboardingGuardFilter 차단"),
    )
    @GetMapping("/search")
    fun search(
        @Parameter(description = "검색어 (종목명·코드·테마)", example = "삼성전")
        @RequestParam q: String,
        @Parameter(description = "결과 수 (1~20, default 10)", example = "10")
        @RequestParam(defaultValue = "10") limit: Int,
        @Parameter(description = "시장 필터 (KOSPI·KOSDAQ·KONEX, 미지정 시 전체)", example = "KOSPI")
        @RequestParam(required = false) market: String?,
    ): InstrumentSearchResponseDto {
        if (q.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어(q)는 필수입니다")
        }
        val marketEnum =
            market?.let {
                runCatching { InstrumentMarket.valueOf(it.uppercase()) }.getOrElse {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 market: $it (허용: KOSPI·KOSDAQ·KONEX)")
                }
            }
        val effectiveLimit = limit.coerceIn(1, 20)
        val results = instrumentSearchService.search(q, effectiveLimit, marketEnum)
        // PR-21b: 검색 결과에 type·marketCapPrev·tradingHalt 추가 노출 (위험 가드 표시)
        // instrument entity join: code 목록으로 일괄 조회 후 매핑 (N+1 회피)
        val entityMap =
            instrumentJpaRepository
                .findAllById(results.map { it.code })
                .associateBy { it.code }
        return InstrumentSearchResponseDto(
            query = q,
            results =
                results.map {
                    val entity = entityMap[it.code]
                    InstrumentSearchItemDto(
                        code = it.code,
                        name = it.name,
                        market = it.market.name,
                        sector = it.sector,
                        score = it.score,
                        type = entity?.type?.takeIf { t -> t != "UNKNOWN" },
                        marketCapPrev = entity?.marketCapPrev,
                        tradingHalt = entity?.tradingHalt ?: false,
                    )
                },
            totalMatched = results.size,
            limit = effectiveLimit,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // GET /api/instruments/{code} — 단일 종목 상세
    // ────────────────────────────────────────────────────────────────────

    @Operation(
        summary = "종목 상세 조회",
        description =
            "종목코드(6자리)로 종목명·시장·업종·테마·alias 목록을 반환합니다. " +
                "PR-11b 기준: instrument 테이블 1순위. " +
                "404이면 instrument 미등록 상태 (첫 sync 후 정상화).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "종목코드 형식 오류 (6자리 숫자 아님)"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
        ApiResponse(
            responseCode = "404",
            description = "종목 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{code}")
    fun getByCode(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): InstrumentDetailDto {
        validateCode(code)
        val entity =
            instrumentJpaRepository.findById(code).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다: $code")
            }
        val aliases = instrumentAliasJpaRepository.findByCode(code)
        // PR-21b: Tier 1·2·3 핵심 필드 응답 schema 추가 (Tier 4 재무는 T-IND 후속)
        return InstrumentDetailDto(
            code = entity.code,
            isin = entity.isin,
            name = entity.name,
            nameEn = entity.nameEn,
            market = entity.market,
            type = entity.type,
            sector = entity.sector,
            theme = entity.theme,
            listedAt = entity.listedAt?.toString(),
            delistedAt = entity.delistedAt?.toString(),
            active = entity.active,
            aliases =
                aliases.map {
                    InstrumentAliasDto(alias = it.alias, source = it.source)
                },
            securitiesGroup = entity.securitiesGroup,
            tradingGuards =
                InstrumentTradingGuardsDto(
                    tradingHalt = entity.tradingHalt,
                    liquidation = entity.liquidation,
                    managementStock = entity.managementStock,
                    marketAlert = entity.marketAlert,
                    shortOverheated = entity.shortOverheated,
                    abnormalRunup = entity.abnormalRunup,
                    falseDisclosure = entity.falseDisclosure,
                    backdoorListing = entity.backdoorListing,
                    creditAble = entity.creditAble,
                    marginRate = entity.marginRate,
                    shortOverheatedStatus = entity.shortOverheatedStatus,
                ),
            classification =
                InstrumentClassificationDto(
                    industryLarge = entity.industryLarge,
                    industryMedium = entity.industryMedium,
                    industrySmall = entity.industrySmall,
                    isKospi200 = entity.isKospi200,
                    kospi200Sector = entity.kospi200Sector,
                    isKosdaq150 = entity.isKosdaq150,
                    isKospi100 = entity.isKospi100,
                    isKospi50 = entity.isKospi50,
                    isKrx = entity.isKrx,
                    isKrx100 = entity.isKrx100,
                    isKrx300 = entity.isKrx300,
                    isSpac = entity.isSpac,
                    isVenture = entity.isVenture,
                    isLowLiquidity = entity.isLowLiquidity,
                    preferredStockType = entity.preferredStockType,
                    etpProductType = entity.etpProductType,
                    avlsScale = entity.avlsScale,
                ),
            marketMeta =
                InstrumentMarketMetaDto(
                    basePrice = entity.basePrice?.toPlainString(),
                    tradingUnitNormal = entity.tradingUnitNormal,
                    prevVolume = entity.prevVolume,
                    marketCapPrev = entity.marketCapPrev,
                    listedShares = entity.listedShares,
                    settlementMonth = entity.settlementMonth,
                ),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // GET /api/instruments — universe 조회
    // ────────────────────────────────────────────────────────────────────

    @Operation(
        summary = "종목 universe 조회",
        description =
            "전체 활성 종목 목록 (페이지네이션). " +
                "q 검색 + market 필터: KOSPI·KOSDAQ·KONEX. " +
                "active=false이면 상장폐지 포함 (ADR 0017 §2-2 보존). " +
                "섹터 표시용 sectorKey·sectorName 포함. limit: 최대 5000 (default 100).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @GetMapping
    fun getUniverse(
        @Parameter(description = "종목명·종목코드 검색어", example = "삼성")
        @RequestParam(required = false) q: String?,
        @Parameter(description = "시장 필터 (KOSPI·KOSDAQ·KONEX)", example = "KOSPI")
        @RequestParam(required = false) market: String?,
        @Parameter(description = "활성 여부 필터 (true=활성만, false=전체)", example = "true")
        @RequestParam(defaultValue = "true") active: Boolean,
        @Parameter(description = "페이지 크기 (1~5000, default 100)", example = "100")
        @RequestParam(defaultValue = "100") limit: Int,
        @Parameter(description = "페이지 오프셋 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") offset: Int,
    ): InstrumentUniverseResponseDto {
        val effectiveLimit = limit.coerceIn(1, 5000)
        val safeOffset = offset.coerceAtLeast(0)
        val marketFilter = market?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val query = q?.trim()?.takeIf { it.isNotBlank() }
        val queryNorm = query?.replace(Regex("[\\s.·()（）]"), "")?.lowercase()
        val sort =
            Sort
                .by(
                    Sort.Order.desc("marketCapPrev").nullsLast(),
                    Sort.Order.asc("market"),
                    Sort.Order.asc("code"),
                )

        val spec =
            Specification<InstrumentJpaEntity> { root, _, cb ->
                val predicates = mutableListOf<Predicate>()
                if (active) {
                    predicates += cb.equal(root.get<Boolean>("active"), true)
                }
                marketFilter?.let {
                    predicates += cb.equal(cb.upper(root.get<String>("market")), it)
                }
                query?.let {
                    val keyword = "%${it.lowercase()}%"
                    val normalizedKeyword = "%${queryNorm ?: it.lowercase()}%"
                    predicates +=
                        cb.or(
                            cb.like(root.get<String>("code"), "%$it%"),
                            cb.like(cb.lower(root.get<String>("name")), keyword),
                            cb.like(cb.lower(root.get<String>("nameNorm")), normalizedKeyword),
                        )
                }
                cb.and(*predicates.toTypedArray())
            }

        val entities = instrumentJpaRepository.findAll(spec, sort)
        val items =
            entities
                .asSequence()
                .drop(safeOffset)
                .take(effectiveLimit)
                .map {
                    val sector = resolveSectorInfo(it)
                    InstrumentUniverseItemDto(
                        code = it.code,
                        name = it.name,
                        market = it.market,
                        type = it.type,
                        sector = it.sector,
                        sectorKey = sector.key,
                        sectorName = sector.name,
                        industryMedium = it.industryMedium,
                        marketCapPrev = it.marketCapPrev,
                        tradingHalt = it.tradingHalt,
                        managementStock = it.managementStock,
                        active = it.active,
                    )
                }.toList()

        return InstrumentUniverseResponseDto(
            items = items,
            total = entities.size,
            limit = effectiveLimit,
            offset = safeOffset,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // POST /api/instruments/sync — 수동 sync trigger
    // ────────────────────────────────────────────────────────────────────

    @Operation(
        summary = "종목 검색 인덱스 수동 재구성 (sync trigger)",
        description =
            "Redis 검색 인덱스를 DB 현재 상태로 강제 재구성합니다. " +
                "InstrumentSyncJob(KIS sync) 없이도 검색 인덱스 갱신 가능. " +
                "운영 시: KIS sync 완료 후 자동 호출됨. " +
                "개발·테스트 시: 수동 호출로 인덱스 갱신 가능.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "재구성 요청 수락"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun triggerSync(): InstrumentSyncTriggerResponse {
        val activeCount = instrumentJpaRepository.findByActiveTrue().size
        log.info("[instrument] 수동 sync trigger: activeInstruments={}", activeCount)
        // 비동기 재구성 (별도 스레드 — API thread 비차단)
        Thread {
            try {
                instrumentSearchService.rebuildIndex()
            } catch (e: Exception) {
                log.error("[instrument] rebuildIndex 실패: err={}", e.message)
            }
        }.also { it.isDaemon = true }.start()
        return InstrumentSyncTriggerResponse(triggered = true, activeInstruments = activeCount)
    }

    // ── private helpers ─────────────────────────────────────────────────

    private fun validateCode(code: String) {
        if (!code.matches(Regex("^\\d{6}$"))) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "종목 코드는 6자리 숫자여야 합니다 (예: 005930)",
            )
        }
    }

    private fun resolveSectorInfo(entity: InstrumentJpaEntity): SectorInfo {
        resolveIndustrySectorName(entity.sector)?.let {
            return sectorInfo(it)
        }

        resolveIndustrySectorName(entity.industryMedium)?.let {
            return sectorInfo(it)
        }

        resolveKrxSectorName(entity)?.let {
            return sectorInfo(it)
        }

        resolveKospi200SectorName(entity.kospi200Sector)?.let {
            return sectorInfo(it)
        }

        return SectorInfo("unclassified", "미분류")
    }

    private fun sectorInfo(name: String): SectorInfo = SectorInfo("sector:${name.trim().replace(Regex("\\s+"), "-")}", name)

    private fun normalizeSectorCode(code: String?): String? =
        code?.trim()?.uppercase()?.takeIf { it.isNotBlank() && it != "0000" && it != "UNKNOWN" }

    private fun resolveIndustrySectorName(code: String?): String? =
        when (normalizeSectorCode(code)) {
            "0005", "1019" -> "음식료·담배"
            "0006", "1020" -> "섬유·의류"
            "0007", "1021" -> "종이·목재"
            "1022" -> "출판·매체복제"
            "0008", "1023" -> "화학"
            "0009", "1024" -> "의약품·바이오"
            "0010", "1025" -> "비금속"
            "0011", "1026" -> "철강·금속"
            "0012", "1027" -> "기계·장비"
            "0013", "1028" -> "전기·전자"
            "0014", "1029" -> "의료·정밀기기"
            "0015", "1030" -> "운송장비·부품"
            "0016" -> "유통업"
            "0017" -> "전기가스업"
            "0018" -> "건설업"
            "0019" -> "운수창고업"
            "0020" -> "통신업"
            "0021" -> "금융업"
            "0022" -> "은행"
            "0024" -> "증권"
            "0025" -> "보험"
            "0026" -> "서비스업"
            "1031" -> "기타 제조"
            else -> null
        }

    private fun resolveKrxSectorName(entity: InstrumentJpaEntity): String? =
        when {
            entity.krxSectorSemicon -> "반도체"
            entity.krxSectorBio -> "바이오"
            entity.krxSectorCar -> "자동차"
            entity.krxSectorBank -> "은행"
            entity.krxSectorEnergyChem -> "에너지화학"
            entity.krxSectorSteel -> "철강"
            entity.krxSectorMediaComm -> "미디어통신"
            entity.krxSectorConstruction -> "건설"
            entity.krxSectorSecurities -> "증권"
            entity.krxSectorShip -> "조선"
            entity.krxSectorInsurance -> "보험"
            entity.krxSectorTransport -> "운송"
            else -> null
        }

    private fun resolveKospi200SectorName(code: String?): String? =
        when (code?.uppercase()) {
            "1" -> "건설"
            "2" -> "중공업"
            "3" -> "철강"
            "4" -> "에너지화학"
            "5" -> "정보통신"
            "6" -> "금융"
            "7" -> "필수소비재"
            "8" -> "자유소비재"
            "9" -> "산업재"
            "A" -> "건강관리"
            "B" -> "커뮤니케이션"
            else -> null
        }
}

private data class SectorInfo(
    val key: String,
    val name: String,
)
