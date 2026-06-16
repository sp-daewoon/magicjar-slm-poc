package kr.spdaewoon.magicjar.api.ai.explain

import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CHART AI prompt context enricher.
 *
 * Frontend 는 선택된 timeframe 정도만 전달하고, API 가 DB 실측 candle에서 작은 분석 근거만 합성한다.
 * 원문 candle 배열을 LLM prompt에 싣지 않아 token을 줄이고, 빈 context로 인한 hallucination을 막는다.
 */
@Component
class AiChartPromptContextEnricher(
    private val candleRepository: CandleRepository,
    private val indicatorEngine: IndicatorEngine,
) {
    private val log = LoggerFactory.getLogger(AiChartPromptContextEnricher::class.java)

    fun enrich(context: Map<String, Any?>): Map<String, Any?> {
        val base = LinkedHashMap(context)
        val code = context.stringValue("code")
        val intervalCode = context.stringValue("timeframe") ?: context.stringValue("interval") ?: CandleInterval.ONE_DAY.code

        if (code == null || !CODE_REGEX.matches(code)) {
            return base.withChartFallback(
                timeframe = intervalCode,
                reason = "종목 코드가 없거나 6자리 숫자가 아닙니다.",
            )
        }

        val interval =
            runCatching { CandleInterval.fromCode(intervalCode) }
                .getOrElse {
                    return base.withChartFallback(
                        timeframe = intervalCode,
                        reason = "지원하지 않는 timeframe입니다: $intervalCode",
                    )
                }

        val candles =
            runCatching { candleRepository.findLatestByCodeAndInterval(code, interval, LOOKBACK_CANDLES) }
                .onFailure { e ->
                    log.warn(
                        "[AiChartContext] candle 조회 실패 — code={} interval={} err={}",
                        code,
                        interval.code,
                        e.message,
                    )
                }.getOrDefault(emptyList())
                .filter(::isValidCandle)
                .sortedBy { it.ts }

        if (candles.isEmpty()) {
            return base.withChartFallback(
                timeframe = interval.displayName(),
                reason = "magicJar DB에서 $code ${interval.code} 실측 캔들을 찾지 못했습니다.",
            )
        }

        val latest = candles.last()
        base["timeframe"] = interval.displayName()
        base["chartDataSource"] = dataSource(candles)
        base["indicators"] = indicatorSummary(candles)
        base["priceFlow"] = priceFlowSummary(candles, context.stringValue("currentPrice"))
        base["latestCandleAt"] = formatTs(latest)
        base["latestClose"] = price(latest.closeRaw)
        return base
    }

    private fun LinkedHashMap<String, Any?>.withChartFallback(
        timeframe: String,
        reason: String,
    ): Map<String, Any?> {
        this["timeframe"] = timeframe
        this["chartDataSource"] = "실측 candle 조회 실패: $reason mock/합성 데이터 0."
        this["indicators"] = "데이터 없음: $reason"
        this["priceFlow"] = "데이터 없음: $reason 캔들 수집/갱신 후 재요청해야 합니다."
        return this
    }

    private fun indicatorSummary(candles: List<Candle>): String {
        val latest = candles.last()
        val ma20 = indicatorValues(candles, IndicatorSpec("ma", mapOf("period" to 20)))["ma"]
        val ma60 = indicatorValues(candles, IndicatorSpec("ma", mapOf("period" to 60)))["ma"]
        val rsi14 = indicatorValues(candles, IndicatorSpec("rsi", mapOf("period" to 14)))["rsi"]
        val macd = indicatorValues(candles, IndicatorSpec("macd", mapOf("fast" to 12, "slow" to 26, "signal" to 9)))
        val bb20 = indicatorValues(candles, IndicatorSpec("bb", mapOf("period" to 20, "std_dev" to BigDecimal("2.0"))))
        val volume = volumeSummary(candles)

        return buildList {
            add("latestClose=${price(latest.closeRaw)}")
            if (ma20 != null) add("MA20=${price(ma20)}")
            if (ma60 != null) add("MA60=${price(ma60)}")
            if (ma20 != null) add("closeVsMA20=${pct(latest.closeRaw, ma20)}")
            if (ma60 != null) add("closeVsMA60=${pct(latest.closeRaw, ma60)}")
            if (rsi14 != null) add("RSI14=${number(rsi14, 2)}")
            if (macd.isNotEmpty()) {
                add(
                    "MACD(12,26,9)=" +
                        "macd:${number(macd["macd"], 2)} " +
                        "signal:${number(macd["signal"], 2)} " +
                        "hist:${number(macd["histogram"], 2)}",
                )
            }
            if (bb20.isNotEmpty()) {
                add(
                    "BB20=" +
                        "upper:${price(bb20["upper"])} " +
                        "mid:${price(bb20["middle"])} " +
                        "lower:${price(bb20["lower"])}",
                )
            }
            add(volume)
        }.joinToString("; ")
    }

    private fun priceFlowSummary(
        candles: List<Candle>,
        currentPrice: String?,
    ): String {
        val latest = candles.last()
        val recent = candles.takeLast(minOf(SUPPORT_RESISTANCE_WINDOW, candles.size))
        val low = recent.minByOrNull { it.lowRaw }
        val high = recent.maxByOrNull { it.highRaw }
        val first = candles.first()
        val previous = candles.getOrNull(candles.lastIndex - 1)

        return buildList {
            add(
                "basis=${candles.size} real candles ${formatTs(first)}~${formatTs(latest)}",
            )
            add(
                "latest=O:${price(latest.openRaw)} " +
                    "H:${price(latest.highRaw)} " +
                    "L:${price(latest.lowRaw)} " +
                    "C:${price(latest.closeRaw)} " +
                    "V:${latest.volume}",
            )
            if (currentPrice != null) add("screenCurrentPrice=$currentPrice")
            if (previous != null) add("last1=${pct(latest.closeRaw, previous.closeRaw)}")
            addReturn(candles, 5, "last5")
            addReturn(candles, 20, "last20")
            addReturn(candles, 60, "last60")
            if (low != null) add("supportCandidate=${price(low.lowRaw)}(${formatTs(low)})")
            if (high != null) add("resistanceCandidate=${price(high.highRaw)}(${formatTs(high)})")
        }.joinToString("; ")
    }

    private fun MutableList<String>.addReturn(
        candles: List<Candle>,
        bars: Int,
        label: String,
    ) {
        val base = candles.getOrNull(candles.lastIndex - bars) ?: return
        add("$label=${pct(candles.last().closeRaw, base.closeRaw)}")
    }

    private fun indicatorValues(
        candles: List<Candle>,
        spec: IndicatorSpec,
    ): Map<String, BigDecimal> =
        runCatching {
            indicatorEngine
                .calculate(spec, candles)
                .series
                .lastOrNull()
                ?.values
                .orEmpty()
        }.onFailure { e ->
            log.debug(
                "[AiChartContext] indicator 계산 skip — name={} candles={} err={}",
                spec.name,
                candles.size,
                e.message,
            )
        }.getOrDefault(emptyMap())

    private fun volumeSummary(candles: List<Candle>): String {
        val latest = candles.last()
        val recent = candles.takeLast(minOf(20, candles.size))
        val avg =
            recent
                .fold(BigDecimal.ZERO) { acc, candle -> acc.add(BigDecimal.valueOf(candle.volume)) }
                .divide(BigDecimal.valueOf(recent.size.toLong()), 0, RoundingMode.HALF_UP)
        val ratio =
            if (avg.signum() > 0) {
                BigDecimal.valueOf(latest.volume).divide(avg, 2, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
        return "volume=latest:${latest.volume} avg20:${avg.toPlainString()} ratio:${ratio.toPlainString()}x"
    }

    private fun dataSource(candles: List<Candle>): String {
        val sourceCounts =
            candles
                .groupingBy { it.source.ifBlank { "UNKNOWN" } }
                .eachCount()
                .entries
                .joinToString(",") { "${it.key}:${it.value}" }
        return "magicJar candle DB real data; sources=$sourceCounts; count=${candles.size}; mock/synthetic=0"
    }

    private fun isValidCandle(candle: Candle): Boolean =
        candle.openRaw.signum() > 0 &&
            candle.highRaw.signum() > 0 &&
            candle.lowRaw.signum() > 0 &&
            candle.closeRaw.signum() > 0 &&
            candle.highRaw >= candle.lowRaw

    private fun Map<String, Any?>.stringValue(key: String): String? = this[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun CandleInterval.displayName(): String =
        when (this) {
            CandleInterval.ONE_MIN -> "1m(1분봉)"
            CandleInterval.TWO_MIN -> "2m(2분봉)"
            CandleInterval.THREE_MIN -> "3m(3분봉)"
            CandleInterval.FIVE_MIN -> "5m(5분봉)"
            CandleInterval.FIFTEEN_MIN -> "15m(15분봉)"
            CandleInterval.THIRTY_MIN -> "30m(30분봉)"
            CandleInterval.NINETY_MIN -> "90m(90분봉)"
            CandleInterval.ONE_HOUR -> "1h(1시간봉)"
            CandleInterval.FOUR_HOUR -> "4h(4시간봉)"
            CandleInterval.TWELVE_HOUR -> "12h(장전체봉)"
            CandleInterval.ONE_DAY -> "1d(일봉)"
            CandleInterval.ONE_WEEK -> "1wk(주봉)"
            CandleInterval.ONE_MONTH -> "1mo(월봉)"
            CandleInterval.ONE_YEAR -> "1y(연봉)"
        }

    private fun formatTs(candle: Candle): String = formatTs(candle.ts.toJavaInstant())

    private fun formatTs(instant: java.time.Instant): String = TS_FORMATTER.format(instant)

    private fun price(value: BigDecimal?): String = number(value, 2)

    private fun number(
        value: BigDecimal?,
        scale: Int,
    ): String =
        value
            ?.setScale(scale, RoundingMode.HALF_UP)
            ?.stripTrailingZeros()
            ?.toPlainString()
            ?: "NA"

    private fun pct(
        current: BigDecimal,
        base: BigDecimal,
    ): String {
        if (base.signum() == 0) return "NA"
        val pct =
            current
                .subtract(base)
                .multiply(BigDecimal("100"))
                .divide(base, 2, RoundingMode.HALF_UP)
        return "${pct.toPlainString()}%"
    }

    companion object {
        private const val LOOKBACK_CANDLES = 120
        private const val SUPPORT_RESISTANCE_WINDOW = 20
        private val CODE_REGEX = Regex("^\\d{6}$")
        private val TS_FORMATTER: DateTimeFormatter =
            DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.of("Asia/Seoul"))
    }
}
