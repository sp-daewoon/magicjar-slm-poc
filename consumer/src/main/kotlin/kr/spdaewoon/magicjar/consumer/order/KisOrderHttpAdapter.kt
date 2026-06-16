package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisAuthAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisHashKeyAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCircuitOpenException
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.KisOrderAck
import kr.spdaewoon.magicjar.domain.order.execution.OrderIntent
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

/**
 * KIS REST 주식 현금 매수/매도 어댑터 (ADR 0068 §14-5 LOCK + ADR 0084 P0-1 hashkey 합류).
 *
 * ### raw 출처 (헌법 §3 KIS-first)
 * - 매수/매도: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions.py L10097~L10213 (def order_cash)
 * - 정정/취소: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions.py L10771~L10884 (def order_rvsecncl)
 * - hashkey 사용 (ADR 0084 P0-1): https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py (def set_order_hash_key) + https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/order_cash/order_cash.py
 *
 * ### tr_id 매트릭스
 * - TTTC0012U: 실전 주식 현금 매수
 * - VTTC0012U: 모의 주식 현금 매수
 * - TTTC0011U: 실전 주식 현금 매도
 * - VTTC0011U: 모의 주식 현금 매도
 * - TTTC0013U: 실전 주식 주문정정·취소
 * - VTTC0013U: 모의 주식 주문정정·취소
 *
 * ### endpoint
 * - POST /uapi/domestic-stock/v1/trading/order-cash
 * - POST /uapi/domestic-stock/v1/trading/order-rvsecncl
 *
 * ### KIS body 매핑 (raw L10097~L10213)
 * - CANO          : 계좌번호 8자리
 * - ACNT_PRDT_CD  : 계좌상품코드 ("01")
 * - PDNO          : 종목코드 6자리
 * - ORD_DVSN      : 주문구분 (MARKET="01" / LIMIT="00")
 * - ORD_QTY       : 주문수량
 * - ORD_UNPR      : 주문단가 (MARKET="0" / LIMIT=limitPrice)
 * - EXCG_ID_DVSN_CD : 거래소ID구분 ("KRX")
 * - SLL_TYPE      : 매도구분 (SELL만 — "01" 일반매도)
 * - CNDT_PRIC     : 조건가격 ("0")
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class KisOrderHttpAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,
    private val kisAuthAdapter: KisAuthAdapter,
    private val kisHashKeyAdapter: KisHashKeyAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) : KisOrderPort {
    private val log = LoggerFactory.getLogger(KisOrderHttpAdapter::class.java)

    companion object {
        private const val ORDER_CASH_PATH = "/uapi/domestic-stock/v1/trading/order-cash"
        private const val ORDER_RVSECNCL_PATH = "/uapi/domestic-stock/v1/trading/order-rvsecncl"
        private const val TIMEOUT_SEC = 10L
    }

    /**
     * 주문 전송 (TTTC0012U·VTTC0012U·TTTC0011U·VTTC0011U).
     *
     * raw 출처: domestic_stock_functions.py L10097~L10213 (def order_cash)
     *
     * 반환:
     *   Right(KisOrderAck) — rt_cd == "0" 정상
     *   Left(DenyReason.KisRejected(rt, msg)) — rt_cd != "0"
     *   Left(DenyReason.KisException(cause)) — invalid response / parse 실패
     *   throws WebClientResponseException 5xx — order-cash 본주문 호출 후 caller retry 책임 (§14-2 step 7)
     *
     * 토큰·hashkey·rate-limit acquire 실패는 KIS 본주문 미전송이 확정되므로 ORDER_NOT_SENT KisException으로 반환한다.
     */
    override fun submit(
        intent: OrderIntent,
        mode: TradingMode,
    ): OrderResult<DenyReason, KisOrderAck> {
        val accountType = if (mode == TradingMode.REAL) AccountType.REAL else AccountType.MOCK

        val cred =
            credentialRepository
                .findAllByAccountType(accountType.name)
                .firstOrNull { it.id.userId == intent.userId }
                ?: return OrderResult.Left(DenyReason.RealGateDenied("CREDENTIAL_NOT_FOUND"))

        val trId = resolveTrId(intent.side, mode)
        val baseUrl = if (mode == TradingMode.REAL) kisBaseProd else kisBaseVps

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        // D-1 try-finally drift 차단 패턴 (T-D-1):
        //   기존 결함: acquire 호출 후 onSuccess/onTpsExceeded 시그널이 정상 path / catch path 모두 누락 → 통계 영구 drift.
        //   본 fix: 정상 path에 onSuccess + 429 catch에 onTpsExceeded + signalSent flag로 모든 path 감시.
        var signalSent = false
        var acquiredToken = false
        return try {
            val accessToken =
                try {
                    kisAuthAdapter.getAccessToken(intent.userId, accountType)
                } catch (e: WebClientResponseException) {
                    log.warn(
                        "KIS auth HTTP 실패 — order-cash 미전송: trId={} userId={} status={} body={}",
                        trId,
                        maskUserId(intent.userId),
                        e.statusCode,
                        e.responseBodyAsString.take(200),
                    )
                    return OrderResult.Left(
                        DenyReason.KisException("ORDER_NOT_SENT:AUTH_HTTP_${e.statusCode.value()}"),
                    )
                } catch (e: Exception) {
                    val reasonPrefix = if (isTimeout(e)) "ORDER_NOT_SENT:AUTH_TIMEOUT" else "ORDER_NOT_SENT:AUTH_FAILED"
                    log.warn(
                        "KIS auth 예외 — order-cash 미전송: trId={} userId={} reason={} err={}",
                        trId,
                        maskUserId(intent.userId),
                        reasonPrefix,
                        e.message,
                    )
                    return OrderResult.Left(DenyReason.KisException("$reasonPrefix:${e.message ?: "unknown"}"))
                }
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val body = buildOrderCashBody(cred, intent)

            // ADR 0084 P0-1 — hashkey 헤더 합류 (REAL 진입 prereq).
            // raw: examples_user/kis_auth.py def set_order_hash_key (POST /uapi/hashkey → response.HASH → headers["hashkey"]).
            val hashKey =
                try {
                    kisHashKeyAdapter.issueHashKey(
                        body = body,
                        accessToken = accessToken,
                        appKey = appKeyStr,
                        appSecret = secretKeyStr,
                        accountType = accountType,
                    )
                } catch (e: KisRateLimitTimeoutException) {
                    log.warn(
                        "KIS hashkey rate-limit timeout — order-cash 미전송: trId={} userId={} timeoutMs={}",
                        trId,
                        maskUserId(intent.userId),
                        e.timeoutMillis,
                    )
                    return OrderResult.Left(
                        DenyReason.KisException("ORDER_NOT_SENT:HASHKEY_RATE_LIMIT_TIMEOUT:${e.message}"),
                    )
                } catch (e: KisCircuitOpenException) {
                    log.warn(
                        "KIS hashkey circuit open — order-cash 미전송: trId={} userId={} openUntil={}",
                        trId,
                        maskUserId(intent.userId),
                        e.openUntilEpochMillis,
                    )
                    return OrderResult.Left(
                        DenyReason.KisException("ORDER_NOT_SENT:HASHKEY_CIRCUIT_OPEN:${e.message}"),
                    )
                } catch (e: WebClientResponseException) {
                    log.warn(
                        "KIS hashkey HTTP 실패 — order-cash 미전송: trId={} userId={} status={} body={}",
                        trId,
                        maskUserId(intent.userId),
                        e.statusCode,
                        e.responseBodyAsString.take(200),
                    )
                    return OrderResult.Left(
                        DenyReason.KisException("ORDER_NOT_SENT:HASHKEY_HTTP_${e.statusCode.value()}"),
                    )
                } catch (e: IllegalStateException) {
                    // 4xx 또는 response 구조 결함 — 재시도 무의미, 즉시 REJECT
                    log.warn(
                        "KIS hashkey 발급 4xx/구조 결함: trId={} userId={} err={}",
                        trId,
                        maskUserId(intent.userId),
                        e.message,
                    )
                    return OrderResult.Left(DenyReason.KisException("ORDER_NOT_SENT:HASHKEY_FAILED:${e.message}"))
                } catch (e: Exception) {
                    val reasonPrefix = if (isTimeout(e)) "ORDER_NOT_SENT:HASHKEY_TIMEOUT" else "ORDER_NOT_SENT:HASHKEY_FAILED"
                    log.warn(
                        "KIS hashkey 예외 — order-cash 미전송: trId={} userId={} reason={} err={}",
                        trId,
                        maskUserId(intent.userId),
                        reasonPrefix,
                        e.message,
                    )
                    return OrderResult.Left(DenyReason.KisException("$reasonPrefix:${e.message ?: "unknown"}"))
                }
            // hashkey 이전/발급 실패는 order-cash 미전송이 확정되므로 위 catch에서 ORDER_NOT_SENT로 반환.

            // Rate Limit acquire (spec PR #564 §2-2 LOCK — USER_PATH 최우선 priority)
            // 주문 path는 자본 직결 — 즉시 acquire 강제 (사용자 path)
            try {
                kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 3000L, trId = trId)
                acquiredToken = true
            } catch (e: KisRateLimitTimeoutException) {
                log.warn(
                    "KIS order-cash rate-limit timeout — KIS 본주문 미전송: trId={} userId={} timeoutMs={}",
                    trId,
                    maskUserId(intent.userId),
                    e.timeoutMillis,
                )
                return OrderResult.Left(
                    DenyReason.KisException("ORDER_NOT_SENT:ORDER_RATE_LIMIT_TIMEOUT:${e.message}"),
                )
            } catch (e: KisCircuitOpenException) {
                log.warn(
                    "KIS order-cash circuit open — KIS 본주문 미전송: trId={} userId={} openUntil={}",
                    trId,
                    maskUserId(intent.userId),
                    e.openUntilEpochMillis,
                )
                return OrderResult.Left(
                    DenyReason.KisException("ORDER_NOT_SENT:ORDER_CIRCUIT_OPEN:${e.message}"),
                )
            }

            val response =
                webClient
                    .post()
                    .uri("$baseUrl$ORDER_CASH_PATH")
                    .header("authorization", "Bearer $accessToken")
                    .header("appkey", appKeyStr)
                    .header("appsecret", secretKeyStr)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .header("hashkey", hashKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(KisOrderResponse::class.java)
                    .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                    .block()
                    ?: run {
                        kisRateLimiterPort.onSuccess() // HTTP 자체는 200·body null path — token 소비 정상 집계
                        signalSent = true
                        return OrderResult.Left(DenyReason.KisException("RESPONSE_NULL"))
                    }

            kisRateLimiterPort.onSuccess()
            signalSent = true

            if (response.rtCd == "0") {
                val output =
                    response.output
                        ?: return OrderResult.Left(DenyReason.KisException("OUTPUT_NULL"))
                log.info(
                    "KIS 주문 ACK: trId={} userId={} signalId={} code={} side={} ODNO={}",
                    trId,
                    maskUserId(intent.userId),
                    intent.signalId,
                    intent.code,
                    intent.side,
                    output.odno,
                )
                OrderResult.Right(
                    KisOrderAck(
                        kisOrderNo = output.odno,
                        kisFwdgOrgno = output.krxFwdgOrdOrgno ?: "",
                        rtCd = "0",
                        msg = response.msg1 ?: "",
                    ),
                )
            } else {
                log.warn(
                    "KIS 주문 REJECT: trId={} userId={} signalId={} rt_cd={} msg={}",
                    trId,
                    maskUserId(intent.userId),
                    intent.signalId,
                    response.rtCd,
                    response.msg1,
                )
                OrderResult.Left(
                    DenyReason.KisRejected(
                        rtCd = response.rtCd ?: "UNKNOWN",
                        msg = response.msg1 ?: "",
                    ),
                )
            }
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.error(
                "KIS 주문 HTTP 실패: trId={} userId={} status={} body={}",
                trId,
                maskUserId(intent.userId),
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            if (e.statusCode.is5xxServerError) {
                throw e // §14-2 step 7 — caller가 1회 retry
            }
            OrderResult.Left(DenyReason.KisException("HTTP_${e.statusCode.value()}"))
        } catch (e: Exception) {
            if (isTimeout(e)) {
                if (acquiredToken && !signalSent) {
                    kisRateLimiterPort.onSuccess()
                    signalSent = true
                }
                log.error(
                    "KIS 주문 timeout(wrapped) → PENDING_RECONCILE: trId={} userId={} err={}",
                    trId,
                    maskUserId(intent.userId),
                    e.message,
                )
                return OrderResult.Left(
                    DenyReason.KisTimeoutPendingReconcile("KIS_TIMEOUT_WRAPPED:${e.message ?: "unknown"}"),
                )
            }
            log.error(
                "KIS 주문 예외: trId={} userId={} err={}",
                trId,
                maskUserId(intent.userId),
                e.message,
            )
            OrderResult.Left(DenyReason.KisException(e.message ?: "UNKNOWN"))
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
            if (acquiredToken && !signalSent) {
                log.warn(
                    "[KisRateLimit] order submit acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): trId={} userId={}",
                    trId,
                    maskUserId(intent.userId),
                )
            }
        }
    }

    /**
     * 주문 정정·취소 (TTTC0013U·VTTC0013U).
     *
     * raw 출처: domestic_stock_functions.py L10771~L10884 (def order_rvsecncl)
     *
     * RVSE_CNCL_DVSN_CD: "01" 정정 · "02" 취소
     * Phase 1은 취소만 cover (정정은 후속).
     */
    override fun cancel(
        request: OrderRequest,
        mode: TradingMode,
    ): OrderResult<DenyReason, Unit> {
        val accountType = if (mode == TradingMode.REAL) AccountType.REAL else AccountType.MOCK
        val cred =
            credentialRepository
                .findAllByAccountType(accountType.name)
                .firstOrNull { it.id.userId == request.userId }
                ?: return OrderResult.Left(DenyReason.RealGateDenied("CREDENTIAL_NOT_FOUND"))

        val kisOrderNo =
            request.kisOrderNo
                ?: return OrderResult.Left(DenyReason.KisException("KIS_ORDER_NO_NULL"))
        val kisFwdgOrgno =
            request.kisFwdgOrgno
                ?: return OrderResult.Left(DenyReason.KisException("KIS_FWDG_ORGNO_NULL"))

        val accessToken = kisAuthAdapter.getAccessToken(request.userId, accountType)
        val trId = if (mode == TradingMode.REAL) "TTTC0013U" else "VTTC0013U"
        val baseUrl = if (mode == TradingMode.REAL) kisBaseProd else kisBaseVps

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        // D-1 try-finally drift 차단 패턴 (T-D-1).
        var signalSent = false
        var acquiredToken = false
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val body =
                mapOf(
                    "CANO" to cred.accountNumber,
                    "ACNT_PRDT_CD" to cred.accountPrdtCd,
                    "KRX_FWDG_ORD_ORGNO" to kisFwdgOrgno,
                    "ORGN_ODNO" to kisOrderNo,
                    "ORD_DVSN" to "00",
                    "RVSE_CNCL_DVSN_CD" to "02", // 02=취소
                    "ORD_QTY" to "0",
                    "ORD_UNPR" to "0",
                    "QTY_ALL_ORD_YN" to "Y",
                )

            // ADR 0084 P0-1 — hashkey 헤더 합류 (정정/취소도 동일 적용).
            // raw: examples_user/kis_auth.py def set_order_hash_key — POST/정정/취소 모두 hashkey 발급 후 헤더 set.
            val hashKey =
                try {
                    kisHashKeyAdapter.issueHashKey(
                        body = body,
                        accessToken = accessToken,
                        appKey = appKeyStr,
                        appSecret = secretKeyStr,
                        accountType = accountType,
                    )
                } catch (e: IllegalStateException) {
                    log.warn(
                        "KIS 주문 취소 hashkey 발급 4xx/구조 결함: trId={} userId={} err={}",
                        trId,
                        maskUserId(request.userId),
                        e.message,
                    )
                    return OrderResult.Left(DenyReason.KisException("HASHKEY_FAILED:${e.message}"))
                }

            // Rate Limit acquire (spec PR #564 §2-2 LOCK — USER_PATH priority — 주문 취소 path)
            kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 3000L, trId = trId)
            acquiredToken = true

            val response =
                webClient
                    .post()
                    .uri("$baseUrl$ORDER_RVSECNCL_PATH")
                    .header("authorization", "Bearer $accessToken")
                    .header("appkey", appKeyStr)
                    .header("appsecret", secretKeyStr)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .header("hashkey", hashKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(KisOrderResponse::class.java)
                    .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                    .block()
                    ?: run {
                        kisRateLimiterPort.onSuccess()
                        signalSent = true
                        return OrderResult.Left(DenyReason.KisException("RESPONSE_NULL"))
                    }

            kisRateLimiterPort.onSuccess()
            signalSent = true

            if (response.rtCd == "0") {
                log.info(
                    "KIS 주문 취소 ACK: trId={} userId={} orderId={} kisOrderNo={}",
                    trId,
                    maskUserId(request.userId),
                    request.id,
                    kisOrderNo,
                )
                OrderResult.Right(Unit)
            } else {
                OrderResult.Left(
                    DenyReason.KisRejected(
                        rtCd = response.rtCd ?: "UNKNOWN",
                        msg = response.msg1 ?: "",
                    ),
                )
            }
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.error(
                "KIS 주문 취소 HTTP 실패: userId={} status={}",
                maskUserId(request.userId),
                e.statusCode,
            )
            OrderResult.Left(DenyReason.KisException("HTTP_${e.statusCode.value()}"))
        } catch (e: Exception) {
            log.error("KIS 주문 취소 예외: userId={} err={}", maskUserId(request.userId), e.message)
            OrderResult.Left(DenyReason.KisException(e.message ?: "UNKNOWN"))
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
            if (acquiredToken && !signalSent) {
                log.warn(
                    "[KisRateLimit] order cancel acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): trId={} userId={}",
                    trId,
                    maskUserId(request.userId),
                )
            }
        }
    }

    /**
     * tr_id 분기 (raw L10097~L10213 도메인 매트릭스).
     *
     * | side | mode | tr_id |
     * |------|------|-------|
     * | BUY  | REAL | TTTC0012U |
     * | BUY  | MOCK | VTTC0012U |
     * | SELL | REAL | TTTC0011U |
     * | SELL | MOCK | VTTC0011U |
     */
    internal fun resolveTrId(
        side: OrderSide,
        mode: TradingMode,
    ): String =
        when (side to mode) {
            OrderSide.BUY to TradingMode.REAL -> "TTTC0012U"
            OrderSide.BUY to TradingMode.MOCK -> "VTTC0012U"
            OrderSide.SELL to TradingMode.REAL -> "TTTC0011U"
            OrderSide.SELL to TradingMode.MOCK -> "VTTC0011U"
            else -> error("unreachable: side=$side mode=$mode")
        }

    /**
     * raw 출처: domestic_stock_functions.py L10097~L10213 (def order_cash) body 정합.
     */
    internal fun buildOrderCashBody(
        cred: ConsumerUserKisCredentialJpaEntity,
        intent: OrderIntent,
    ): Map<String, String> {
        val base =
            mutableMapOf(
                "CANO" to cred.accountNumber,
                "ACNT_PRDT_CD" to cred.accountPrdtCd,
                "PDNO" to intent.code,
                "ORD_DVSN" to intent.priceType.ordDvsn,
                "ORD_QTY" to intent.quantity.toString(),
                "ORD_UNPR" to if (intent.priceType == OrderPriceType.MARKET) "0" else (intent.limitPrice?.toPlainString() ?: "0"),
                "EXCG_ID_DVSN_CD" to "KRX",
                "CNDT_PRIC" to "0",
            )
        if (intent.side == OrderSide.SELL) {
            base["SLL_TYPE"] = "01" // 일반매도 (raw L10130)
        }
        return base
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    private fun isTimeout(e: Exception): Boolean =
        e is java.util.concurrent.TimeoutException ||
            e.message?.contains("timeout", ignoreCase = true) == true ||
            generateSequence(e.cause) { it.cause }
                .any { cause ->
                    cause is java.util.concurrent.TimeoutException ||
                        cause.javaClass.simpleName.contains("Timeout", ignoreCase = true)
                }
}

/**
 * KIS order-cash·order-rvsecncl 응답 DTO (raw output / rt_cd / msg1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class KisOrderResponse(
    @JsonProperty("rt_cd")
    val rtCd: String? = null,
    @JsonProperty("msg_cd")
    val msgCd: String? = null,
    @JsonProperty("msg1")
    val msg1: String? = null,
    @JsonProperty("output")
    val output: KisOrderOutput? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class KisOrderOutput(
    @JsonProperty("KRX_FWDG_ORD_ORGNO")
    val krxFwdgOrdOrgno: String? = null,
    @JsonProperty("ODNO")
    val odno: String = "",
    @JsonProperty("ORD_TMD")
    val ordTmd: String? = null,
)
