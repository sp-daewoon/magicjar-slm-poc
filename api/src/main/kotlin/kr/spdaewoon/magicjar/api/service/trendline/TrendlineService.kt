package kr.spdaewoon.magicjar.api.service.trendline

import kr.spdaewoon.magicjar.api.controller.TrendlineCreateRequest
import kr.spdaewoon.magicjar.api.controller.TrendlineResponse
import kr.spdaewoon.magicjar.api.controller.TrendlineUpdateRequest
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.trendline.TrendlineJpaEntity
import kr.spdaewoon.magicjar.api.persistence.trendline.TrendlineJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

@Service
class TrendlineService(
    private val trendlineJpaRepository: TrendlineJpaRepository,
    private val candleJpaRepository: CandleJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val userAccountJpaRepository: UserAccountJpaRepository,
) {
    private val log = LoggerFactory.getLogger(TrendlineService::class.java)

    @Transactional(readOnly = true)
    fun list(code: String): List<TrendlineResponse> {
        validateCode(code)
        return trendlineJpaRepository
            .findByUserIdAndCodeOrderByCreatedAtDesc(currentUserId(), code)
            .map { it.toResponse() }
    }

    @Transactional
    fun createManual(
        code: String,
        request: TrendlineCreateRequest,
    ): TrendlineResponse {
        validateCode(code)
        upsertInstrumentStub(code)
        val userId = currentUserId()
        val lineType = parseLineType(request.type)
        val sourceInterval = request.sourceInterval.trim()
        if (sourceInterval.isBlank() || sourceInterval.length > 8) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceInterval 값이 올바르지 않습니다")
        }
        if (request.anchor1Ts == request.anchor2Ts) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "추세선 시작/끝 시각은 달라야 합니다")
        }

        val (firstTs, firstPrice, secondTs, secondPrice) =
            orderedAnchors(
                request.anchor1Ts,
                request.anchor1Price,
                request.anchor2Ts,
                request.anchor2Price,
            )

        val saved =
            trendlineJpaRepository.save(
                TrendlineJpaEntity(
                    userId = userId,
                    code = code,
                    sourceInterval = sourceInterval,
                    lineType = lineType,
                    source = SOURCE_MANUAL,
                    anchor1Ts = firstTs,
                    anchor1Price = firstPrice,
                    anchor2Ts = secondTs,
                    anchor2Price = secondPrice,
                    extendRight = request.extendRight,
                    extendLeft = request.extendLeft,
                    label =
                        request.label
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.take(80),
                    color =
                        request.color
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.take(16),
                ),
            )

        log.info("trendline manual saved — code={} interval={} type={}", code, sourceInterval, lineType)
        return saved.toResponse()
    }

    @Transactional
    fun createAuto(
        code: String,
        interval: String,
        lookback: Int,
    ): List<TrendlineResponse> {
        validateCode(code)
        validateCandleInterval(interval)
        upsertInstrumentStub(code)
        val userId = currentUserId()
        val limit = lookback.coerceIn(MIN_AUTO_LOOKBACK, MAX_AUTO_LOOKBACK)
        val candles =
            candleJpaRepository
                .findByCodeAndIntervalLatest(code, interval, PageRequest.of(0, limit))
                .asReversed()
                .filter { it.highRaw != null && it.lowRaw != null }

        if (candles.size < MIN_AUTO_CANDLE_COUNT) {
            return emptyList()
        }

        val support = buildAutoTrendline(userId, code, interval, TYPE_SUPPORT, findPivotLows(candles))
        val resistance = buildAutoTrendline(userId, code, interval, TYPE_RESISTANCE, findPivotHighs(candles))
        val candidates = listOfNotNull(support, resistance)
        if (candidates.isEmpty()) {
            return emptyList()
        }

        trendlineJpaRepository.deleteByUserIdAndCodeAndSourceAndSourceInterval(
            userId = userId,
            code = code,
            source = SOURCE_AUTO,
            sourceInterval = interval,
        )
        val saved = trendlineJpaRepository.saveAll(candidates)

        log.info("trendline auto saved — code={} interval={} count={}", code, interval, saved.size)
        return saved.map { it.toResponse() }
    }

    @Transactional
    fun update(
        code: String,
        id: Long,
        request: TrendlineUpdateRequest,
    ): TrendlineResponse {
        validateCode(code)
        val userId = currentUserId()
        val existing =
            trendlineJpaRepository.findByIdAndUserIdAndCode(id, userId, code)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "추세선을 찾을 수 없습니다")
        val lineType = parseLineType(request.type)
        if (request.anchor1Ts == request.anchor2Ts) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "추세선 시작/끝 시각은 달라야 합니다")
        }
        val (firstTs, firstPrice, secondTs, secondPrice) =
            orderedAnchors(
                request.anchor1Ts,
                request.anchor1Price,
                request.anchor2Ts,
                request.anchor2Price,
            )

        val saved =
            trendlineJpaRepository.save(
                TrendlineJpaEntity(
                    id = existing.id,
                    userId = existing.userId,
                    code = existing.code,
                    sourceInterval = existing.sourceInterval,
                    lineType = lineType,
                    source = existing.source,
                    anchor1Ts = firstTs,
                    anchor1Price = firstPrice,
                    anchor2Ts = secondTs,
                    anchor2Price = secondPrice,
                    extendRight = request.extendRight,
                    extendLeft = request.extendLeft,
                    label =
                        request.label
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.take(80),
                    color =
                        request.color
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.take(16),
                    createdAt = existing.createdAt,
                    updatedAt = Instant.now(),
                ),
            )

        log.info("trendline updated — code={} id={} type={}", code, id, lineType)
        return saved.toResponse()
    }

    @Transactional
    fun delete(
        code: String,
        id: Long,
    ) {
        validateCode(code)
        val deleted = trendlineJpaRepository.deleteByIdAndUserIdAndCode(id, currentUserId(), code)
        if (deleted == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "추세선을 찾을 수 없습니다")
        }
    }

    private fun buildAutoTrendline(
        userId: String,
        code: String,
        interval: String,
        lineType: String,
        pivots: List<Pivot>,
    ): TrendlineJpaEntity? {
        if (pivots.size < 2) return null
        val second = pivots.last()
        val first = pivots.dropLast(1).lastOrNull { it.ts != second.ts } ?: return null
        return TrendlineJpaEntity(
            userId = userId,
            code = code,
            sourceInterval = interval,
            lineType = lineType,
            source = SOURCE_AUTO,
            anchor1Ts = first.ts,
            anchor1Price = first.price,
            anchor2Ts = second.ts,
            anchor2Price = second.price,
            extendRight = true,
            extendLeft = false,
            label = "AUTO ${if (lineType == TYPE_SUPPORT) "SUPPORT" else "RESISTANCE"} $interval",
            color = if (lineType == TYPE_SUPPORT) SUPPORT_COLOR else RESISTANCE_COLOR,
        )
    }

    private fun findPivotLows(candles: List<CandleJpaEntity>): List<Pivot> =
        findPivots(candles) { center, neighbor ->
            val centerLow = center.lowRaw ?: return@findPivots false
            val neighborLow = neighbor.lowRaw ?: return@findPivots false
            centerLow <= neighborLow
        }.mapNotNull { candle -> candle.lowRaw?.let { Pivot(candle.ts, it) } }

    private fun findPivotHighs(candles: List<CandleJpaEntity>): List<Pivot> =
        findPivots(candles) { center, neighbor ->
            val centerHigh = center.highRaw ?: return@findPivots false
            val neighborHigh = neighbor.highRaw ?: return@findPivots false
            centerHigh >= neighborHigh
        }.mapNotNull { candle -> candle.highRaw?.let { Pivot(candle.ts, it) } }

    private fun findPivots(
        candles: List<CandleJpaEntity>,
        predicate: (CandleJpaEntity, CandleJpaEntity) -> Boolean,
    ): List<CandleJpaEntity> {
        if (candles.size < MIN_AUTO_CANDLE_COUNT) return emptyList()
        return (PIVOT_WING until candles.size - PIVOT_WING)
            .mapNotNull { index ->
                val center = candles[index]
                val neighbors =
                    (index - PIVOT_WING..index + PIVOT_WING)
                        .filter { it != index }
                        .map { candles[it] }
                if (neighbors.all { predicate(center, it) }) center else null
            }
    }

    private fun orderedAnchors(
        anchor1Ts: Instant,
        anchor1Price: BigDecimal,
        anchor2Ts: Instant,
        anchor2Price: BigDecimal,
    ): AnchorTuple =
        if (anchor1Ts.isBefore(anchor2Ts)) {
            AnchorTuple(anchor1Ts, anchor1Price, anchor2Ts, anchor2Price)
        } else {
            AnchorTuple(anchor2Ts, anchor2Price, anchor1Ts, anchor1Price)
        }

    private fun currentUserId(): String =
        userAccountJpaRepository
            .findAll(Sort.by(Sort.Direction.ASC, "createdAt"))
            .firstOrNull()
            ?.userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "셋업 완료 후 사용 가능합니다")

    private fun validateCode(code: String) {
        if (!code.matches(Regex("^\\d{6}$"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "종목코드는 6자리 숫자여야 합니다")
        }
    }

    private fun validateCandleInterval(interval: String) {
        try {
            CandleInterval.fromCode(interval)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 interval: $interval")
        }
    }

    private fun parseLineType(type: String): String =
        when (type.trim().uppercase()) {
            TYPE_SUPPORT -> TYPE_SUPPORT
            TYPE_RESISTANCE -> TYPE_RESISTANCE
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "type은 SUPPORT 또는 RESISTANCE만 가능합니다")
        }

    private fun upsertInstrumentStub(code: String) {
        if (!instrumentJpaRepository.existsByCode(code)) {
            runCatching {
                instrumentJpaRepository.save(
                    InstrumentJpaEntity(
                        code = code,
                        name = code,
                        market = "UNKNOWN",
                        type = "UNKNOWN",
                    ),
                )
            }.onFailure { e ->
                log.debug("instrument stub upsert race — code={}: {}", code, e.message)
            }
        }
    }

    private fun TrendlineJpaEntity.toResponse(): TrendlineResponse =
        TrendlineResponse(
            id = id ?: 0L,
            code = code,
            sourceInterval = sourceInterval,
            type = lineType,
            source = source,
            anchor1Ts = anchor1Ts,
            anchor1Price = anchor1Price.toPlainString(),
            anchor2Ts = anchor2Ts,
            anchor2Price = anchor2Price.toPlainString(),
            extendRight = extendRight,
            extendLeft = extendLeft,
            label = label,
            color = color,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private data class Pivot(
        val ts: Instant,
        val price: BigDecimal,
    )

    private data class AnchorTuple(
        val anchor1Ts: Instant,
        val anchor1Price: BigDecimal,
        val anchor2Ts: Instant,
        val anchor2Price: BigDecimal,
    )

    companion object {
        private const val TYPE_SUPPORT = "SUPPORT"
        private const val TYPE_RESISTANCE = "RESISTANCE"
        private const val SOURCE_MANUAL = "MANUAL"
        private const val SOURCE_AUTO = "AUTO"
        private const val SUPPORT_COLOR = "#10b981"
        private const val RESISTANCE_COLOR = "#ef4444"
        private const val MIN_AUTO_LOOKBACK = 30
        private const val MAX_AUTO_LOOKBACK = 1000
        private const val MIN_AUTO_CANDLE_COUNT = 9
        private const val PIVOT_WING = 2
    }
}
