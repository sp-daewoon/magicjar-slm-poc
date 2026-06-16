package kr.spdaewoon.magicjar.consumer.reconciliation

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMarketDailyUniverseJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * MARKET_DAILY universe provider (ADR 0118 R1 — 전종목 일봉 적재 대상).
 *
 * active managed universe(~25, WS 구독 — [ManagedCandleUniverseProvider])와 **물리 분리**된
 * 별도 universe. `instrument` 테이블 보통주(~3,000) 코드 집합을 산출하며,
 * **KIS WS 구독 0** — 일봉(1d) 적재 전용이다.
 *
 * ★ direction-guard LOCK (ADR 0118 §2-A·§7 + CLAUDE.md):
 *   - MARKET_DAILY는 WS 구독 universe가 **아니다**. 전종목 일봉 적재 ≠ 실시간 구독.
 *   - **watchlist에 자동 삽입하지 않는다.** 스크리너 후보 산출의 raw 데이터일 뿐이고,
 *     사용자가 검증·선택해야 STRATEGY/DETAIL owner로 승격된다.
 *   - 따라서 본 provider는 KIS WS slot·SubscriptionRouter·watchlist와 **무관**하다 (의존 0).
 *
 * 소비:
 *   - R2 저녁 20:05 멀티시세 snapshot 스케줄러 (consumer) — 본 universe code list를 30종목 chunk로 호출.
 *   - R3 1d gap catch-up (consumer) — listed_at clamp로 신규 상장 false gap 0.
 *
 * 보통주 필터(결재 ① LOCK)는 [ConsumerMarketDailyUniverseJpaRepository]가 native @Query로 수행한다
 * (securities_group='ST' + 우선주·SPAC 제외 → ETF·ETN·REIT·DR·WARRANT 자동 배제).
 */
@Component
class MarketDailyUniverseProvider(
    private val marketDailyUniverseRepository: ConsumerMarketDailyUniverseJpaRepository,
) {
    private val log = LoggerFactory.getLogger(MarketDailyUniverseProvider::class.java)

    companion object {
        private val CODE_REGEX = Regex("^\\d{6}$")
    }

    /**
     * MARKET_DAILY universe 보통주 코드 집합 (code ASC 결정적 순서).
     *
     * 결과는 6자리 코드만 통과(2차 방어). DB 조회 실패 시 빈 list (mock 합성 0 — 데이터 없음 path).
     */
    fun resolveCodes(): List<String> {
        val codes =
            try {
                marketDailyUniverseRepository.findMarketDailyCommonStockCodes()
            } catch (e: Exception) {
                log.warn("MARKET_DAILY universe 조회 실패 — 빈 결과 반환: err={}", e.message)
                return emptyList()
            }
        val filtered =
            codes
                .asSequence()
                .map { it.trim() }
                .filter { CODE_REGEX.matches(it) }
                .distinct()
                .toList()
        log.info("MARKET_DAILY universe 산출: 보통주 {}종목 (ETF·ETN·우선주·SPAC 제외 — ADR 0118 결재 ①)", filtered.size)
        return filtered
    }

    /** 보통주 카운트 (운영 관측·실측 evidence용). */
    fun count(): Long =
        try {
            marketDailyUniverseRepository.countMarketDailyCommonStock()
        } catch (e: Exception) {
            log.warn("MARKET_DAILY universe count 실패: err={}", e.message)
            0L
        }
}
