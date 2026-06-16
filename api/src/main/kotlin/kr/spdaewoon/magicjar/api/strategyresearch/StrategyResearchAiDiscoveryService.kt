package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryCorePromptJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryCorePromptJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryPresetJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryPresetJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryStateJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryStateJpaRepository
import kr.spdaewoon.magicjar.domain.indicator.IndicatorCatalog
import kr.spdaewoon.magicjar.domain.indicator.IndicatorCategory
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class StrategyResearchAiDiscoveryService(
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val candleJpaRepository: CandleJpaRepository,
    private val aiTextClient: StrategyResearchAiTextClient,
    private val objectMapper: ObjectMapper,
    private val stateRepository: StrategyResearchAiDiscoveryStateJpaRepository,
    private val presetRepository: StrategyResearchAiDiscoveryPresetJpaRepository,
    private val corePromptRepository: StrategyResearchAiDiscoveryCorePromptJpaRepository,
) {
    private val log = LoggerFactory.getLogger(StrategyResearchAiDiscoveryService::class.java)

    companion object {
        private const val MIN_RESEARCH_CANDLES = 40

        /**
         * core wrapper 기본값(서비스 코드 seed). DB 행이 없으면 이 값으로 fallback 하며,
         * reset API 는 이 값으로 복원한다. V71 seed 와 동일 본문이어야 한다.
         */
        val DEFAULT_CORE_PROMPT =
            """
            너는 한국 주식 자동매매 시스템 magicJar의 '종목 발굴 전문 퀀트 리서처'다.
            사용자의 요청을 분석하여 조건에 부합하는 한국 주식 후보를 제안하라.
            단, 너의 제안은 절대적인 매수 추천이 아니며, 이후 magicJar의 자체 Screener와 과거 캔들 Backtest 검증을 거쳐야 하는 '가설 후보'다.

            [분석 원칙]
            1. 단순 시총 상위주 나열을 금지한다. 사용자의 조건에 맞는 구체적인 모멘텀, 수급, 차트 패턴적 근거를 reason에 작성하라.
            2. 종목의 섹터와 테마 트렌드를 반영하라. 예: HBM, 전력설비, 조선, 방산, AI 인프라 등.
            3. KRX 상장 보통주/우선주 후보만 제안한다. ETF, ETN, ELW, 펀드, 리츠, 해외주식, 파생상품은 제외한다.
            4. 최신 가격·뉴스를 확정적으로 단정하지 말고, 검증이 필요한 후보 가설로 표현한다.
            5. 매수/매도 권유, 확정 수익, 자동 주문을 암시하는 표현을 금지한다.
            """.trimIndent()
    }

    /** DB 행이 있으면 DB 가 권위, 없으면 서비스 코드 기본값으로 fallback. */
    private fun corePromptText(): String =
        corePromptRepository
            .findById(StrategyResearchAiDiscoveryCorePromptJpaEntity.SINGLETON_ID)
            .orElse(null)
            ?.promptText
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_CORE_PROMPT

    fun corePrompt(): StrategyResearchAiDiscoveryCorePromptDto {
        val entity = corePromptRepository.findById(StrategyResearchAiDiscoveryCorePromptJpaEntity.SINGLETON_ID).orElse(null)
        val text = entity?.promptText?.takeIf { it.isNotBlank() } ?: DEFAULT_CORE_PROMPT
        return StrategyResearchAiDiscoveryCorePromptDto(
            promptText = text,
            isDefault = text.trim() == DEFAULT_CORE_PROMPT.trim(),
            updatedAt = entity?.updatedAt?.toString(),
        )
    }

    fun updateCorePrompt(request: StrategyResearchAiDiscoveryCorePromptUpdateRequestDto): StrategyResearchAiDiscoveryCorePromptDto {
        val text = request.promptText.trim()
        if (text.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "core 프롬프트는 비워둘 수 없습니다.")
        }
        saveCorePrompt(text)
        return corePrompt()
    }

    fun resetCorePrompt(): StrategyResearchAiDiscoveryCorePromptDto {
        saveCorePrompt(DEFAULT_CORE_PROMPT)
        return corePrompt()
    }

    private fun saveCorePrompt(text: String) {
        val entity =
            corePromptRepository
                .findById(StrategyResearchAiDiscoveryCorePromptJpaEntity.SINGLETON_ID)
                .orElse(StrategyResearchAiDiscoveryCorePromptJpaEntity(promptText = text))
        entity.promptText = text
        entity.updatedAt = Instant.now()
        corePromptRepository.save(entity)
    }

    fun latest(
        userId: String,
        interval: String? = null,
        from: String? = null,
        to: String? = null,
    ): StrategyResearchAiDiscoveryResponseDto? {
        val entity = stateRepository.findById(userId).orElse(null) ?: return null
        val response = entity.toResponse()
        val availabilityRequest =
            StrategyResearchAiDiscoveryRequestDto(
                userId = userId,
                query = response.query,
                market = response.market,
                limit = response.limit,
                presetId = response.presetId,
                interval = interval,
                from = from,
                to = to,
            )
        if (candleAvailabilityWindow(availabilityRequest) == null) {
            return response
        }

        val refreshedItems = enrichCandleAvailability(response.items, availabilityRequest)
        val keptItems = refreshedItems.filter { it.status == StrategyResearchAiDiscoveryItemStatus.KEPT }
        val dismissedItems = refreshedItems.filter { it.status == StrategyResearchAiDiscoveryItemStatus.DISMISSED }
        val warnings =
            discoveryWarnings(
                keptItems = keptItems,
                dismissedItems = dismissedItems,
                items = refreshedItems,
                requestedLimit = response.limit,
                aiSkipped = response.provider == "state-cache",
            )
        entity.itemsJson = objectMapper.writeValueAsString(refreshedItems)
        entity.warningsJson = objectMapper.writeValueAsString(warnings)
        entity.updatedAt = Instant.now()
        return stateRepository.save(entity).toResponse()
    }

    fun clear(userId: String) {
        if (stateRepository.existsById(userId)) {
            stateRepository.deleteById(userId)
        }
    }

    fun presets(): List<StrategyResearchAiDiscoveryPresetDto> = presetRepository.findAllByOrderBySortOrderAscIdAsc().map { it.toDto() }

    fun createPreset(request: StrategyResearchAiDiscoveryPresetCreateRequestDto): StrategyResearchAiDiscoveryPresetDto {
        val id = request.id?.trim()?.takeIf { it.isNotBlank() } ?: "custom_${Instant.now().toEpochMilli()}"
        if (presetRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 AI 종목발굴 프리셋 ID입니다: $id")
        }
        val sortOrder =
            request.sortOrder
                ?: ((presetRepository.findAllByOrderBySortOrderAscIdAsc().maxOfOrNull { it.sortOrder } ?: 0) + 10)
        val now = Instant.now()
        val entity =
            StrategyResearchAiDiscoveryPresetJpaEntity(
                id = id,
                label = request.label.trim(),
                description = request.description.trim(),
                promptText = request.promptText.trim(),
                sortOrder = sortOrder,
                active = request.active,
                builtIn = false,
                createdAt = now,
                updatedAt = now,
            )
        return presetRepository.save(entity).toDto()
    }

    fun updatePreset(
        presetId: String,
        request: StrategyResearchAiDiscoveryPresetUpdateRequestDto,
    ): StrategyResearchAiDiscoveryPresetDto {
        val entity =
            presetRepository
                .findById(presetId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "AI 종목발굴 프리셋을 찾지 못했습니다: $presetId") }
        entity.label = request.label.trim()
        entity.description = request.description.trim()
        entity.promptText = request.promptText.trim()
        entity.sortOrder = request.sortOrder
        entity.active = request.active
        entity.updatedAt = Instant.now()
        return presetRepository.save(entity).toDto()
    }

    /**
     * 프리셋 삭제. builtin(is_builtin=TRUE)도 삭제를 허용한다.
     *
     * V70 seed 는 ON CONFLICT DO NOTHING 이므로, builtin 을 삭제한 뒤 재배포해도 부활하지 않는다(영구 삭제).
     * state.preset_id 가 삭제된 id 를 참조 중이면 다음 discover 의 resolveActivePreset 이
     * NOT FOUND(BAD_REQUEST)를 던지지 않도록 — 단 resolveActivePreset 은 명시 요청 presetId 만 검증하므로,
     * state 에만 남은 stale id 는 새 요청 presetId 가 null 이면 자연히 null 로 취급된다.
     */
    fun deletePreset(presetId: String) {
        if (!presetRepository.existsById(presetId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "AI 종목발굴 프리셋을 찾지 못했습니다: $presetId")
        }
        presetRepository.deleteById(presetId)
    }

    fun previewPrompt(request: StrategyResearchAiDiscoveryPromptPreviewRequestDto): StrategyResearchAiDiscoveryPromptPreviewDto {
        val discoveryRequest =
            StrategyResearchAiDiscoveryRequestDto(
                userId = request.userId,
                query = request.query,
                presetId = request.presetId,
                market = request.market,
                limit = request.limit,
            )
        val previous = stateRepository.findById(request.userId).orElse(null)
        val keptItems = previous?.items()?.filter { it.status == StrategyResearchAiDiscoveryItemStatus.KEPT } ?: emptyList()
        val dismissedItems = previous?.items()?.filter { it.status == StrategyResearchAiDiscoveryItemStatus.DISMISSED } ?: emptyList()
        val preset = resolveActivePreset(request.presetId)
        requireQueryOrPreset(request.query, preset)
        return StrategyResearchAiDiscoveryPromptPreviewDto(
            systemPrompt = corePromptText(),
            preset = preset?.toDto(),
            userQuery = request.query,
            finalPrompt = buildPrompt(discoveryRequest, keptItems, dismissedItems, preset),
            generatedAt = Instant.now().toString(),
            warnings =
                buildList {
                    add("이 프롬프트는 후보 가설 생성을 위한 미리보기이며 Claude 호출을 실행하지 않습니다.")
                    if (keptItems.isNotEmpty()) add("유지 후보 ${keptItems.size}건이 중복 금지 문맥에 포함됩니다.")
                    if (dismissedItems.isNotEmpty()) add("제외 후보 ${dismissedItems.size}건이 재제안 금지 문맥에 포함됩니다.")
                },
        )
    }

    /**
     * AI 프리셋 추천. magicJar 구조(core wrapper·기존 프리셋 전체·실제 시스템 능력·사용자 hint)를
     * 하나의 메타 프롬프트로 묶어 Claude에게 신규 발굴 프리셋 후보를 받아온다.
     *
     * - 저장하지 않는다. 추천만 반환하며, 사용자가 검토 후 기존 createPreset 으로 저장한다.
     * - AI 실패/타임아웃은 discover 와 동일하게 ResponseStatusException 으로 전파(mock 합성 금지).
     */
    fun recommendPresets(
        request: StrategyResearchAiDiscoveryPresetRecommendRequestDto,
    ): StrategyResearchAiDiscoveryPresetRecommendResponseDto {
        val count = request.count.coerceIn(1, 5)
        val existingPresets = presetRepository.findAllByOrderBySortOrderAscIdAsc()
        val hint = request.hint?.trim()?.takeIf { it.isNotBlank() }

        val prompt = buildPresetRecommendPrompt(count = count, hint = hint, existingPresets = existingPresets)
        val aiResult = aiTextClient.complete(prompt)
        val items = parsePresetRecommendations(aiResult.text).take(count)
        if (items.isEmpty()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude 응답에서 추천 프리셋을 읽지 못했습니다.")
        }

        log.info(
            "StrategyResearch AI 프리셋 추천: userId={} hintLength={} requested={} existing={} returned={}",
            request.userId,
            hint?.length ?: 0,
            count,
            existingPresets.size,
            items.size,
        )

        val existingLabels = existingPresets.map { it.label.trim() }.toSet()
        return StrategyResearchAiDiscoveryPresetRecommendResponseDto(
            provider = aiResult.provider,
            generatedAt = Instant.now().toString(),
            warnings =
                buildList {
                    add("추천 프리셋은 매수 추천이 아니라 Screener·Backtest 검증용 후보 가설 템플릿입니다.")
                    add("추천은 저장되지 않습니다. 검토 후 '프리셋 추가'로 직접 저장하세요.")
                    if (items.size < count) {
                        add("요청한 ${count}건 중 ${items.size}건만 추천되었습니다.")
                    }
                    val dup = items.count { it.label.trim() in existingLabels }
                    if (dup > 0) {
                        add("기존 프리셋과 이름이 겹치는 후보 ${dup}건이 있어 저장 전 이름 조정이 필요할 수 있습니다.")
                    }
                },
            items = items,
        )
    }

    fun updateItemStatus(request: StrategyResearchAiDiscoveryItemStatusRequestDto): StrategyResearchAiDiscoveryResponseDto {
        val entity =
            stateRepository
                .findById(request.userId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "저장된 AI 종목발굴 결과가 없습니다.") }
        val items = entity.items()
        var found = false
        val updatedItems =
            items.map { item ->
                if (item.itemKey == request.itemKey) {
                    found = true
                    item.copy(status = request.status)
                } else {
                    item
                }
            }
        if (!found) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "AI 종목발굴 후보를 찾지 못했습니다.")
        }
        entity.itemsJson = objectMapper.writeValueAsString(updatedItems)
        entity.updatedAt = Instant.now()
        return stateRepository.save(entity).toResponse()
    }

    fun discover(request: StrategyResearchAiDiscoveryRequestDto): StrategyResearchAiDiscoveryResponseDto {
        val previous = stateRepository.findById(request.userId).orElse(null)
        val previousItems = previous?.items() ?: emptyList()
        val keptItems = previousItems.filter { it.status == StrategyResearchAiDiscoveryItemStatus.KEPT }
        val dismissedItems = previousItems.filter { it.status == StrategyResearchAiDiscoveryItemStatus.DISMISSED }
        val dismissedKeys = dismissedItems.map { it.itemKey }.toSet()
        val keptKeys = keptItems.map { it.itemKey }.toSet()
        val preset = resolveActivePreset(request.presetId)
        requireQueryOrPreset(request.query, preset)

        if (keptItems.size >= request.limit) {
            val items = enrichCandleAvailability(keptItems + dismissedItems, request)
            return saveState(
                request = request,
                response =
                    StrategyResearchAiDiscoveryResponseDto(
                        query = request.query,
                        presetId = preset?.id,
                        market = request.market,
                        limit = request.limit,
                        provider = "state-cache",
                        generatedAt = Instant.now().toString(),
                        warnings = discoveryWarnings(keptItems, dismissedItems, items, request.limit, aiSkipped = true),
                        items = items,
                    ),
            )
        }

        val prompt = buildPrompt(request, keptItems, dismissedItems, preset)
        val aiResult = aiTextClient.complete(prompt)
        val rawItems = parseItems(aiResult.text).take(12)
        if (rawItems.isEmpty()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude 응답에서 종목 후보를 읽지 못했습니다.")
        }

        val marketFilter = request.market?.uppercase()
        val freshItems =
            rawItems
                .map { it.toDto(marketFilter) }
                .filterNot { it.itemKey in dismissedKeys || it.itemKey in keptKeys }
        val availableSlots = (request.limit - keptItems.size).coerceAtLeast(0)
        val visibleItems = keptItems + freshItems.take(availableSlots)
        val items = enrichCandleAvailability(visibleItems + dismissedItems, request)

        log.info(
            "StrategyResearch AI 종목발굴: userId={} queryLength={} presetId={} rawItems={} matched={}",
            request.userId,
            request.query?.length ?: 0,
            preset?.id,
            rawItems.size,
            items.count { it.matched },
        )

        return saveState(
            request = request,
            response =
                StrategyResearchAiDiscoveryResponseDto(
                    query = request.query,
                    presetId = preset?.id,
                    market = request.market,
                    limit = request.limit,
                    provider = aiResult.provider,
                    generatedAt = Instant.now().toString(),
                    warnings = discoveryWarnings(keptItems, dismissedItems, items, request.limit),
                    items = items,
                ),
        )
    }

    private fun discoveryWarnings(
        keptItems: List<StrategyResearchAiDiscoveryItemDto>,
        dismissedItems: List<StrategyResearchAiDiscoveryItemDto>,
        items: List<StrategyResearchAiDiscoveryItemDto>,
        requestedLimit: Int,
        aiSkipped: Boolean = false,
    ): List<String> {
        val unmatchedCount = items.count { !it.matched }
        // 상시 안내성 문구 4종(검증용 표시·유지 고정·제외 숨김·캔들 부족 안내)은 노이즈로 제거 —
        // 카드 뱃지·선택 차단·캔들 가져오기 버튼이 동일 정보를 UI로 전달한다 (사용자 지시 2026-06-07).
        // 특이 상황 알림 3종(개수 초과·AI skip·미매칭)만 유지.
        return buildList {
            if (keptItems.size > requestedLimit) {
                add("유지 후보가 요청 개수 ${requestedLimit}건보다 많아 ${keptItems.size}건을 표시합니다.")
            }
            if (aiSkipped) {
                add("표시 슬롯이 유지 후보로 이미 채워져 Claude 호출은 건너뛰었습니다.")
            }
            if (unmatchedCount > 0) {
                add("종목 마스터와 매칭되지 않은 후보 ${unmatchedCount}건이 있습니다.")
            }
        }
    }

    /**
     * 후보별 candle 보유량을 재계산해 candleCount·hasResearchCandles를 채운다.
     * request에 interval/from/to가 없으면 기존 동작 유지(둘 다 null).
     * state-cache 반환 경로·신규 발굴 경로 양쪽에서 호출되어 stale candleCount를 예방한다.
     */
    private fun enrichCandleAvailability(
        items: List<StrategyResearchAiDiscoveryItemDto>,
        request: StrategyResearchAiDiscoveryRequestDto,
    ): List<StrategyResearchAiDiscoveryItemDto> {
        val window = candleAvailabilityWindow(request) ?: return items
        return items.map { item ->
            val code = item.code
            if (code.isNullOrBlank()) {
                item.copy(candleCount = null, hasResearchCandles = null)
            } else {
                val count =
                    runCatching {
                        val rangeCount =
                            candleJpaRepository.countByCodeAndIntervalAndTsBetween(
                                code = code,
                                interval = window.interval,
                                fromTs = window.from,
                                toTs = window.to,
                            )
                        val missing = (MIN_RESEARCH_CANDLES - rangeCount).coerceAtLeast(0L).toInt()
                        val warmupCount =
                            if (missing > 0) {
                                val warmupRows =
                                    candleJpaRepository.findByCodeAndIntervalAndTsBefore(
                                        code = code,
                                        interval = window.interval,
                                        before = window.from,
                                        pageable = PageRequest.of(0, missing),
                                    )
                                warmupRows.size.toLong()
                            } else {
                                0L
                            }
                        rangeCount + warmupCount
                    }.onFailure { e ->
                        log.warn(
                            "StrategyResearch AI 후보 candle 보유량 조회 실패: code={} interval={} err={}",
                            code,
                            window.interval,
                            e.message,
                        )
                    }.getOrNull()
                if (count == null) {
                    item.copy(candleCount = null, hasResearchCandles = null)
                } else {
                    item.copy(candleCount = count, hasResearchCandles = count >= MIN_RESEARCH_CANDLES)
                }
            }
        }
    }

    private fun candleAvailabilityWindow(request: StrategyResearchAiDiscoveryRequestDto): StrategyResearchCandleWindow? {
        val interval = request.interval?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val from =
            request.from
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val to =
            request.to
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        if (from.isAfter(to)) return null
        return StrategyResearchCandleWindows.of(interval, from, to)
    }

    private fun saveState(
        request: StrategyResearchAiDiscoveryRequestDto,
        response: StrategyResearchAiDiscoveryResponseDto,
    ): StrategyResearchAiDiscoveryResponseDto {
        val now = Instant.now()
        val generatedAt = Instant.parse(response.generatedAt)
        // state.query 컬럼은 NOT NULL 이라 직접 입력 없이 프리셋만 쓴 경우 빈 문자열로 보관한다.
        val storedQuery = request.query?.trim().orEmpty()
        val entity =
            stateRepository.findById(request.userId).orElse(
                StrategyResearchAiDiscoveryStateJpaEntity(
                    userId = request.userId,
                    query = storedQuery,
                    market = request.market,
                    presetId = response.presetId,
                    candidateLimit = request.limit,
                    provider = response.provider,
                    generatedAt = generatedAt,
                    warningsJson = "[]",
                    itemsJson = "[]",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        entity.query = storedQuery
        entity.presetId = response.presetId
        entity.market = request.market
        entity.candidateLimit = request.limit
        entity.provider = response.provider
        entity.generatedAt = generatedAt
        entity.warningsJson = objectMapper.writeValueAsString(response.warnings)
        entity.itemsJson = objectMapper.writeValueAsString(response.items)
        entity.updatedAt = now
        return stateRepository.save(entity).toResponse()
    }

    private fun StrategyResearchAiDiscoveryStateJpaEntity.toResponse(): StrategyResearchAiDiscoveryResponseDto =
        StrategyResearchAiDiscoveryResponseDto(
            query = query.takeIf { it.isNotBlank() },
            presetId = presetId,
            market = market,
            limit = candidateLimit,
            provider = provider,
            generatedAt = generatedAt.toString(),
            warnings = warnings(),
            items = items(),
        )

    private fun StrategyResearchAiDiscoveryStateJpaEntity.warnings(): List<String> =
        runCatching { objectMapper.readValue<List<String>>(warningsJson) }.getOrDefault(emptyList())

    private fun StrategyResearchAiDiscoveryStateJpaEntity.items(): List<StrategyResearchAiDiscoveryItemDto> =
        runCatching { objectMapper.readValue<List<StrategyResearchAiDiscoveryItemDto>>(itemsJson) }.getOrDefault(emptyList())

    private fun resolveActivePreset(presetId: String?): StrategyResearchAiDiscoveryPresetJpaEntity? {
        val normalized = presetId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val preset =
            presetRepository
                .findById(normalized)
                .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "AI 종목발굴 프리셋을 찾지 못했습니다: $normalized") }
        if (!preset.active) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비활성 AI 종목발굴 프리셋은 사용할 수 없습니다: $normalized")
        }
        return preset
    }

    /**
     * 사용자 query 옵션화 검증 규칙.
     *
     * - 유효한 프리셋을 선택했으면 query 를 생략할 수 있다(프리셋 지시문 기준으로 발굴).
     * - 프리셋이 없는 직접 입력 모드에서는 query 가 필수다.
     */
    private fun requireQueryOrPreset(
        query: String?,
        preset: StrategyResearchAiDiscoveryPresetJpaEntity?,
    ) {
        if (preset == null && query.isNullOrBlank()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "직접 입력 모드에서는 프롬프트가 필수입니다. 프리셋을 선택하거나 검색어를 입력하세요.",
            )
        }
    }

    private fun StrategyResearchAiDiscoveryPresetJpaEntity.toDto(): StrategyResearchAiDiscoveryPresetDto =
        StrategyResearchAiDiscoveryPresetDto(
            id = id,
            label = label,
            description = description,
            promptText = promptText,
            sortOrder = sortOrder,
            active = active,
            builtIn = builtIn,
            updatedAt = updatedAt.toString(),
        )

    private fun buildPrompt(
        request: StrategyResearchAiDiscoveryRequestDto,
        keptItems: List<StrategyResearchAiDiscoveryItemDto>,
        dismissedItems: List<StrategyResearchAiDiscoveryItemDto>,
        preset: StrategyResearchAiDiscoveryPresetJpaEntity?,
    ): String {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val marketText = request.market ?: "KOSPI/KOSDAQ/KONEX 전체"
        val userQuery = request.query?.trim()?.takeIf { it.isNotBlank() }
        return listOf(
            corePromptText(),
            "",
            "[요청 컨텍스트]",
            "오늘 날짜: $today KST",
            "시장 필터: $marketText",
            "후보 수: ${request.limit}",
            "",
            if (preset != null) {
                listOf(
                    "[선택 프리셋]",
                    "프리셋 ID: ${preset.id}",
                    "프리셋 이름: ${preset.label}",
                    "프리셋 설명: ${preset.description}",
                    "프리셋 지시문:",
                    preset.promptText,
                    "",
                ).joinToString("\n")
            } else {
                "[선택 프리셋]\n없음. 사용자 입력만 바탕으로 검증 후보를 제안한다.\n"
            },
            "[사용자 입력]",
            userQuery ?: "없음. 프리셋 지시문을 기준으로 후보를 제안한다.",
            "",
            if (keptItems.isNotEmpty()) {
                "이미 사용자가 유지하기로 한 후보(중복 제안 금지): ${keptItems.joinToString { it.nameWithCode() }}"
            } else {
                null
            },
            if (dismissedItems.isNotEmpty()) {
                "사용자가 제외한 후보(다시 제안 금지): ${dismissedItems.joinToString { it.nameWithCode() }}"
            } else {
                null
            },
            "",
            "가능한 경우 KRX 6자리 종목코드를 함께 적고, 모르면 code는 null로 두세요.",
            "아래 JSON 형식만 출력하세요. 마크다운, 설명문, 코드블록은 출력하지 마세요.",
            "{",
            "  \"items\": [",
            "    {",
            "      \"name\": \"종목명\",",
            "      \"code\": \"005930\",",
            "      \"market\": \"KOSPI\",",
            "      \"themes\": [\"테마1\", \"테마2\"],",
            "      \"reason\": \"후보로 볼 만한 한두 문장 근거\",",
            "      \"confidence\": 0.72",
            "    }",
            "  ]",
            "}",
        ).filterNotNull().joinToString("\n")
    }

    /**
     * AI 프리셋 추천 메타 프롬프트.
     *
     * 이 메서드가 이 기능의 본체다. "magicJar 구조에 맞는" 추천 품질은 다음 컨텍스트를 모두
     * 프롬프트에 넣느냐에 달려 있다:
     *  1. 역할 — 발굴 프리셋 설계 전문가. 프리셋은 매수 추천이 아니라 Screener+Backtest 검증용 후보 가설 템플릿.
     *  2. 현재 core wrapper 풀텍스트 — 추천 프리셋이 이 wrapper 뒤에 주입됨을 명시.
     *  3. 기존 프리셋 전체(label+description+promptText) — 중복/유사 컨셉 회피.
     *  4. magicJar 시스템 능력 요약(실제 IndicatorCatalog·candle interval 기반) — 검증 가능한 프리셋만 추천하게.
     *  5. 사용자 hint(있으면 반영, 없으면 시장 보편 + 차별화).
     *  6. 출력 JSON 스키마 + 필드 길이·톤 제약.
     */
    private fun buildPresetRecommendPrompt(
        count: Int,
        hint: String?,
        existingPresets: List<StrategyResearchAiDiscoveryPresetJpaEntity>,
    ): String {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val existingBlock =
            if (existingPresets.isEmpty()) {
                "현재 등록된 프리셋 없음. 자유롭게 서로 다른 컨셉을 제안하되 아래 시스템 능력으로 검증 가능해야 한다."
            } else {
                existingPresets.joinToString("\n\n") { p ->
                    listOf(
                        "- 이름: ${p.label}",
                        "  설명: ${p.description}",
                        "  지시문: ${p.promptText}",
                    ).joinToString("\n")
                }
            }
        return listOf(
            "너는 한국 주식 자동매매 시스템 magicJar의 '종목 발굴 프리셋 설계 전문가'다.",
            "발굴 프리셋이란, 사용자의 발굴 의도를 한 문단의 intent prompt로 담아둔 재사용 템플릿이다.",
            "프리셋은 절대 매수 추천이 아니다. 프리셋으로 받은 후보는 반드시 magicJar의 자체 Screener(조건식 필터)와",
            "과거 캔들 Backtest 검증을 거쳐야 하는 '가설 후보'다. 아래 [core wrapper]의 [분석 원칙] 제약을 그대로 따른다.",
            "",
            "[core wrapper — 네가 설계한 프리셋의 지시문은 아래 wrapper 바로 뒤에 그대로 주입되어 Claude 발굴에 사용된다]",
            corePromptText(),
            "",
            "[이미 등록된 프리셋 — 컨셉/이름/지시문이 겹치거나 매우 유사한 추천은 금지한다. 빈 영역을 채우는 새로운 각도를 제안하라]",
            existingBlock,
            "",
            "[magicJar 시스템 능력 — 아래 능력으로 '검증 가능한' 프리셋만 추천하라. 보유하지 않은 데이터/지표에 의존하는 프리셋은 금지]",
            systemCapabilitySummary(),
            "",
            if (hint != null) {
                "[사용자 추천 방향 힌트 — 아래 요청을 최우선 반영하되, 위 제약과 검증 가능성을 지켜라]\n$hint"
            } else {
                "[사용자 추천 방향 힌트]\n없음. 현재 한국 시장에서 보편적으로 유효하면서 위 기존 프리셋과 뚜렷이 차별화되는 컨셉을 제안하라."
            },
            "",
            "[작업]",
            "오늘 날짜: $today KST",
            "위 능력으로 검증 가능하고 기존 프리셋과 겹치지 않는 신규 발굴 프리셋 ${count}건을 설계하라.",
            "각 프리셋은 구체적인 지표/캔들 interval/수급·차트 근거를 지시문에 담아, 발굴 후보가 무엇을 만족해야 하는지 분명히 하라.",
            "단순 '시총 상위주 나열', '인기주' 같은 모호한 프리셋은 금지한다.",
            "",
            "[출력 — 아래 JSON만 출력하라. 마크다운, 설명문, 코드블록 펜스는 출력하지 마라.]",
            "필드 제약:",
            "- label: 프리셋 노출명, 20자 이내, 핵심 컨셉이 드러나게.",
            "- description: 80자 이내, '~를 찾습니다' 톤(예: '20일선 눌림목에서 거래량이 붙는 스윙 후보를 찾습니다').",
            "- promptText: core wrapper 뒤에 주입될 발굴 지시문, 2~4문장 명령형(예: '~한 종목을 탐색해라', '~를 제안해라').",
            "- rationale: 이 프리셋이 magicJar의 지표·캔들·백테스트 구조로 왜 검증 가능한지 1문장.",
            "{",
            "  \"items\": [",
            "    {",
            "      \"label\": \"장초반 수급 폭발\",",
            "      \"description\": \"장 초반 거래대금이 급증하며 단기 모멘텀이 붙는 후보를 찾습니다.\",",
            "      \"promptText\": \"장 초반 거래대금이 직전 평균 대비 급증하고 5분봉에서 장대양봉으로 강하게 출발한 종목을 탐색해라. ...\",",
            "      \"rationale\": \"거래량 이동평균·ATR·5분봉 캔들로 스크리너와 백테스트 모두 검증 가능하기 때문이다.\"",
            "    }",
            "  ]",
            "}",
        ).joinToString("\n")
    }

    /**
     * magicJar가 '실제로' 보유한 발굴/검증 능력 요약.
     * IndicatorCatalog(도메인 권위)에서 카테고리별 지표를 직접 읽어 구성하므로,
     * 지표가 추가/삭제되면 이 요약도 자동으로 따라간다(메타 프롬프트가 코드 권위와 정합 유지).
     */
    private fun systemCapabilitySummary(): String {
        fun names(
            category: IndicatorCategory,
            take: Int,
        ): String =
            IndicatorCatalog
                .byCategory(category)
                .take(take)
                .joinToString(", ") { it.displayName }
        val total = IndicatorCatalog.ALL.size
        val smartMoney =
            IndicatorCatalog.ALL
                .filter { it.id == "fvg" || it.id == "order_block" }
                .joinToString(", ") { it.displayName }
        return listOf(
            "- 캔들 interval 13종: 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y (단타~중장기 스윙 모두 검증 가능).",
            "- 보유 지표 총 ${total}종(DSL 조건식·스크리너·백테스트 공용):",
            "  · 추세: ${names(IndicatorCategory.TREND, 6)} 등",
            "  · 모멘텀: ${names(IndicatorCategory.MOMENTUM, 6)} 등",
            "  · 거래량/수급: ${names(IndicatorCategory.VOLUME, 6)} 등",
            "  · 변동성: ${names(IndicatorCategory.VOLATILITY, 5)} 등",
            "  · 오실레이터: ${names(IndicatorCategory.OSCILLATOR, 5)} 등",
            "  · 스마트머니(구조): $smartMoney — FVG, 오더블록(BOS·장악형 detection), 스윙 기준봉 지지/저항.",
            "- 발굴 흐름: 자연어/프리셋 → 후보 종목 → 종목 마스터 매핑 → Screener 조건식 필터 → 특정 기준봉 선택 → 과거 캔들 Backtest 검증.",
            "- 종목 범위: KRX 상장 보통주/우선주(ST)만. ETF·ETN·ELW·리츠·해외주식·파생은 제외.",
        ).joinToString("\n")
    }

    private fun parsePresetRecommendations(text: String): List<StrategyResearchAiDiscoveryPresetRecommendItemDto> {
        val json = extractJson(text)
        val root =
            runCatching { objectMapper.readTree(json) }
                .getOrElse {
                    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude 추천 JSON 파싱 실패: ${it.message}", it)
                }
        val itemsNode =
            when {
                root.has("items") -> root["items"]
                root.isArray -> root
                else -> null
            }
        if (itemsNode == null || !itemsNode.isArray) return emptyList()
        return itemsNode.mapNotNull { node ->
            val label = node.text("label")?.let { clip(it, 20) }
            val description = node.text("description")?.let { clip(it, 80) }
            val promptText = node.text("promptText") ?: node.text("prompt") ?: node.text("intent")
            val rationale = node.text("rationale") ?: node.text("reason") ?: "magicJar의 지표·캔들·백테스트 구조로 검증 가능한 발굴 가설."
            if (label.isNullOrBlank() || promptText.isNullOrBlank()) return@mapNotNull null
            StrategyResearchAiDiscoveryPresetRecommendItemDto(
                label = label,
                description = description ?: "$label 검증용 발굴 후보를 찾습니다.",
                promptText = promptText,
                rationale = rationale,
            )
        }
    }

    private fun clip(
        value: String,
        max: Int,
    ): String = if (value.length <= max) value else value.take(max)

    private fun parseItems(text: String): List<ClaudeDiscoveryRawItem> {
        val json = extractJson(text)
        val root =
            runCatching { objectMapper.readTree(json) }
                .getOrElse {
                    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude JSON 파싱 실패: ${it.message}", it)
                }
        val itemsNode =
            when {
                root.has("items") -> root["items"]
                root.isArray -> root
                else -> null
            }
        if (itemsNode == null || !itemsNode.isArray) return emptyList()
        return itemsNode.mapNotNull { node ->
            val name = node.text("name")?.takeIf { it.isNotBlank() }
            val code = node.text("code")?.let { sixDigitCode(it) }
            if (name == null && code == null) return@mapNotNull null
            ClaudeDiscoveryRawItem(
                name = name,
                code = code,
                market = node.text("market"),
                themes = node.stringList("themes"),
                reason = node.text("reason") ?: node.text("thesis") ?: "AI 후보",
                confidence = node.number("confidence") ?: 0.5,
            )
        }
    }

    private fun extractJson(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val fenced =
            Regex("```(?:json)?\\s*([\\s\\S]*?)```")
                .find(trimmed)
                ?.groupValues
                ?.get(1)
                ?.trim()
        if (!fenced.isNullOrBlank()) return fenced
        val startObject = trimmed.indexOf('{')
        val endObject = trimmed.lastIndexOf('}')
        if (startObject >= 0 && endObject > startObject) return trimmed.substring(startObject, endObject + 1)
        val startArray = trimmed.indexOf('[')
        val endArray = trimmed.lastIndexOf(']')
        if (startArray >= 0 && endArray > startArray) return trimmed.substring(startArray, endArray + 1)
        return trimmed
    }

    private fun ClaudeDiscoveryRawItem.toDto(marketFilter: String?): StrategyResearchAiDiscoveryItemDto {
        val instrument = resolveInstrument(this, marketFilter)
        val resolvedCode = instrument?.code
        val resolvedName = instrument?.name ?: name ?: code ?: "미확인 후보"
        val normalizedConfidence =
            when {
                confidence > 1.0 && confidence <= 100.0 -> confidence / 100.0
                else -> confidence
            }.coerceIn(0.0, 1.0)
        return StrategyResearchAiDiscoveryItemDto(
            itemKey = discoveryItemKey(resolvedCode, code, resolvedName),
            code = resolvedCode,
            name = resolvedName,
            market = instrument?.market ?: market?.uppercase(),
            sector = instrument?.sector,
            theme = instrument?.theme,
            aiReason = reason,
            themes = themes.take(5),
            confidence = "%.2f".format(normalizedConfidence).toDouble(),
            matched = instrument != null,
            rawName = name,
            rawCode = code,
            tradingHalt = instrument?.tradingHalt ?: false,
            managementStock = instrument?.managementStock ?: false,
            status = StrategyResearchAiDiscoveryItemStatus.NEW,
        )
    }

    private fun resolveInstrument(
        raw: ClaudeDiscoveryRawItem,
        marketFilter: String?,
    ): InstrumentJpaEntity? {
        raw.code
            ?.let { code ->
                instrumentJpaRepository.findById(code).orElse(null)
            }?.takeIf { it.isResearchEligible(marketFilter) }
            ?.let { return it }

        val queries =
            listOfNotNull(raw.name, raw.code)
                .flatMap { listOf(it, normalizeQuery(it)) }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

        queries.forEach { query ->
            val found =
                instrumentJpaRepository
                    .searchByNameOrCode(query, marketFilter, PageRequest.of(0, 5))
                    .firstOrNull { it.isResearchEligible(marketFilter) }
            if (found != null) return found
        }
        return null
    }

    private fun InstrumentJpaEntity.isResearchEligible(marketFilter: String?): Boolean {
        if (!active) return false
        if (securitiesGroup != null && securitiesGroup != "ST") return false
        if (marketFilter != null && market != marketFilter) return false
        return true
    }

    private fun normalizeQuery(value: String): String = value.replace(Regex("[\\s.·()（）]"), "").lowercase()

    private fun sixDigitCode(value: String): String? = Regex("\\d{6}").find(value)?.value

    private fun discoveryItemKey(
        code: String?,
        rawCode: String?,
        name: String,
    ): String =
        when {
            !code.isNullOrBlank() -> "CODE:$code"
            !rawCode.isNullOrBlank() -> "RAW:${sixDigitCode(rawCode) ?: normalizeQuery(rawCode)}"
            else -> "NAME:${normalizeQuery(name).take(80)}"
        }

    private fun StrategyResearchAiDiscoveryItemDto.nameWithCode(): String = if (code.isNullOrBlank()) name else "$name($code)"

    private fun JsonNode.text(fieldName: String): String? =
        get(fieldName)
            ?.takeIf { !it.isNull }
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonNode.number(fieldName: String): Double? =
        get(fieldName)
            ?.takeIf { it.isNumber || it.isTextual }
            ?.asText()
            ?.toDoubleOrNull()

    private fun JsonNode.stringList(fieldName: String): List<String> {
        val node = get(fieldName) ?: return emptyList()
        if (node.isArray) {
            return node.mapNotNull { it.asText()?.trim()?.takeIf { value -> value.isNotBlank() } }
        }
        return node
            .asText("")
            .split(",", "/", "|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private data class ClaudeDiscoveryRawItem(
        val name: String?,
        val code: String?,
        val market: String?,
        val themes: List<String>,
        val reason: String,
        val confidence: Double,
    )
}
