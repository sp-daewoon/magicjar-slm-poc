package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

class ConsumerKisBalanceQueryAdapterTest {
    private val secretCipher = mockk<SecretCipher>()
    private val credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>()
    private val kisAuthAdapter = mockk<KisAuthAdapter>()
    private val kisRateLimiterPort = mockk<KisRateLimiterPort>()

    @Test
    fun `EGW00201 rate limit은 1회 backoff retry 후 성공하면 잔고를 반환한다`() {
        stubCredential()
        every { secretCipher.decrypt(any()) } answers { "plain-key".toByteArray() }
        every { kisAuthAdapter.getAccessToken("u-001", AccountType.MOCK) } returns "valid-token"
        every { kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, 3000L, any()) } just Runs
        every { kisRateLimiterPort.onTpsExceeded() } just Runs
        every { kisRateLimiterPort.onSuccess() } just Runs

        val callCount = AtomicInteger(0)
        val exchange =
            ExchangeFunction {
                val n = callCount.incrementAndGet()
                if (n == 1) {
                    Mono.just(
                        ClientResponse
                            .create(HttpStatus.INTERNAL_SERVER_ERROR)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body("""{"rt_cd":"1","msg1":"초당 거래건수를 초과하였습니다.","msg_cd":"EGW00201"}""")
                            .build(),
                    )
                } else {
                    Mono.just(
                        ClientResponse
                            .create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(successBody)
                            .build(),
                    )
                }
            }

        val sut =
            ConsumerKisBalanceQueryAdapter(
                webClient = webClientWith(exchange),
                secretCipher = secretCipher,
                credentialRepository = credentialRepository,
                kisAuthAdapter = kisAuthAdapter,
                kisRateLimiterPort = kisRateLimiterPort,
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                kisBaseVps = "https://openapivts.koreainvestment.com:29443",
                rateLimitBackoffMillis = 0L,
            )

        val result = sut.fetchFullBalance("u-001", AccountType.MOCK)

        assertThat(callCount.get()).isEqualTo(2)
        assertThat(result.holdings).hasSize(1)
        assertThat(result.holdings[0].code).isEqualTo("005930")
        assertThat(result.holdings[0].orderableQuantity).isEqualTo(7)
        verify(exactly = 2) { kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, 3000L, any()) }
        verify(exactly = 1) { kisRateLimiterPort.onTpsExceeded() }
        verify(exactly = 1) { kisRateLimiterPort.onSuccess() }
    }

    @Test
    fun `account availability refresh 조회는 BACKFILL priority를 사용한다`() {
        stubCredential()
        every { secretCipher.decrypt(any()) } answers { "plain-key".toByteArray() }
        every { kisAuthAdapter.getAccessToken("u-001", AccountType.MOCK) } returns "valid-token"
        every { kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, 3000L, any()) } just Runs
        every { kisRateLimiterPort.onSuccess() } just Runs

        val exchange =
            ExchangeFunction {
                Mono.just(
                    ClientResponse
                        .create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(successBody)
                        .build(),
                )
            }
        val sut =
            ConsumerKisBalanceQueryAdapter(
                webClient = webClientWith(exchange),
                secretCipher = secretCipher,
                credentialRepository = credentialRepository,
                kisAuthAdapter = kisAuthAdapter,
                kisRateLimiterPort = kisRateLimiterPort,
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                kisBaseVps = "https://openapivts.koreainvestment.com:29443",
            )

        val result = sut.fetchFullBalanceForAvailabilityRefresh("u-001", AccountType.MOCK)

        assertThat(result.holdings[0].orderableQuantity).isEqualTo(7)
        verify(exactly = 1) { kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, 3000L, any()) }
    }

    @Test
    fun `F10 — EGW00201 rate limit 재시도(2회) 소진 시 KisBalanceRateLimitedException 을 던진다`() {
        stubCredential()
        every { secretCipher.decrypt(any()) } answers { "plain-key".toByteArray() }
        every { kisAuthAdapter.getAccessToken("u-001", AccountType.MOCK) } returns "valid-token"
        every { kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, 3000L, any()) } just Runs
        every { kisRateLimiterPort.onTpsExceeded() } just Runs
        every { kisRateLimiterPort.onSuccess() } just Runs

        // 매 호출 EGW00201 — 재시도까지 모두 rate limit.
        val callCount = AtomicInteger(0)
        val exchange =
            ExchangeFunction {
                callCount.incrementAndGet()
                Mono.just(
                    ClientResponse
                        .create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("""{"rt_cd":"1","msg1":"초당 거래건수를 초과하였습니다.","msg_cd":"EGW00201"}""")
                        .build(),
                )
            }

        val sut =
            ConsumerKisBalanceQueryAdapter(
                webClient = webClientWith(exchange),
                secretCipher = secretCipher,
                credentialRepository = credentialRepository,
                kisAuthAdapter = kisAuthAdapter,
                kisRateLimiterPort = kisRateLimiterPort,
                kisBaseProd = "https://openapi.koreainvestment.com:9443",
                kisBaseVps = "https://openapivts.koreainvestment.com:29443",
                rateLimitBackoffMillis = 0L,
                rateLimitMaxRetries = 2,
            )

        assertThatThrownBy { sut.fetchFullBalance("u-001", AccountType.MOCK) }
            .isInstanceOf(KisBalanceRateLimitedException::class.java)
        // 최초 1 + 재시도 2 = 총 3회 호출.
        assertThat(callCount.get()).isEqualTo(3)
        verify(exactly = 3) { kisRateLimiterPort.onTpsExceeded() }
    }

    private fun stubCredential() {
        every { credentialRepository.findAllByAccountType(AccountType.MOCK.name) } returns
            listOf(
                ConsumerUserKisCredentialJpaEntity(
                    id = ConsumerUserKisCredentialId(userId = "u-001", accountType = AccountType.MOCK.name),
                    accountNumber = "12345678",
                    accountPrdtCd = "01",
                    appKeyEnc = ByteArray(0),
                    secretKeyEnc = ByteArray(0),
                ),
            )
    }

    private fun webClientWith(exchange: ExchangeFunction): WebClient = WebClient.builder().exchangeFunction(exchange).build()

    private val successBody =
        """
        {
          "rt_cd":"0",
          "msg1":"정상처리",
          "output1":[{
            "pdno":"005930","prdt_name":"삼성전자","hldg_qty":"10",
            "pchs_avg_pric":"70000","prpr":"71000","evlu_amt":"710000",
            "evlu_pfls_amt":"10000","evlu_pfls_rt":"1.43","ord_psbl_qty":"7"
          }],
          "output2":[{
            "dnca_tot_amt":"10000000","nxdy_excc_amt":"10000000","prvs_rcdl_excc_amt":"10000000",
            "tot_evlu_amt":"12000000","nass_amt":"12000000",
            "pchs_amt_smtl_amt":"1900000","evlu_amt_smtl_amt":"2000000","evlu_pfls_smtl_amt":"100000"
          }]
        }
        """.trimIndent()
}
