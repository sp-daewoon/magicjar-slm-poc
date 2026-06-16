package kr.spdaewoon.magicjar.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

/**
 * User-triggered KIS order cancel adapter for the API module.
 *
 * OrderCancelService는 이 adapter가 성공 ACK를 받은 뒤에만 DB status=CANCELLED로 전이한다.
 */
@Component
class ApiKisOrderCancelAdapter(
    private val webClient: WebClient,
    private val credentialRepository: UserKisCredentialJpaRepository,
    private val tokenHelper: KisTokenCacheHelper,
    private val hashKeyAdapter: ApiKisHashKeyAdapter,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) {
    private val log = LoggerFactory.getLogger(ApiKisOrderCancelAdapter::class.java)

    companion object {
        private const val ORDER_RVSECNCL_PATH = "/uapi/domestic-stock/v1/trading/order-rvsecncl"
        private val TIMEOUT = Duration.ofSeconds(10)
    }

    fun cancel(request: OrderRequest): OrderResult<DenyReason, Unit> {
        val accountType = if (request.mode == TradingMode.REAL) AccountType.REAL else AccountType.MOCK
        val cred =
            credentialRepository
                .findAllByAccountType(accountType.name)
                .firstOrNull { it.id.userId == request.userId }
                ?: return OrderResult.Left(DenyReason.RealGateDenied("CREDENTIAL_NOT_FOUND"))

        val kisOrderNo = request.kisOrderNo ?: return OrderResult.Left(DenyReason.KisException("KIS_ORDER_NO_NULL"))
        val kisFwdgOrgno = request.kisFwdgOrgno ?: return OrderResult.Left(DenyReason.KisException("KIS_FWDG_ORGNO_NULL"))

        val (accessToken, appKey, appSecret) = tokenHelper.resolveTokenAndKeys(request.userId, accountType)
        val trId = if (request.mode == TradingMode.REAL) "TTTC0013U" else "VTTC0013U"
        val baseUrl = if (request.mode == TradingMode.REAL) kisBaseProd else kisBaseVps
        val body = buildCancelBody(cred, kisFwdgOrgno, kisOrderNo)

        return try {
            val hashKey =
                try {
                    hashKeyAdapter.issueHashKey(body, accessToken, appKey, appSecret, accountType)
                } catch (e: IllegalStateException) {
                    return OrderResult.Left(DenyReason.KisException("HASHKEY_FAILED:${e.message}"))
                }

            var signalSent = false
            try {
                kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 3000L, trId = trId)
                val response =
                    webClient
                        .post()
                        .uri("$baseUrl$ORDER_RVSECNCL_PATH")
                        .header("authorization", "Bearer $accessToken")
                        .header("appkey", appKey)
                        .header("appsecret", appSecret)
                        .header("tr_id", trId)
                        .header("custtype", "P")
                        .header("hashkey", hashKey)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(ApiKisOrderResponse::class.java)
                        .timeout(TIMEOUT)
                        .block()
                        ?: return OrderResult.Left(DenyReason.KisException("RESPONSE_NULL"))

                kisRateLimiterPort.onSuccess()
                signalSent = true
                if (response.rtCd == "0") {
                    log.info("KIS 주문 취소 ACK(api): userId={} orderId={} kisOrderNo={}", request.userId, request.id, kisOrderNo)
                    OrderResult.Right(Unit)
                } else {
                    OrderResult.Left(DenyReason.KisRejected(response.rtCd ?: "UNKNOWN", response.msg1 ?: ""))
                }
            } catch (e: WebClientResponseException) {
                // P0 hotfix (진단 PR #639) — EGW00201 = HTTP 500 wrap. classifyKisError() 로 분기 통일.
                if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                    kisRateLimiterPort.onTpsExceeded()
                    signalSent = true
                }
                log.error("KIS 주문 취소 HTTP 실패(api): userId={} status={}", request.userId, e.statusCode)
                OrderResult.Left(DenyReason.KisException("HTTP_${e.statusCode.value()}"))
            } finally {
                if (!signalSent) {
                    log.warn("[KisRateLimit] api order cancel acquire 후 통계 시그널 미발행 — drift risk")
                }
            }
        } catch (e: Exception) {
            log.error("KIS 주문 취소 예외(api): userId={} orderId={} err={}", request.userId, request.id, e.message)
            OrderResult.Left(DenyReason.KisException(e.message ?: "UNKNOWN"))
        }
    }

    private fun buildCancelBody(
        cred: UserKisCredentialJpaEntity,
        kisFwdgOrgno: String,
        kisOrderNo: String,
    ): Map<String, String> =
        mapOf(
            "CANO" to cred.accountNumber,
            "ACNT_PRDT_CD" to cred.accountPrdtCd,
            "KRX_FWDG_ORD_ORGNO" to kisFwdgOrgno,
            "ORGN_ODNO" to kisOrderNo,
            "ORD_DVSN" to "00",
            "RVSE_CNCL_DVSN_CD" to "02",
            "ORD_QTY" to "0",
            "ORD_UNPR" to "0",
            "QTY_ALL_ORD_YN" to "Y",
        )
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiKisOrderResponse(
    @JsonProperty("rt_cd")
    val rtCd: String? = null,
    @JsonProperty("msg1")
    val msg1: String? = null,
)
