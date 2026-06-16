package kr.spdaewoon.magicjar.api.account

import kr.spdaewoon.magicjar.api.infrastructure.kis.ApiKisBalanceQueryAdapter
import kr.spdaewoon.magicjar.api.infrastructure.kis.KisTokenCacheHelper
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialId
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

/**
 * ApiKisBalanceQueryAdapter 단위 테스트 — T-ACCT Phase 1B (ADR 0081 §2-6).
 *
 * KIS-first raw fetch 인용 (헌법 §3 LOCK · spec §3-1):
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py
 *
 * 검증 대상:
 * - output1 보유 종목 8 컬럼 파싱 + qty 0 필터
 * - output2 11 필드 → AccountBalance 매핑 (한글 의미 매트릭스 LOCK)
 * - output2 null/empty → mock 합성 0 LOCK (IllegalStateException 발행)
 * - asst_icdc_amt / asst_icdc_erng_rt 옵션 필드 null 허용
 *
 * WebClient 호출 흐름은 통합 테스트 영역 (본 단위 테스트는 파싱 책임만 검증).
 */
class ApiKisBalanceQueryAdapterTest {
    private val webClient: WebClient = mock()
    private val secretCipher: SecretCipher = mock()
    private val credentialRepository: UserKisCredentialJpaRepository = mock()
    private val tokenHelper: KisTokenCacheHelper = mock()

    // T-D-2 Rate Limit 합류 — 본 단위 테스트는 파싱 책임만 검증하므로 NoOp mock으로 충분.
    private val kisRateLimiterPort: KisRateLimiterPort = mock()

    private val sut =
        ApiKisBalanceQueryAdapter(
            webClient = webClient,
            secretCipher = secretCipher,
            credentialRepository = credentialRepository,
            tokenHelper = tokenHelper,
            kisRateLimiterPort = kisRateLimiterPort,
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )

    @Test
    fun `output2 11 필드 매핑 — KIS 한글 의미 매트릭스 LOCK`() {
        val response =
            mapOf<String, Any>(
                "output2" to
                    listOf(
                        mapOf(
                            // dnca_tot_amt          → 예수금 총금액
                            "dnca_tot_amt" to "10000000",
                            // nxdy_excc_amt         → D+1 예수금
                            "nxdy_excc_amt" to "10000000",
                            // prvs_rcdl_excc_amt    → D+2 예수금 (실가용)
                            "prvs_rcdl_excc_amt" to "10000000",
                            // tot_evlu_amt          → 총 평가금액 (자산)
                            "tot_evlu_amt" to "12000000",
                            // nass_amt              → 순자산금액
                            "nass_amt" to "12000000",
                            // pchs_amt_smtl_amt     → 매입금액 합계
                            "pchs_amt_smtl_amt" to "1900000",
                            // evlu_amt_smtl_amt     → 평가금액 합계
                            "evlu_amt_smtl_amt" to "2000000",
                            // evlu_pfls_smtl_amt    → 평가손익 합계
                            "evlu_pfls_smtl_amt" to "100000",
                            // asst_icdc_amt         → 자산증감액 (전일 대비)
                            "asst_icdc_amt" to "50000",
                            // asst_icdc_erng_rt     → 자산증감 수익률 %
                            "asst_icdc_erng_rt" to "0.42",
                        ),
                    ),
            )

        val balance = sut.parseOutput2(response)

        assertThat(balance.cashBalance).isEqualByComparingTo("10000000")
        assertThat(balance.cashD1).isEqualByComparingTo("10000000")
        assertThat(balance.cashD2).isEqualByComparingTo("10000000")
        assertThat(balance.totalEvaluation).isEqualByComparingTo("12000000")
        assertThat(balance.netAssetValue).isEqualByComparingTo("12000000")
        assertThat(balance.totalPurchaseAmount).isEqualByComparingTo("1900000")
        assertThat(balance.totalHoldingEvaluation).isEqualByComparingTo("2000000")
        assertThat(balance.totalProfitLoss).isEqualByComparingTo("100000")
        assertThat(balance.assetIncreaseDecrease).isEqualByComparingTo("50000")
        assertThat(balance.assetIncreaseDecreaseRate).isEqualByComparingTo("0.42")
    }

    @Test
    fun `output2 Map 형식 응답 — List 외에 Map 단일 객체도 흡수`() {
        // KIS 응답이 단일 Map인 경우 (일부 환경에서 단건 응답)
        val response =
            mapOf<String, Any>(
                "output2" to
                    mapOf(
                        "dnca_tot_amt" to "5000000",
                        "nxdy_excc_amt" to "5000000",
                        "prvs_rcdl_excc_amt" to "5000000",
                        "tot_evlu_amt" to "5000000",
                        "nass_amt" to "5000000",
                        "pchs_amt_smtl_amt" to "0",
                        "evlu_amt_smtl_amt" to "0",
                        "evlu_pfls_smtl_amt" to "0",
                    ),
            )

        val balance = sut.parseOutput2(response)

        assertThat(balance.cashBalance).isEqualByComparingTo("5000000")
        // 옵션 필드 부재 → null
        assertThat(balance.assetIncreaseDecrease).isNull()
        assertThat(balance.assetIncreaseDecreaseRate).isNull()
    }

    @Test
    fun `output2 null — mock 합성 0 LOCK (IllegalStateException 발행)`() {
        val response = mapOf<String, Any>("output1" to emptyList<Map<String, Any>>())

        assertThatThrownBy { sut.parseOutput2(response) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("output2 응답 부재")
            .hasMessageContaining("mock 합성 0 LOCK")
    }

    @Test
    fun `output2 empty list — mock 합성 0 LOCK (IllegalStateException 발행)`() {
        val response = mapOf<String, Any>("output2" to emptyList<Map<String, Any>>())

        assertThatThrownBy { sut.parseOutput2(response) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("output2 응답 부재")
    }

    @Test
    fun `output1 8 컬럼 매핑 — pdno·prdt_name·hldg_qty·pchs_avg_pric·prpr·evlu_amt·evlu_pfls_amt·evlu_pfls_rt`() {
        val response =
            mapOf<String, Any>(
                "output1" to
                    listOf(
                        mapOf(
                            "pdno" to "005930",
                            "prdt_name" to "삼성전자",
                            "hldg_qty" to "10",
                            "pchs_avg_pric" to "70000",
                            "prpr" to "71000",
                            "evlu_amt" to "710000",
                            "evlu_pfls_amt" to "10000",
                            "evlu_pfls_rt" to "1.43",
                        ),
                        mapOf(
                            "pdno" to "000660",
                            "prdt_name" to "SK하이닉스",
                            "hldg_qty" to "5",
                            "pchs_avg_pric" to "240000",
                            "prpr" to "258000",
                            "evlu_amt" to "1290000",
                            "evlu_pfls_amt" to "90000",
                            "evlu_pfls_rt" to "7.50",
                        ),
                    ),
            )

        val holdings = sut.parseOutput1(response)

        assertThat(holdings).hasSize(2)
        val samsung = holdings[0]
        assertThat(samsung.code).isEqualTo("005930")
        assertThat(samsung.name).isEqualTo("삼성전자")
        assertThat(samsung.quantity).isEqualTo(10)
        assertThat(samsung.avgPrice).isEqualByComparingTo(BigDecimal("70000"))
        assertThat(samsung.currentPrice).isEqualByComparingTo(BigDecimal("71000"))
        assertThat(samsung.evaluationAmount).isEqualByComparingTo(BigDecimal("710000"))
        assertThat(samsung.profitLoss).isEqualByComparingTo(BigDecimal("10000"))
        assertThat(samsung.profitLossRate).isEqualByComparingTo(BigDecimal("1.43"))

        val hynix = holdings[1]
        assertThat(hynix.code).isEqualTo("000660")
        assertThat(hynix.name).isEqualTo("SK하이닉스")
    }

    @Test
    fun `output1 qty 0 항목 필터 — Bootstrap 어댑터와 동일 규칙`() {
        val response =
            mapOf<String, Any>(
                "output1" to
                    listOf(
                        mapOf(
                            "pdno" to "005930",
                            "prdt_name" to "삼성전자",
                            "hldg_qty" to "10",
                            "pchs_avg_pric" to "70000",
                        ),
                        mapOf(
                            "pdno" to "000660",
                            "prdt_name" to "SK하이닉스",
                            "hldg_qty" to "0",
                            "pchs_avg_pric" to "240000",
                        ),
                    ),
            )

        val holdings = sut.parseOutput1(response)

        // qty 0인 SK하이닉스는 제외
        assertThat(holdings).hasSize(1)
        assertThat(holdings[0].code).isEqualTo("005930")
    }

    @Test
    fun `output1 부재 — 빈 리스트 (정상 케이스)`() {
        val response = mapOf<String, Any>("output2" to listOf(mapOf("dnca_tot_amt" to "0")))

        val holdings = sut.parseOutput1(response)

        assertThat(holdings).isEmpty()
    }

    @Test
    fun `output1 누락 필드 — 0 fallback (KIS 일부 필드 누락 graceful)`() {
        val response =
            mapOf<String, Any>(
                "output1" to
                    listOf(
                        mapOf(
                            "pdno" to "005930",
                            "hldg_qty" to "10",
                            // prdt_name·pchs_avg_pric·prpr·evlu_amt·... 누락
                        ),
                    ),
            )

        val holdings = sut.parseOutput1(response)

        assertThat(holdings).hasSize(1)
        assertThat(holdings[0].name).isEqualTo("")
        assertThat(holdings[0].avgPrice).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(holdings[0].currentPrice).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(holdings[0].profitLossRate).isEqualByComparingTo(BigDecimal.ZERO)
    }

    // ── 2026-05-21 hotfix (backend-core) — EGW00123 self-heal e2e ──────────────────────────────
    //
    // dogfooding evidence (사용자 2026-05-21):
    //   GET /api/account/positions?userId=74683223&accountType=REAL 502 무한 폭주.
    //   docker logs magicjar-api 20+ 회 반복:
    //     ERROR account KIS upstream 실패: status=500 body={"rt_cd":"1","msg1":"기간이 만료된 token 입니다.","msg_cd":"EGW00123"}
    //   Redis TTL 살아있어 helper fast path가 stale 토큰 무한 재사용 →
    //   수동 `redis-cli DEL "kis:oauth:token:user:74683223:account:REAL"` 후에야 신규 발급 + 정상 응답 복귀.

    /** EGW00123 응답 body (KIS 실제 wire format). */
    private val egw00123Body = """{"rt_cd":"1","msg1":"기간이 만료된 token 입니다.","msg_cd":"EGW00123"}"""

    /** 정상 응답 body — output2 11 필드 + output1 1 종목. */
    private val successBody =
        """
        {
          "rt_cd":"0",
          "msg1":"정상처리",
          "output1":[{
            "pdno":"005930","prdt_name":"삼성전자","hldg_qty":"10",
            "pchs_avg_pric":"70000","prpr":"71000","evlu_amt":"710000",
            "evlu_pfls_amt":"10000","evlu_pfls_rt":"1.43"
          }],
          "output2":[{
            "dnca_tot_amt":"10000000","nxdy_excc_amt":"10000000","prvs_rcdl_excc_amt":"10000000",
            "tot_evlu_amt":"12000000","nass_amt":"12000000",
            "pchs_amt_smtl_amt":"1900000","evlu_amt_smtl_amt":"2000000","evlu_pfls_smtl_amt":"100000",
            "asst_icdc_amt":"50000","asst_icdc_erng_rt":"0.42"
          }]
        }
        """.trimIndent()

    private fun stubCredential(testCredRepo: UserKisCredentialJpaRepository) {
        val cred =
            UserKisCredentialJpaEntity(
                id = UserKisCredentialId(userId = "u-001", accountType = AccountType.REAL.name),
                accountNumber = "12345678",
                accountPrdtCd = "01",
                appKeyEnc = ByteArray(0),
                secretKeyEnc = ByteArray(0),
            )
        whenever(testCredRepo.findAllByAccountType(AccountType.REAL.name)).thenReturn(listOf(cred))
    }

    /**
     * ExchangeFunction 기반 WebClient — TraceIdWebClientFilterTest와 동일 패턴.
     * brittle 한 mock chain(.get().uri().retrieve()...) 없이 e2e 검증.
     */
    private fun webClientWith(exchange: ExchangeFunction): WebClient = WebClient.builder().exchangeFunction(exchange).build()

    @Test
    fun `EGW00123 self-heal — 첫 호출 만료 → evictToken 1회 호출 + 재시도 성공 정합`() {
        val testCredRepo: UserKisCredentialJpaRepository = mock()
        val testCipher: SecretCipher = mock()
        val testTokenHelper: KisTokenCacheHelper = mock()
        val testRateLimiter: KisRateLimiterPort = mock()
        stubCredential(testCredRepo)
        whenever(testCipher.decrypt(any())).thenReturn(ByteArray(0))
        whenever(testTokenHelper.getAccessToken(eq("u-001"), eq(AccountType.REAL))).thenReturn("stale-token")

        val callCount = AtomicInteger(0)
        val exchange =
            ExchangeFunction { _ ->
                val n = callCount.incrementAndGet()
                if (n == 1) {
                    // 1차: KIS 토큰 만료 EGW00123 — HTTP 500 wrap (status 만으로 분기 불가)
                    Mono.just(
                        ClientResponse
                            .create(HttpStatus.INTERNAL_SERVER_ERROR)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(egw00123Body)
                            .build(),
                    )
                } else {
                    // 2차: evict 후 재발급 토큰으로 재호출 → 정상 응답
                    Mono.just(
                        ClientResponse
                            .create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(successBody)
                            .build(),
                    )
                }
            }

        val testSut =
            ApiKisBalanceQueryAdapter(
                webClient = webClientWith(exchange),
                secretCipher = testCipher,
                credentialRepository = testCredRepo,
                tokenHelper = testTokenHelper,
                kisRateLimiterPort = testRateLimiter,
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                kisBaseVps = "https://openapivts.koreainvestment.com:29443",
            )

        val result = testSut.fetchFullBalance(userId = "u-001", accountType = AccountType.REAL)

        // (a) cache evict 1회 호출
        verify(testTokenHelper).evictToken(eq("u-001"), eq(AccountType.REAL))
        // (b) WebClient 2회 호출
        assertThat(callCount.get()).isEqualTo(2)
        // (c) 정상 응답 파싱
        assertThat(result.balance.cashBalance).isEqualByComparingTo("10000000")
        assertThat(result.holdings).hasSize(1)
        assertThat(result.holdings[0].code).isEqualTo("005930")
        // (d) EGW00123 은 RateLimit 아님 → onTpsExceeded 호출되지 않음
        verify(testRateLimiter, never()).onTpsExceeded()
        // (e) 정상 종료 → onSuccess 1회
        verify(testRateLimiter).onSuccess()
    }

    @Test
    fun `EGW00123 self-heal — 재시도도 만료면 throw (무한 루프 차단, retried=true LIMIT)`() {
        val testCredRepo: UserKisCredentialJpaRepository = mock()
        val testCipher: SecretCipher = mock()
        val testTokenHelper: KisTokenCacheHelper = mock()
        val testRateLimiter: KisRateLimiterPort = mock()
        stubCredential(testCredRepo)
        whenever(testCipher.decrypt(any())).thenReturn(ByteArray(0))
        whenever(testTokenHelper.getAccessToken(eq("u-001"), eq(AccountType.REAL))).thenReturn("always-expired-token")

        val callCount = AtomicInteger(0)
        val exchange =
            ExchangeFunction { _ ->
                callCount.incrementAndGet()
                Mono.just(
                    ClientResponse
                        .create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(egw00123Body)
                        .build(),
                )
            }

        val testSut =
            ApiKisBalanceQueryAdapter(
                webClient = webClientWith(exchange),
                secretCipher = testCipher,
                credentialRepository = testCredRepo,
                tokenHelper = testTokenHelper,
                kisRateLimiterPort = testRateLimiter,
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                kisBaseVps = "https://openapivts.koreainvestment.com:29443",
            )

        assertThatThrownBy { testSut.fetchFullBalance(userId = "u-001", accountType = AccountType.REAL) }
            .isInstanceOf(WebClientResponseException::class.java)

        // evict 1회만 호출 (재시도 진입 직전)
        verify(testTokenHelper).evictToken(eq("u-001"), eq(AccountType.REAL))
        // WebClient 정확히 2회 (1차 EGW00123 → evict → 2차 retry EGW00123 → throw, 3차 진입 0)
        assertThat(callCount.get()).isEqualTo(2)
    }

    @Test
    fun `EGW00201 (429 rate limit) — 기존 동작 보존 (onTpsExceeded 호출, EGW00123 self-heal 미진입)`() {
        val testCredRepo: UserKisCredentialJpaRepository = mock()
        val testCipher: SecretCipher = mock()
        val testTokenHelper: KisTokenCacheHelper = mock()
        val testRateLimiter: KisRateLimiterPort = mock()
        stubCredential(testCredRepo)
        whenever(testCipher.decrypt(any())).thenReturn(ByteArray(0))
        whenever(testTokenHelper.getAccessToken(eq("u-001"), eq(AccountType.REAL))).thenReturn("valid-token")

        val egw00201Body = """{"rt_cd":"1","msg1":"초당 거래건수를 초과하였습니다.","msg_cd":"EGW00201"}"""
        val callCount = AtomicInteger(0)
        val exchange =
            ExchangeFunction { _ ->
                callCount.incrementAndGet()
                // KIS EGW00201은 HTTP 500 wrap (진단 PR #639 정합)
                Mono.just(
                    ClientResponse
                        .create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(egw00201Body)
                        .build(),
                )
            }

        val testSut =
            ApiKisBalanceQueryAdapter(
                webClient = webClientWith(exchange),
                secretCipher = testCipher,
                credentialRepository = testCredRepo,
                tokenHelper = testTokenHelper,
                kisRateLimiterPort = testRateLimiter,
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                kisBaseVps = "https://openapivts.koreainvestment.com:29443",
            )

        assertThatThrownBy { testSut.fetchFullBalance(userId = "u-001", accountType = AccountType.REAL) }
            .isInstanceOf(WebClientResponseException::class.java)

        // EGW00201 은 RateLimit → onTpsExceeded 호출
        verify(testRateLimiter).onTpsExceeded()
        // EGW00123 미포함 → evict 호출 0회 + 재시도 0회 (호출 1회만)
        verify(testTokenHelper, never()).evictToken(any(), any())
        assertThat(callCount.get()).isEqualTo(1)
    }

    @Test
    fun `Rate Limit acquire 호출 — USER_PATH priority 3000ms timeout (정합 회귀)`() {
        val testCredRepo: UserKisCredentialJpaRepository = mock()
        val testCipher: SecretCipher = mock()
        val testTokenHelper: KisTokenCacheHelper = mock()
        val testRateLimiter: KisRateLimiterPort = mock()
        stubCredential(testCredRepo)
        whenever(testCipher.decrypt(any())).thenReturn(ByteArray(0))
        whenever(testTokenHelper.getAccessToken(eq("u-001"), eq(AccountType.REAL))).thenReturn("valid-token")

        val exchange =
            ExchangeFunction { _ ->
                Mono.just(
                    ClientResponse
                        .create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(successBody)
                        .build(),
                )
            }

        val testSut =
            ApiKisBalanceQueryAdapter(
                webClient = webClientWith(exchange),
                secretCipher = testCipher,
                credentialRepository = testCredRepo,
                tokenHelper = testTokenHelper,
                kisRateLimiterPort = testRateLimiter,
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                kisBaseVps = "https://openapivts.koreainvestment.com:29443",
            )

        testSut.fetchFullBalance(userId = "u-001", accountType = AccountType.REAL)

        // trId 라벨 합류 (ADR 0116 §2-E 연장) — REAL accountType → TTTC8434R.
        verify(testRateLimiter).acquire(eq(KisCallPriority.USER_PATH), eq(3000L), eq("TTTC8434R"))
        verify(testRateLimiter).onSuccess()
    }
}
