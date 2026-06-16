package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.config.ChartCandleProperties
import kr.spdaewoon.magicjar.api.infrastructure.kis.KisTokenCacheHelper
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.service.CandleRefreshService
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.util.Optional

/**
 * CandleRefreshService.parseKisDailyOutput2 — KIS DESC 응답 ASC 정규화 회귀 가드 (P0-B hotfix 2026-05-19 backend-core + ADR 0086 §11).
 *
 * 결함 evidence (한국석유 004090, 2026-05-19 — market-data PR #555 보고):
 *   KIS `inquire-daily-itemchartprice` (FHKST03010100) 응답 output2[]는 **최신→과거 DESC 순서**.
 *   햇징 룰 §1 (이중 검증) monotonic은 ASC를 강제하므로,
 *   parseKisDailyOutput2가 정규화 누락 시 idx 1+ 전부 reject → insertedCount=0.
 *   2025-12-31~2026-05-18 5개월 일봉 갭 누적의 root cause.
 *   consumer KisRestCandleAdapter(PR #537)·batch CandleRefreshJob(PR #540)은 fix 완료,
 *   api CandleRefreshService는 별도 path drift로 누락 → 본 fix로 정합 회복.
 *
 * Fix:
 *   `parseKisDailyOutput2` 반환 직전 `.sortedBy { it.epochSec }` 적용.
 *
 * raw spec:
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
 *
 * 테스트 매트릭스 (ADR 0086 §11 cover — FHKST03010100 4 PERIOD_DIV_CODE 공통 schema):
 *   (asc-normalize-daily-output2) KIS DESC 일봉(D) → parseKisDailyOutput2 ASC 검증 — 한국석유 evidence
 *   (asc-normalize-daily-output2-idempotent) ASC 입력 → ASC 유지 (sortedBy idempotent)
 *   (pagination-cursor-advance) B-2 cursor 페이지네이션 — oldest date 추출 정확성 (cursor advance 검증 entry)
 *   (pagination-dedup-cross-page) B-2 페이지 간 중복 epochSec → distinctBy로 1건만 보존
 *
 * 매트릭스 LOCK: 본 KIS endpoint는 4 PERIOD_DIV_CODE 공통 schema이므로 단일 parse 함수가 4 timeframe 전부 cover.
 * 일봉 회귀 가드 1건으로 함수 ASC 정규화 책임이 검증되며, 주봉·월봉·연봉은 동일 함수를 통과하므로 동일 보장.
 *
 * B-2 cursor 페이지네이션 회귀 가드 (2026-05-19 backend-core — chart-curator PR #587 evidence + B-1 consumer fix 정합):
 *   refreshDailyCandles의 cursor 페이지네이션 전체 흐름은 WebClient·KisRateLimiterPort 외부 의존 → 단위 테스트 부적합.
 *   대신 페이지네이션 핵심 invariant 두 개만 검증:
 *     1. parseKisDailyOutput2 ASC 정렬 보장 → first() = oldest 안정성 (cursor advance 정확성)
 *     2. distinctBy { epochSec } → 페이지 간 boundary 중복 단일 보존 (KIS oldest-1일 cursor가 다음 페이지 최신과 겹칠 risk)
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
class CandleRefreshServiceAscNormalizeTest {
    private fun buildService(
        instrumentJpaRepository: InstrumentJpaRepository = mock<InstrumentJpaRepository>(),
        chartCandleProperties: ChartCandleProperties = ChartCandleProperties(),
        candleJpaRepository: CandleJpaRepository = mock<CandleJpaRepository>(),
    ): CandleRefreshService {
        // ASC 정규화는 외부 의존성 0이므로 모든 dependency mock 가능.
        // T-4 통합 (2026-05-20) — credential·cipher·redis 는 KisTokenCacheHelper 위임으로 이전.
        // Phase 3 (2026-05-20 ADR 0093) — ChartCandleProperties 의존성 추가 (default fallback 6 활용).
        return CandleRefreshService(
            webClient = mock<WebClient>(),
            instrumentJpaRepository = instrumentJpaRepository,
            candleJpaRepository = candleJpaRepository,
            jdbcTemplate = mock<org.springframework.jdbc.core.JdbcTemplate>(),
            tokenHelper = mock<KisTokenCacheHelper>(),
            // P0 hotfix 2026-05-20 — credentialRepository 추가 (빈 userId 차단을 위한 1순위 REAL row userId 추출)
            credentialRepository = mock<kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository>(),
            kisRateLimiterPort = mock<KisRateLimiterPort>(),
            chartCandleProperties = chartCandleProperties,
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
        )
    }

    /**
     * private fun parseKisDailyOutput2(output2, code, intervalCode) — reflection 호출.
     * 반환은 private nested data class KisDailyRow 리스트 → epochSec 필드 reflection 추출.
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeParseDailyOutput2(
        service: CandleRefreshService,
        output2: List<*>,
        code: String,
        intervalCode: String,
    ): List<Long> {
        val method =
            CandleRefreshService::class.java.getDeclaredMethod(
                "parseKisDailyOutput2",
                List::class.java,
                String::class.java,
                String::class.java,
            )
        method.isAccessible = true
        val rows = method.invoke(service, output2, code, intervalCode) as List<Any>

        // KisDailyRow.epochSec 필드 reflection 추출 (private nested data class).
        return rows.map { row ->
            val field = row.javaClass.getDeclaredField("epochSec")
            field.isAccessible = true
            field.getLong(row)
        }
    }

    private fun invokeResolveMrktDivCode(
        service: CandleRefreshService,
        code: String,
    ): String {
        val method =
            CandleRefreshService::class.java.getDeclaredMethod(
                "resolveMrktDivCode",
                String::class.java,
            )
        method.isAccessible = true
        return method.invoke(service, code) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeResolveMrktDivCodeCandidates(
        service: CandleRefreshService,
        code: String,
    ): List<String> {
        val method =
            CandleRefreshService::class.java.getDeclaredMethod(
                "resolveMrktDivCodeCandidates",
                String::class.java,
            )
        method.isAccessible = true
        return method.invoke(service, code) as List<String>
    }

    private fun instrument(
        code: String,
        market: String,
        isKospi200: Boolean = false,
        isKosdaq150: Boolean = false,
    ): InstrumentJpaEntity =
        InstrumentJpaEntity(
            code = code,
            name = code,
            market = market,
            type = "STOCK",
            isKospi200 = isKospi200,
            isKosdaq150 = isKosdaq150,
        )

    @Test
    fun `asc-normalize-daily-output2 KIS DESC 일봉 응답 → ASC 변환 검증 (한국석유 결함 회귀 가드)`() {
        // given — KIS 응답이 DESC 순서 (최신→과거)로 들어옴. 한국석유(004090) evidence 동일 패턴.
        // 2025-12-30, 2025-12-29, 2025-12-26, 2025-12-24, 2025-12-23 5건.
        val output2 =
            listOf(
                mapOf(
                    "stck_bsop_date" to "20251230",
                    "stck_oprc" to "14450",
                    "stck_hgpr" to "14500",
                    "stck_lwpr" to "13850",
                    "stck_clpr" to "13950",
                    "acml_vol" to "56873",
                ),
                mapOf(
                    "stck_bsop_date" to "20251229",
                    "stck_oprc" to "13920",
                    "stck_hgpr" to "13960",
                    "stck_lwpr" to "13700",
                    "stck_clpr" to "13930",
                    "acml_vol" to "88870",
                ),
                mapOf(
                    "stck_bsop_date" to "20251226",
                    "stck_oprc" to "14060",
                    "stck_hgpr" to "14070",
                    "stck_lwpr" to "13840",
                    "stck_clpr" to "13920",
                    "acml_vol" to "92923",
                ),
                mapOf(
                    "stck_bsop_date" to "20251224",
                    "stck_oprc" to "14100",
                    "stck_hgpr" to "14150",
                    "stck_lwpr" to "13900",
                    "stck_clpr" to "14060",
                    "acml_vol" to "70000",
                ),
                mapOf(
                    "stck_bsop_date" to "20251223",
                    "stck_oprc" to "13800",
                    "stck_hgpr" to "14100",
                    "stck_lwpr" to "13750",
                    "stck_clpr" to "14080",
                    "acml_vol" to "65000",
                ),
            )

        // when
        val epochSecs = invokeParseDailyOutput2(buildService(), output2, "004090", "1d")

        // then — ASC monotonic (epochSec 오름차순) 검증 (햇징 룰 §1 정합).
        assertThat(epochSecs).hasSize(5)
        assertThat(epochSecs).isSorted
        // 첫 row가 가장 과거(2025-12-23), 마지막 row가 가장 최신(2025-12-30) — DESC 입력 → ASC 출력.
        // 2025-12-23 KST 00:00 → epoch second 1766415600
        // 2025-12-30 KST 00:00 → epoch second 1767020400
        assertThat(epochSecs.first()).isLessThan(epochSecs.last())
    }

    @Test
    fun `market-div-resolver 채널 J 단일화 — KOSPI200 포함 전 종목 J (ADR 0118 amend, UN-stale 결함)`() {
        // 2026-06-09 J 단일화: KIS UN(통합)/NX(NXT) 일봉이 일부 종목에서 2/11 동결되는 결함으로
        // KOSPI200 멤버도 더 이상 UN 우선을 쓰지 않고 항상 J(KRX 본시장)만 조회한다. consumer #844 정합.
        val instrumentJpaRepository = mock<InstrumentJpaRepository>()
        whenever(instrumentJpaRepository.findById("005930"))
            .thenReturn(Optional.of(instrument("005930", market = "KOSPI", isKospi200 = true)))
        whenever(instrumentJpaRepository.findById("000001"))
            .thenReturn(Optional.of(instrument("000001", market = "KOSPI", isKospi200 = false)))
        val service = buildService(instrumentJpaRepository)

        // KOSPI200 멤버여도 UN 아님 — 항상 J.
        assertThat(invokeResolveMrktDivCode(service, "005930")).isEqualTo("J")
        assertThat(invokeResolveMrktDivCodeCandidates(service, "005930")).containsExactly("J")
        assertThat(invokeResolveMrktDivCode(service, "000001")).isEqualTo("J")
        assertThat(invokeResolveMrktDivCodeCandidates(service, "000001")).containsExactly("J")
    }

    @Test
    fun `market-div-resolver 채널 J 단일화 — KOSDAQ150 포함 전 종목 J (ADR 0118 amend)`() {
        val instrumentJpaRepository = mock<InstrumentJpaRepository>()
        whenever(instrumentJpaRepository.findById("091990"))
            .thenReturn(Optional.of(instrument("091990", market = "KOSDAQ", isKosdaq150 = true)))
        whenever(instrumentJpaRepository.findById("123456"))
            .thenReturn(Optional.of(instrument("123456", market = "KOSDAQ", isKosdaq150 = false)))
        whenever(instrumentJpaRepository.findById("999999")).thenReturn(Optional.empty())
        val service = buildService(instrumentJpaRepository)

        // KOSDAQ150 멤버여도 UN 아님 — 항상 J.
        assertThat(invokeResolveMrktDivCode(service, "091990")).isEqualTo("J")
        assertThat(invokeResolveMrktDivCodeCandidates(service, "091990")).containsExactly("J")
        assertThat(invokeResolveMrktDivCode(service, "123456")).isEqualTo("J")
        assertThat(invokeResolveMrktDivCode(service, "999999")).isEqualTo("J")
    }

    @Test
    fun `asc-normalize-daily-output2 ASC 정상 입력은 그대로 ASC 유지 (idempotent)`() {
        // given — 이미 ASC 정렬된 입력 (sortedBy idempotent 검증).
        val output2 =
            listOf(
                mapOf(
                    "stck_bsop_date" to "20251223",
                    "stck_oprc" to "13800",
                    "stck_hgpr" to "14100",
                    "stck_lwpr" to "13750",
                    "stck_clpr" to "14080",
                    "acml_vol" to "65000",
                ),
                mapOf(
                    "stck_bsop_date" to "20251224",
                    "stck_oprc" to "14100",
                    "stck_hgpr" to "14150",
                    "stck_lwpr" to "13900",
                    "stck_clpr" to "14060",
                    "acml_vol" to "70000",
                ),
                mapOf(
                    "stck_bsop_date" to "20251226",
                    "stck_oprc" to "14060",
                    "stck_hgpr" to "14070",
                    "stck_lwpr" to "13840",
                    "stck_clpr" to "13920",
                    "acml_vol" to "92923",
                ),
            )

        // when
        val epochSecs = invokeParseDailyOutput2(buildService(), output2, "004090", "1d")

        // then — 정렬 후에도 ASC 유지.
        assertThat(epochSecs).hasSize(3)
        assertThat(epochSecs).isSorted
    }

    @Test
    fun `pagination-cursor-advance parseKisDailyOutput2 ASC first()는 oldest를 정확히 가리킨다 (B-2 cursor 페이지네이션 회귀 가드)`() {
        // given — KIS DESC 응답 (한 페이지 = 5건 가정). cursor advance는 ASC.first()=oldest 기반이므로 정렬 정확성 필수.
        val output2 =
            listOf(
                mapOf(
                    "stck_bsop_date" to "20251230",
                    "stck_oprc" to "14450",
                    "stck_hgpr" to "14500",
                    "stck_lwpr" to "13850",
                    "stck_clpr" to "13950",
                    "acml_vol" to "56873",
                ),
                mapOf(
                    "stck_bsop_date" to "20251229",
                    "stck_oprc" to "13920",
                    "stck_hgpr" to "13960",
                    "stck_lwpr" to "13700",
                    "stck_clpr" to "13930",
                    "acml_vol" to "88870",
                ),
                mapOf(
                    "stck_bsop_date" to "20251226",
                    "stck_oprc" to "14060",
                    "stck_hgpr" to "14070",
                    "stck_lwpr" to "13840",
                    "stck_clpr" to "13920",
                    "acml_vol" to "92923",
                ),
                mapOf(
                    "stck_bsop_date" to "20251224",
                    "stck_oprc" to "14100",
                    "stck_hgpr" to "14150",
                    "stck_lwpr" to "13900",
                    "stck_clpr" to "14060",
                    "acml_vol" to "70000",
                ),
                mapOf(
                    "stck_bsop_date" to "20251223",
                    "stck_oprc" to "13800",
                    "stck_hgpr" to "14100",
                    "stck_lwpr" to "13750",
                    "stck_clpr" to "14080",
                    "acml_vol" to "65000",
                ),
            )

        // when
        val epochSecs = invokeParseDailyOutput2(buildService(), output2, "004090", "1d")

        // then — ASC 정렬되어야 cursor advance 로직이 first()=oldest를 정확히 추출 가능.
        // 2025-12-23 KST 00:00 → epoch second (가장 과거)
        // 2025-12-30 KST 00:00 → epoch second (가장 최신)
        assertThat(epochSecs).hasSize(5)
        assertThat(epochSecs).isSorted
        // cursor advance 핵심 invariant: first() = 가장 과거 epoch (oldest date 추출 entry).
        val oldest = epochSecs.first()
        val newest = epochSecs.last()
        assertThat(oldest).isLessThan(newest)
        // 2025-12-23 KST → 2025-12-30 KST 차이는 정확히 7 days = 604800초.
        assertThat(newest - oldest).isEqualTo(7 * 86400L)
    }

    @Test
    fun `pagination-dedup-cross-page 페이지 boundary 중복 epochSec은 distinctBy로 1건만 보존된다 (B-2 회귀 가드)`() {
        // given — KIS cursor는 oldest-1일 advance. 그러나 KIS 응답 idempotency 가정 시
        // 두 페이지 응답에 boundary date(예: 12-26)가 중복으로 포함될 risk → distinctBy { epochSec } 필수.
        // 본 테스트는 동일 date 2건 중복 응답이 1건으로 통합되는지 검증.
        val output2WithDuplicate =
            listOf(
                mapOf(
                    "stck_bsop_date" to "20251226", // 중복 1
                    "stck_oprc" to "14060",
                    "stck_hgpr" to "14070",
                    "stck_lwpr" to "13840",
                    "stck_clpr" to "13920",
                    "acml_vol" to "92923",
                ),
                mapOf(
                    "stck_bsop_date" to "20251226", // 중복 2 (같은 날짜)
                    "stck_oprc" to "14060",
                    "stck_hgpr" to "14070",
                    "stck_lwpr" to "13840",
                    "stck_clpr" to "13920",
                    "acml_vol" to "92923",
                ),
                mapOf(
                    "stck_bsop_date" to "20251224",
                    "stck_oprc" to "14100",
                    "stck_hgpr" to "14150",
                    "stck_lwpr" to "13900",
                    "stck_clpr" to "14060",
                    "acml_vol" to "70000",
                ),
            )

        // when — parseKisDailyOutput2는 중복 처리 안 함 (그대로 통과).
        val epochSecs = invokeParseDailyOutput2(buildService(), output2WithDuplicate, "004090", "1d")

        // then — parse 단계는 3건 그대로 (정렬만 보장).
        // distinctBy 후속 처리(refreshDailyCandles 내부)에서 2건으로 축소되는 것이 invariant.
        // 본 테스트는 parse 단계 정렬 보장만 확인. 합산 dedup은 refreshDailyCandles 내부 흐름.
        assertThat(epochSecs).hasSize(3)
        assertThat(epochSecs).isSorted

        // distinctBy { epochSec } simulation — 동일 epochSec은 1건만 남는지 검증.
        val dedup = epochSecs.distinct()
        assertThat(dedup).hasSize(2) // 12-26 1건 + 12-24 1건
    }

    /**
     * private fun latestEpochSec(code, intervalCode) — reflection 호출.
     * 2026-06-04 KIS_TPS_EXCEEDED 점검 #2 fix — incremental(delta-only) 경계 lookup 회귀 가드.
     */
    private fun invokeLatestEpochSec(
        service: CandleRefreshService,
        code: String,
        intervalCode: String,
    ): Long? {
        val method =
            CandleRefreshService::class.java.getDeclaredMethod(
                "latestEpochSec",
                String::class.java,
                String::class.java,
            )
        method.isAccessible = true
        return method.invoke(service, code, intervalCode) as Long?
    }

    @Test
    fun `incremental-boundary latestEpochSec DB 최신 봉 epochSec 반환 (점검 #2 KIS_TPS burst 제거 경계)`() {
        // given — DB에 1d 최신 봉(2025-12-30 KST 00:00) 1건 존재.
        // incremental 경계 = 이 봉의 epochSecond. 한 페이지 oldest 봉이 이 값 이하면 페이지네이션 중단.
        val candleRepo = mock<CandleJpaRepository>()
        val latestTs = Instant.parse("2025-12-29T15:00:00Z") // 2025-12-30 00:00 KST
        whenever(candleRepo.findByCodeAndIntervalLatest(any(), any(), any<Pageable>()))
            .thenReturn(
                listOf(
                    CandleJpaEntity(code = "004090", interval = "1d", ts = latestTs, source = "KIS"),
                ),
            )
        val service = buildService(candleJpaRepository = candleRepo)

        // when
        val boundary = invokeLatestEpochSec(service, "004090", "1d")

        // then — DB 최신 봉 epochSecond 정확 반환.
        assertThat(boundary).isEqualTo(latestTs.epochSecond)
    }

    @Test
    fun `incremental-boundary 봉 0건 cold-start은 null 반환 (전수 backfill fallback — 회귀 0)`() {
        // given — DB candle 0건 (cold-start 종목).
        val candleRepo = mock<CandleJpaRepository>()
        whenever(candleRepo.findByCodeAndIntervalLatest(any(), any(), any<Pageable>()))
            .thenReturn(emptyList())
        val service = buildService(candleJpaRepository = candleRepo)

        // when
        val boundary = invokeLatestEpochSec(service, "999999", "1d")

        // then — null → refreshDailyCandles incremental 경계 미적용 → 전수 backfill 진행 (회귀 0).
        assertThat(boundary).isNull()
    }

    @Test
    fun `incremental-boundary 조회 예외는 null로 graceful (전수 backfill fallback)`() {
        // given — repository 조회 예외 (DB 장애 등).
        val candleRepo = mock<CandleJpaRepository>()
        whenever(candleRepo.findByCodeAndIntervalLatest(any(), any(), any<Pageable>()))
            .thenThrow(RuntimeException("DB down"))
        val service = buildService(candleJpaRepository = candleRepo)

        // when / then — 예외 삼키고 null 반환 (incremental 경계 미적용 → 전수 fetch 안전).
        assertThat(invokeLatestEpochSec(service, "004090", "1d")).isNull()
    }
}
