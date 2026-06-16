package kr.spdaewoon.magicjar.api.adapter.redis

import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentSearchResult
import kr.spdaewoon.magicjar.domain.instrument.port.InstrumentSearchIndex
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Redis 검색 인덱스 어댑터 (ADR 0017 §2-4 — T-IM1).
 *
 * 키 매트릭스 (dispatch-spec §5-5 LOCK):
 * - `instrument:search:prefix:{2자prefix}` — sorted set (member=code, score=0.0)
 *   → lex-order: member 형식 = "{name_norm}\t{code}" (이름 정렬 + code로 GET)
 * - `instrument:summary:code:{code}` — JSON (1일 TTL)
 * - `instrument:list:market:{market}` — JSON List<code> (1일 TTL)
 *
 * rebuild: 부팅 + sync 후 재구성. invalidate: sync 전 전체 DEL.
 * 2자 prefix bucket: 이름 앞 2자 + code 앞 2자 (한글·영문·숫자 모두 처리).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class InstrumentSearchIndexAdapter(
    private val redisTemplate: StringRedisTemplate,
) : InstrumentSearchIndex {
    private val log = LoggerFactory.getLogger(InstrumentSearchIndexAdapter::class.java)

    companion object {
        private val SEARCH_KEY_TTL = Duration.ofDays(1)
        private const val MAX_RESULTS = 50
    }

    /**
     * 전체 종목 목록으로 Redis 검색 인덱스 재구성.
     * 기존 키 invalidate 후 신규 키 SET.
     */
    override fun rebuild(instruments: List<Instrument>) {
        if (instruments.isEmpty()) {
            log.warn("[instrument-index] rebuild 입력 0건 — skip")
            return
        }
        val start = System.currentTimeMillis()

        // 1. 기존 인덱스 DEL
        invalidate()

        // 2. 새 인덱스 구성
        val zSetOps = redisTemplate.opsForZSet()
        val valueOps = redisTemplate.opsForValue()

        // 2자 prefix 버킷별 sorted set 구성
        val prefixBuckets = mutableMapOf<String, MutableSet<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>>>()

        instruments.forEach { instrument ->
            val nameNorm = normalizeForIndex(instrument.name)
            val member = "${nameNorm}\t${instrument.code}" // lex-order 정렬용

            // 이름 2자 prefix 버킷
            if (nameNorm.length >= 2) {
                val prefixKey = nameNorm.take(2)
                prefixBuckets
                    .getOrPut(prefixKey) { mutableSetOf() }
                    .add(
                        org.springframework.data.redis.core
                            .DefaultTypedTuple(member, 0.0),
                    )
            } else if (nameNorm.length == 1) {
                val prefixKey = nameNorm
                prefixBuckets
                    .getOrPut(prefixKey) { mutableSetOf() }
                    .add(
                        org.springframework.data.redis.core
                            .DefaultTypedTuple(member, 0.0),
                    )
            }

            // code prefix/contains 검색용. 예: 038110은 "038110", "38110" 둘 다 인덱싱.
            codeSearchTokens(instrument.code).forEach { codeToken ->
                val codePrefixKey = "code:${if (codeToken.length >= 2) codeToken.take(2) else codeToken}"
                val codeMember = "$codeToken\t${instrument.code}"
                prefixBuckets
                    .getOrPut(codePrefixKey) { mutableSetOf() }
                    .add(
                        org.springframework.data.redis.core
                            .DefaultTypedTuple(codeMember, 0.0),
                    )
            }

            // by-code JSON 캐시
            val json = """{"code":"${instrument.code}","name":"${escapeJson(
                instrument.name,
            )}","market":"${instrument.market.name}","sector":"${instrument.sector ?: ""}","active":${instrument.active}}"""
            valueOps.set(RedisKeyRegistry.Instrument.byCode(instrument.code), json, SEARCH_KEY_TTL)
        }

        // 버킷별 zadd
        prefixBuckets.forEach { (prefix, tuples) ->
            val key = RedisKeyRegistry.Instrument.searchPrefix(prefix)
            zSetOps.add(key, tuples)
            redisTemplate.expire(key, SEARCH_KEY_TTL)
        }

        // market별 list 캐시
        val byMarket = instruments.groupBy { it.market.name }
        byMarket.forEach { (market, list) ->
            val codes = list.map { it.code }
            val json = codes.joinToString(",", "[", "]") { "\"$it\"" }
            valueOps.set(RedisKeyRegistry.Instrument.list(market), json, SEARCH_KEY_TTL)
        }

        val elapsed = System.currentTimeMillis() - start
        log.info("[instrument-index] rebuild 완료: instruments={} buckets={} elapsed={}ms", instruments.size, prefixBuckets.size, elapsed)
    }

    /** Redis `instrument:search:*` + `instrument:list:market:*` + `instrument:summary:code:*` 전체 DEL. */
    override fun invalidate() {
        val patterns =
            listOf(
                RedisKeyRegistry.Instrument.searchPrefixPattern(),
                RedisKeyRegistry.Instrument.listPattern(),
                RedisKeyRegistry.Instrument.byCodePattern(),
            )
        var deleted = 0L
        patterns.forEach { pattern ->
            val keys = redisTemplate.keys(pattern)
            if (keys.isNotEmpty()) {
                deleted += redisTemplate.delete(keys)
            }
        }
        log.info("[instrument-index] invalidate: deleted={}건", deleted)
    }

    /**
     * Redis sorted set 검색: query prefix → ZRANGEBYLEX.
     * market 필터 있으면 추가 필터링.
     */
    override fun search(
        query: String,
        limit: Int,
        market: InstrumentMarket?,
    ): List<InstrumentSearchResult> {
        if (query.isBlank()) return emptyList()
        val normalizedQuery = normalizeForIndex(query)
        if (normalizedQuery.isEmpty()) return emptyList()

        val prefix = if (normalizedQuery.length >= 2) normalizedQuery.take(2) else normalizedQuery
        val zSetOps = redisTemplate.opsForZSet()
        val valueOps = redisTemplate.opsForValue()

        // 1. 이름 prefix 버킷 검색
        val nameKey = RedisKeyRegistry.Instrument.searchPrefix(prefix)
        val codeQuery = normalizedQuery
        val codeKey =
            RedisKeyRegistry.Instrument.searchPrefix(
                "code:${
                    if (codeQuery.length >= 2) {
                        codeQuery.take(2)
                    } else {
                        codeQuery
                    }
                }",
            )

        val candidates = mutableListOf<Pair<String, Double>>() // code, score

        // ZRANGEBYLEX: "[{normalizedQuery} ~ "[{normalizedQuery}￿"
        val nameMembers =
            zSetOps.rangeByLex(
                nameKey,
                org.springframework.data.redis.connection.RedisZSetCommands.Range
                    .range()
                    .gte("[$normalizedQuery")
                    .lte("[$normalizedQuery￿"),
            ) ?: emptySet()

        nameMembers.forEach { member ->
            val parts = member.split("\t")
            if (parts.size == 2) {
                val memberNameNorm = parts[0]
                val code = parts[1]
                // score 계산: 정확 prefix match면 높은 점수
                val score =
                    when {
                        memberNameNorm.startsWith(normalizedQuery) && memberNameNorm.length == normalizedQuery.length -> 1.0
                        memberNameNorm.startsWith(normalizedQuery) -> 0.9
                        else -> 0.7
                    }
                candidates.add(code to score)
            }
        }

        // code prefix 버킷도 검색
        val codeMembers =
            zSetOps.rangeByLex(
                codeKey,
                org.springframework.data.redis.connection.RedisZSetCommands.Range
                    .range()
                    .gte("[$codeQuery")
                    .lte("[$codeQuery￿"),
            ) ?: emptySet()

        codeMembers.forEach { member ->
            val parts = member.split("\t")
            if (parts.size == 2) {
                val code = parts[1]
                if (candidates.none { it.first == code }) {
                    candidates.add(code to 0.8)
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // 2. by-code GET pipeline으로 상세 정보 조회
        val results = mutableListOf<InstrumentSearchResult>()
        candidates
            .sortedByDescending { it.second }
            .take(maxOf(limit, MAX_RESULTS))
            .forEach { (code, score) ->
                val json = valueOps.get(RedisKeyRegistry.Instrument.byCode(code)) ?: return@forEach
                val result = parseByCodeJson(json, score) ?: return@forEach
                if (market == null || result.market == market) {
                    results.add(result)
                }
            }

        return results.take(limit)
    }

    // ── private helpers ─────────────────────────────────────────────────

    /** 종목명 정규화: 공백·점·괄호 제거 + lowercase. design §6-5. */
    private fun normalizeForIndex(name: String): String = name.replace(Regex("[\\s.·()（）]"), "").lowercase()

    private fun codeSearchTokens(code: String): Set<String> {
        val normalizedCode = normalizeForIndex(code)
        val withoutLeadingZeros = normalizedCode.trimStart('0')
        return setOf(normalizedCode, withoutLeadingZeros)
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** JSON 이스케이프 (단순 구현 — 큰따옴표만). */
    private fun escapeJson(s: String): String = s.replace("\"", "\\\"")

    /** by-code JSON 파싱 (단순 파서 — ObjectMapper 미사용으로 순환 의존 회피). */
    private fun parseByCodeJson(
        json: String,
        score: Double,
    ): InstrumentSearchResult? =
        runCatching {
            val code = Regex(""""code"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
            val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
            val marketStr = Regex(""""market"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
            val sector =
                Regex(""""sector"\s*:\s*"([^"]*)"""")
                    .find(json)
                    ?.groupValues
                    ?.get(1)
                    ?.takeIf { it.isNotEmpty() }
            val market = runCatching { InstrumentMarket.valueOf(marketStr) }.getOrElse { return null }
            InstrumentSearchResult(code = code, name = name, market = market, sector = sector, score = score)
        }.getOrNull()
}
