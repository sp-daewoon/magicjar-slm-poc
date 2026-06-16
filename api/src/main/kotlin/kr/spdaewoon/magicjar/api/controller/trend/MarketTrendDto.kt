package kr.spdaewoon.magicjar.api.controller.trend

import io.swagger.v3.oas.annotations.media.Schema
import kr.spdaewoon.magicjar.api.service.trend.IndexCandle
import kr.spdaewoon.magicjar.domain.trend.CrossSignal
import kr.spdaewoon.magicjar.domain.trend.IndexTrend
import kr.spdaewoon.magicjar.domain.trend.SectorRankEntry
import kr.spdaewoon.magicjar.domain.trend.SectorRanking
import kr.spdaewoon.magicjar.domain.trend.SectorSnapshot
import kr.spdaewoon.magicjar.domain.trend.TrendLabel
import java.math.BigDecimal
import kotlinx.datetime.Instant as KtInstant

/**
 * `/api/market/...` 응답 DTO 묶음 (Swagger 4단 정합).
 */
@Schema(description = "지수 추세 응답 (ADR 0100 §2-2-C)")
data class IndexTrendResponse(
    @field:Schema(description = "KIS index code (예: 0001=KOSPI · .IXIC=NASDAQ)", example = "0001")
    val code: String,
    @field:Schema(
        description = "period → MA 값 map (1~5 entry). 결측 시 일부만 채움.",
        example = """{"5":2670.12,"20":2685.34,"60":2710.55}""",
    )
    val ma: Map<Int, BigDecimal>,
    @field:Schema(description = "MA cross signal", example = "NEUTRAL")
    val crossSignal: CrossSignal,
    @field:Schema(description = "추세 라벨", example = "UP")
    val trendLabel: TrendLabel,
    @field:Schema(description = "응답 echo periods", example = "[5,20,60]")
    val periodsUsed: List<Int>,
    @field:Schema(description = "사용자 설정 적용 여부", example = "false")
    val isUserCustom: Boolean,
    @field:Schema(
        description = "true 시 candles.size < longMa — frontend '데이터 부족' 한국어 표시 의무",
        example = "false",
    )
    val dataInsufficient: Boolean,
    @field:Schema(description = "마지막 종가", nullable = true, example = "2685.34")
    val lastClose: BigDecimal?,
    @field:Schema(description = "전일 대비 등락률 (%)", nullable = true, example = "0.55")
    val changeRate: BigDecimal?,
) {
    companion object {
        fun from(trend: IndexTrend): IndexTrendResponse =
            IndexTrendResponse(
                code = trend.code,
                ma = trend.ma,
                crossSignal = trend.crossSignal,
                trendLabel = trend.trendLabel,
                periodsUsed = trend.periodsUsed,
                isUserCustom = trend.isUserCustom,
                dataInsufficient = trend.dataInsufficient,
                lastClose = trend.lastClose,
                changeRate = trend.changeRate,
            )
    }
}

@Schema(description = "지수 카드 list 응답 — /api/market/index/snapshot")
data class IndexSnapshotResponse(
    @field:Schema(description = "지수 추세 list (default 4 지수: KOSPI · KOSDAQ · NASDAQ · S&P)")
    val indexes: List<IndexTrendResponse>,
)

@Schema(description = "시장 지수 캔들 한 행 — market_index_snapshot 실제 수집값 기반")
data class IndexCandleItem(
    @field:Schema(description = "캔들 시각")
    val ts: String,
    @field:Schema(description = "시가", example = "2680.12")
    val open: BigDecimal,
    @field:Schema(description = "고가", example = "2685.34")
    val high: BigDecimal,
    @field:Schema(description = "저가", example = "2678.90")
    val low: BigDecimal,
    @field:Schema(description = "종가", example = "2682.45")
    val close: BigDecimal,
    @field:Schema(description = "거래량. 지수 snapshot에는 없으면 0", example = "0")
    val volume: Long,
) {
    companion object {
        fun from(candle: IndexCandle): IndexCandleItem =
            IndexCandleItem(
                ts = candle.ts.toString(),
                open = candle.open,
                high = candle.high,
                low = candle.low,
                close = candle.close,
                volume = candle.volume,
            )
    }
}

@Schema(description = "시장 지수 캔들 응답 — /api/market/index/{code}/candles")
data class IndexCandlesResponse(
    @field:Schema(description = "KIS index code", example = "0001")
    val code: String,
    @field:Schema(description = "캔들 간격", example = "1m")
    val interval: String,
    @field:Schema(description = "오래된 → 최신 ASC 캔들 list")
    val candles: List<IndexCandleItem>,
)

@Schema(description = "섹터 snapshot 한 행")
data class SectorSnapshotItem(
    @field:Schema(description = "섹터 코드", example = "0024")
    val sectorCode: String,
    @field:Schema(description = "섹터명", example = "금융업")
    val sectorName: String,
    @field:Schema(description = "snapshot 시각")
    val ts: KtInstant,
    @field:Schema(description = "현재가", example = "1234.56")
    val price: BigDecimal,
    @field:Schema(description = "전일 대비 등락률 (%)", example = "1.2")
    val changeRate: BigDecimal,
    @field:Schema(description = "거래대금 (원)", nullable = true, example = "1234567890")
    val tradeValue: BigDecimal?,
) {
    companion object {
        fun from(snap: SectorSnapshot): SectorSnapshotItem =
            SectorSnapshotItem(
                sectorCode = snap.sectorCode,
                sectorName = snap.sectorName,
                ts = snap.ts,
                price = snap.price,
                changeRate = snap.changeRate,
                tradeValue = snap.tradeValue,
            )
    }
}

@Schema(description = "섹터 list 응답 — /api/market/sectors/snapshot")
data class SectorsSnapshotResponse(
    val sectors: List<SectorSnapshotItem>,
)

@Schema(description = "섹터 ranking 한 행 — todayTop · longTop 공용")
data class SectorRankEntryDto(
    @field:Schema(description = "섹터 코드", example = "0024")
    val sectorCode: String,
    @field:Schema(description = "섹터명", example = "금융업")
    val sectorName: String,
    @field:Schema(description = "등락률 (%)", example = "1.2")
    val changeRate: BigDecimal,
    @field:Schema(description = "거래대금 (원)", nullable = true)
    val tradeValue: BigDecimal?,
    @field:Schema(description = "정렬 점수 (todayTop=등락+거래대금 가중 · longTop=RS+우상향fit)", example = "1.05")
    val score: BigDecimal,
    @field:Schema(description = "보조 metric 한국어 string (tooltip 용)", example = "RS=1.12 · 우상향 fit=3/3")
    val metric: String,
) {
    companion object {
        fun from(entry: SectorRankEntry): SectorRankEntryDto =
            SectorRankEntryDto(
                sectorCode = entry.sectorCode,
                sectorName = entry.sectorName,
                changeRate = entry.changeRate,
                tradeValue = entry.tradeValue,
                score = entry.score,
                metric = entry.metric,
            )
    }
}

@Schema(description = "섹터 ranking 응답 — /api/market/sectors/ranking")
data class SectorRankingResponse(
    @field:Schema(description = "오늘 강세 sector 5 (등락 0.7 + 거래대금 0.3 가중)")
    val todayTop: List<SectorRankEntryDto>,
    @field:Schema(description = "장기 강세 sector 5 (Mansfield RS rsPeriod 기준 + 우상향 fit 가산점)")
    val longTop: List<SectorRankEntryDto>,
    @field:Schema(description = "응답 echo periods", example = "[5,20,60]")
    val periodsUsed: List<Int>,
    @field:Schema(description = "응답 echo rsPeriod", example = "60")
    val rsPeriod: Int,
    @field:Schema(description = "사용자 설정 적용 여부", example = "false")
    val isUserCustom: Boolean,
) {
    companion object {
        fun from(ranking: SectorRanking): SectorRankingResponse =
            SectorRankingResponse(
                todayTop = ranking.todayTop.map { SectorRankEntryDto.from(it) },
                longTop = ranking.longTop.map { SectorRankEntryDto.from(it) },
                periodsUsed = ranking.periodsUsed,
                rsPeriod = ranking.rsPeriod,
                isUserCustom = ranking.isUserCustom,
            )
    }
}
