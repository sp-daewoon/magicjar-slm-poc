package kr.spdaewoon.magicjar.api.messaging

import kr.spdaewoon.magicjar.api.controller.trend.IndexTrendResponse
import kr.spdaewoon.magicjar.api.controller.trend.SectorRankingResponse
import kr.spdaewoon.magicjar.api.controller.trend.TrendPreferenceResponse
import kr.spdaewoon.magicjar.domain.trend.IndexTrend
import kr.spdaewoon.magicjar.domain.trend.SectorRanking
import kr.spdaewoon.magicjar.domain.trend.TrendPreference
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * 시장 추세 STOMP fanout publisher (ADR 0100 §2-6 + ADR 0101 §2-4 정합).
 *
 * ## topic 3건
 * - `/topic/market.index.{code}` — 4 지수 1분 cron 결과 push (R1 market-data 가 Kafka 머지 후 listener 활성)
 * - `/topic/market.sector.rank` — sector ranking 5분 캐시 갱신 push
 * - `/topic/market.user.{userId}.pref` — 사용자 pref 변경 시 즉시 push (다중 탭 sync)
 *
 * 본 R1 backend-core 는 publisher 만 발행. Kafka consumer / listener 는 R2 또는 R1 market-data 책임.
 *
 * AI 호출 0 — 본체 push only.
 */
@Component
class StompMarketNotifier(
    private val brokerMessagingTemplate: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(StompMarketNotifier::class.java)

    /** 지수 추세 fanout — code 별 topic. */
    fun publishIndexTrend(trend: IndexTrend) {
        val topic = StompDestinations.marketIndex(trend.code)
        brokerMessagingTemplate.convertAndSend(topic, IndexTrendResponse.from(trend))
        log.debug("STOMP {} 발행: trend={} cross={}", topic, trend.trendLabel, trend.crossSignal)
    }

    /** 섹터 ranking fanout — 단일 topic (전 사용자 공통). */
    fun publishSectorRanking(ranking: SectorRanking) {
        val topic = StompDestinations.MARKET_SECTOR_RANK
        brokerMessagingTemplate.convertAndSend(topic, SectorRankingResponse.from(ranking))
        log.debug("STOMP {} 발행: today={} long={}", topic, ranking.todayTop.size, ranking.longTop.size)
    }

    /** 사용자 pref 변경 fanout — userId 별 topic (다중 탭 sync 의무). */
    fun publishUserPreference(pref: TrendPreference) {
        val topic = StompDestinations.marketUserPref(pref.userId)
        brokerMessagingTemplate.convertAndSend(topic, TrendPreferenceResponse.from(pref))
        log.debug(
            "STOMP {} 발행: userId={} periods={} rs={}",
            topic,
            pref.userId,
            pref.maPeriods,
            pref.rsPeriod,
        )
    }
}
