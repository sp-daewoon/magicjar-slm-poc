package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentSearchResult
import kr.spdaewoon.magicjar.domain.instrument.port.InstrumentRepository
import kr.spdaewoon.magicjar.domain.instrument.port.InstrumentSearchIndex
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 종목 검색 서비스 (ADR 0017 §2-4 — T-IM1).
 *
 * 검색 순서:
 * 1. Redis InstrumentSearchIndex (ZRANGEBYLEX — timeout 100ms)
 * 2. Redis miss 또는 0건 → DB InstrumentRepository.searchByPgTrgm fallback (timeout 1s)
 *
 * 입력 정규화: 공백·점 제거 + lowercase (design §6-5).
 * 최소 쿼리 길이: 1자 (1자 미만이면 빈 배열 반환).
 * 최대 limit: 20 (default 10).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class InstrumentSearchService(
    private val searchIndex: InstrumentSearchIndex,
    private val instrumentRepository: InstrumentRepository,
) {
    private val log = LoggerFactory.getLogger(InstrumentSearchService::class.java)

    companion object {
        private const val REDIS_TIMEOUT_MS = 100L
        private const val DB_TIMEOUT_MS = 1000L
        private const val MAX_LIMIT = 20
        private const val DEFAULT_LIMIT = 10
    }

    /**
     * 종목 검색 (자동완성용).
     *
     * @param query 검색어 (한글·영문·숫자 혼용 가능)
     * @param limit 결과 수 (1~20, default 10)
     * @param market market 필터 (null = 전체)
     * @return score 내림차순 정렬된 InstrumentSearchResult 목록
     */
    fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        market: InstrumentMarket? = null,
    ): List<InstrumentSearchResult> {
        val normalizedQuery = normalizeQuery(query)
        if (normalizedQuery.isEmpty()) return emptyList()

        val effectiveLimit = limit.coerceIn(1, MAX_LIMIT)

        // 1. Redis 검색 (timeout 100ms)
        val redisResults =
            try {
                withTimeout(REDIS_TIMEOUT_MS) {
                    searchIndex.search(normalizedQuery, effectiveLimit, market)
                }
            } catch (e: TimeoutException) {
                log.warn("[instrument-search] Redis timeout: query={} elapsed={}ms", normalizedQuery, REDIS_TIMEOUT_MS)
                emptyList()
            } catch (e: Exception) {
                log.warn("[instrument-search] Redis 검색 오류: query={} err={}", normalizedQuery, e.message)
                emptyList()
            }

        if (redisResults.isNotEmpty()) {
            log.debug("[instrument-search] Redis hit: query={} results={}", normalizedQuery, redisResults.size)
            return redisResults.sortedByDescending { it.score }.take(effectiveLimit)
        }

        // 2. DB pg_trgm fallback (timeout 1s)
        log.debug("[instrument-search] Redis miss → DB fallback: query={}", normalizedQuery)
        return try {
            withTimeout(DB_TIMEOUT_MS) {
                instrumentRepository.searchByPgTrgm(normalizedQuery, effectiveLimit, market)
            }
        } catch (e: TimeoutException) {
            log.warn("[instrument-search] DB fallback timeout: query={} elapsed={}ms", normalizedQuery, DB_TIMEOUT_MS)
            emptyList()
        } catch (e: Exception) {
            log.warn("[instrument-search] DB fallback 오류: query={} err={}", normalizedQuery, e.message)
            emptyList()
        }
    }

    /**
     * 검색 인덱스 재구성 (InstrumentSyncJob 또는 수동 trigger 후 호출).
     * 비동기 호출 권장 (검색 latency 비차단).
     *
     * 사용자 요청: 보통주+우선주만 노출 (KIS securities_group='ST').
     * ETF·ETN·REIT·WARRANT·DR·외국주식·펀드는 인덱스에서 제외.
     */
    fun rebuildIndex() {
        val instruments = instrumentRepository.findActiveStocks()
        if (instruments.isEmpty()) {
            log.warn("[instrument-search] rebuildIndex: active ST instrument 0건 — skip")
            return
        }
        searchIndex.rebuild(instruments)
        log.info("[instrument-search] rebuildIndex 완료: stInstruments={}", instruments.size)
    }

    // ── private helpers ─────────────────────────────────────────────────

    /** 쿼리 정규화: 공백·점·괄호 제거 + lowercase. design §6-5. */
    private fun normalizeQuery(query: String): String = query.trim().replace(Regex("[\\s.·()（）]"), "").lowercase()

    /**
     * 간단한 timeout wrapper (Java Future 없이 직접 구현).
     * 호출이 100ms / 1000ms 안에 완료되지 않으면 TimeoutException.
     */
    private fun <T> withTimeout(
        timeoutMs: Long,
        block: () -> T,
    ): T {
        val future =
            java.util.concurrent.CompletableFuture.supplyAsync {
                block()
            }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }
}
