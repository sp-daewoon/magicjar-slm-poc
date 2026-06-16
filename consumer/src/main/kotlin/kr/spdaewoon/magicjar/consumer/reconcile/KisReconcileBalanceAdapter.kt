package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisAuthAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileHolding
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileKisBalancePort
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration

/**
 * KIS 도메인 잔고 조회 어댑터 — T-RECON Phase 1 (ADR 0041·0070).
 *
 * ### raw 정합 (헌법 §3 + ADR 0070 §8 inline KDoc LOCK 4건)
 *
 * **1. 실전 매수가능 잔고**: tr_id = `TTTC8434R`
 *   - URL: `/uapi/domestic-stock/v1/trading/inquire-balance`
 *   - raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py
 *   - L18 (API_URL = "/uapi/domestic-stock/v1/trading/inquire-balance")
 *   - L97~100 (tr_id assignment 분기: REAL=TTTC8434R / MOCK=VTTC8434R)
 *   - L102~112 (parameter mapping: CANO·ACNT_PRDT_CD·AFHR_FLPR_YN·OFL_YN·INQR_DVSN·UNPR_DVSN·FUND_STTL_ICLD_YN·FNCG_AMT_AUTO_RDPT_YN·PRCS_DVSN·CTX_AREA_FK100·CTX_AREA_NK100)
 *   - L125~134 (pagination loop: tr_cont 헤더 "M"=more, "F"/"D"=final + ctx_area_fk100/nk100 페이지 carry)
 *
 * **2. 모의 매수가능 잔고**: tr_id = `VTTC8434R` (동일 endpoint, tr_id만 분기)
 *
 * **3. 계좌자산현황 (보조)**: tr_id = `CTRP6548R`
 *   - URL: `/uapi/domestic-stock/v1/trading/inquire-account-balance`
 *   - raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_account_balance/inquire_account_balance.py
 *   - L14·L16·L42·L56~60 (output2 단건 — 평가금액·예수금 등 계좌 단위 fast check, T-RECON Phase 1은 메인 TTTC8434R만 호출)
 *
 * **4. H0STCNI0/H0STCNI9 (체결통보 — T-O baseline)**: ADR 0068 §14-6 cross-ref (T-RECON 직접 무관)
 *
 * ### 페이징 룰 (raw L125~134 정합)
 * - tr_cont 헤더 "M" or "" → 다음 페이지 존재 (CTX_AREA_FK100·NK100 전달)
 * - tr_cont 헤더 "F" or "D" → 마지막 페이지 (loop 종료)
 * - 실전 50건/요청 · 모의 20건/요청 한도
 *
 * ### Rate Limit (ADR 0037 + spec PR #564 §2-2 LOCK)
 * - 페이지 단위로 1회 호출 — 100주 보유 시 실전 2 페이지 = 2 호출
 * - 매 페이지 호출 직전 [KisRateLimiterPort.acquire] BACKFILL priority (cron 재 정합 reconcile path)
 * - EGW00201 (TPS 초과) → onTpsExceeded() + 1초 backoff + 1회 재시도 (Phase 1 baseline)
 * - HTTP 429 → 동일 backoff path
 *
 * ### AI 호출 0 (ADR 0023 §2-4 + 룰 5)
 * 룰/cron 기반 — AI provider import 0.
 *
 * 본 어댑터는 기존 `KisBalanceAdapter` (ADR 0042 Bootstrap)와 별도 분리:
 * - bootstrap은 단일 페이지만 — reconcile은 전체 페이징 흡수 의무
 * - reconcile은 평균단가 0.5% 임계 비교 → BigDecimal precision 보존 의무 (소수점 round-down 방지)
 *
 * 메모리 zero-fill 의무: APP_KEY·SECRET_KEY 사용 후 즉시 fill (기존 baseline 정합).
 */
@Component
class KisReconcileBalanceAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,
    private val kisAuthAdapter: KisAuthAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
) : ReconcileKisBalancePort {
    private val log = LoggerFactory.getLogger(KisReconcileBalanceAdapter::class.java)

    companion object {
        private const val ENDPOINT = "/uapi/domestic-stock/v1/trading/inquire-balance"
        private const val TR_ID_REAL = "TTTC8434R"
        private const val TR_ID_MOCK = "VTTC8434R"
        private const val TIMEOUT_SECONDS = 10L
        private const val MAX_PAGES = 20 // 1000주 보유 가정 — 실전 50건 × 20 페이지 안전 한도
        private const val EGW_TPS_EXCEEDED = "EGW00201"
        private const val EGW_BACKOFF_MS = 1000L
    }

    /**
     * 페이징 흡수 — raw L125~134 정합 (tr_cont "M"→loop, "F"/"D"→break).
     *
     * 정보 수집은 모두 실전 키 (사용자 메모리 `feedback_kis_key_realonly_for_data`).
     * AccountType 분기는 `inquire-balance` 호출 시 tr_id만 다를 뿐 — REAL 기본.
     */
    override fun fetchAllBalances(userId: String): List<ReconcileHolding> {
        val accountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        val cred =
            credentialRepository
                .findAllByAccountType(accountType.name)
                .firstOrNull { it.id.userId == userId }
                ?: error("KIS 자격증명 없음 — userId=${maskUserId(userId)} accountType=${accountType.name}")

        val trId = if (accountType == AccountType.REAL) TR_ID_REAL else TR_ID_MOCK
        val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps
        val accessToken = kisAuthAdapter.getAccessToken(userId, accountType)

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val accumulated = mutableListOf<ReconcileHolding>()
            var fk100 = ""
            var nk100 = ""
            var pageIdx = 0

            while (pageIdx < MAX_PAGES) {
                val page =
                    fetchBalancePage(
                        baseUrl = baseUrl,
                        trId = trId,
                        accessToken = accessToken,
                        appKey = appKeyStr,
                        secretKey = secretKeyStr,
                        cano = cred.accountNumber,
                        acntPrdtCd = "01",
                        fk100 = fk100,
                        nk100 = nk100,
                    )
                accumulated.addAll(page.holdings)

                if (page.trCont == "F" || page.trCont == "D") {
                    // 마지막 페이지 (raw L125~134 LOCK)
                    break
                }
                if (page.ctxAreaFk100.isBlank() && page.ctxAreaNk100.isBlank()) {
                    // 안전 fallback — fk100/nk100 비어있으면 loop 종료 (KIS 비정상 응답 가드)
                    break
                }
                fk100 = page.ctxAreaFk100
                nk100 = page.ctxAreaNk100
                pageIdx++
            }

            log.info(
                "KIS reconcile 잔고 흡수 완료: user={} type={} pages={} holdings={}건",
                maskUserId(userId),
                accountType.name,
                pageIdx + 1,
                accumulated.size,
            )
            accumulated
        } finally {
            // 메모리 zero-fill 의무
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    /**
     * 페이지 1건 호출 (raw L97~134 정합).
     *
     * EGW00201 (TPS 초과) 응답 시 1초 backoff + 1회 재시도.
     */
    @Suppress("LongParameterList")
    private fun fetchBalancePage(
        baseUrl: String,
        trId: String,
        accessToken: String,
        appKey: String,
        secretKey: String,
        cano: String,
        acntPrdtCd: String,
        fk100: String,
        nk100: String,
        retried: Boolean = false,
    ): BalancePage {
        // D-1 try-finally drift 차단 패턴 (T-D-1):
        //   acquire를 try 안으로 옮기고 signalSent flag로 통계 시그널 미발행 path 감시.
        var signalSent = false
        var acquiredToken = false
        try {
            // Rate Limit acquire (spec PR #564 §2-2 LOCK — BACKFILL priority — cron reconcile path)
            kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 5000L, trId = trId)
            acquiredToken = true

            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(baseUrl.removePrefix("https://").split(":")[0])
                            .port(
                                baseUrl
                                    .removePrefix("https://")
                                    .split(":")
                                    .getOrNull(1)
                                    ?.toIntOrNull() ?: 9443,
                            ).path(ENDPOINT)
                            // raw L102~112 — parameter mapping LOCK
                            .queryParam("CANO", cano)
                            .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                            .queryParam("AFHR_FLPR_YN", "N")
                            .queryParam("OFL_YN", "")
                            .queryParam("INQR_DVSN", "02")
                            .queryParam("UNPR_DVSN", "01")
                            .queryParam("FUND_STTL_ICLD_YN", "N")
                            .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                            .queryParam("PRCS_DVSN", "00")
                            .queryParam("CTX_AREA_FK100", fk100)
                            .queryParam("CTX_AREA_NK100", nk100)
                            .build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKey)
                    .header("appsecret", secretKey)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .exchangeToMono { resp ->
                        val trContHeader =
                            resp.headers().asHttpHeaders().getFirst("tr_cont") ?: ""
                        resp.bodyToMono(Map::class.java).map { body ->
                            Pair(trContHeader, body)
                        }
                    }.timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .block() ?: error("KIS 잔고조회 응답 없음")

            @Suppress("UNCHECKED_CAST")
            val body = response.second as Map<String, Any?>
            val rtCd = body["rt_cd"]?.toString()
            val msgCd = body["msg_cd"]?.toString()

            // EGW00201 backoff + 1회 재시도 (spec PR #564 §2-2 LOCK — onTpsExceeded 시그널)
            if (msgCd == EGW_TPS_EXCEEDED && !retried) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                log.warn("KIS EGW00201 TPS 초과 — onTpsExceeded + 1s backoff + 1회 retry")
                Thread.sleep(EGW_BACKOFF_MS)
                return fetchBalancePage(
                    baseUrl = baseUrl,
                    trId = trId,
                    accessToken = accessToken,
                    appKey = appKey,
                    secretKey = secretKey,
                    cano = cano,
                    acntPrdtCd = acntPrdtCd,
                    fk100 = fk100,
                    nk100 = nk100,
                    retried = true,
                )
            }

            if (rtCd != "0") {
                // 명시적 KIS 비정상 응답 — token 자체는 소비됐으므로 onSuccess로 통계 집계 (drift 회피)
                kisRateLimiterPort.onSuccess()
                signalSent = true
                error("KIS 잔고조회 실패: rt_cd=$rtCd msg_cd=$msgCd")
            }

            @Suppress("UNCHECKED_CAST")
            val output1 = body["output1"] as? List<Map<String, Any?>> ?: emptyList()
            val holdings = parseHoldings(output1, AccountType.valueOf(trIdToAccountTypeName(trId)))
            val nextFk100 = body["ctx_area_fk100"]?.toString()?.trim() ?: ""
            val nextNk100 = body["ctx_area_nk100"]?.toString()?.trim() ?: ""
            kisRateLimiterPort.onSuccess()
            signalSent = true
            return BalancePage(
                holdings = holdings,
                trCont = response.first.trim(),
                ctxAreaFk100 = nextFk100,
                ctxAreaNk100 = nextNk100,
            )
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
            val isRateLimit = classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit
            if (isRateLimit && !retried) {
                // Rate Limit (HTTP 429 또는 EGW00201 body) → onTpsExceeded + 1초 backoff + 1회 재시도
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                log.warn("KIS 잔고조회 Rate Limit — 1초 backoff 후 1회 retry: status={}", e.statusCode.value())
                Thread.sleep(EGW_BACKOFF_MS)
                return fetchBalancePage(
                    baseUrl = baseUrl,
                    trId = trId,
                    accessToken = accessToken,
                    appKey = appKey,
                    secretKey = secretKey,
                    cano = cano,
                    acntPrdtCd = acntPrdtCd,
                    fk100 = fk100,
                    nk100 = nk100,
                    retried = true,
                )
            } else if (isRateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.error("KIS 잔고조회 HTTP 실패: status={} body={}", e.statusCode, e.responseBodyAsString.take(200))
            throw e
        } finally {
            if (acquiredToken && !signalSent) {
                log.warn(
                    "[KisRateLimit] reconcile-balance acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): trId={}",
                    trId,
                )
            }
        }
    }

    /**
     * KIS output1 → [ReconcileHolding] 매핑.
     *
     * 수량 0주 종목은 필터링 (KIS는 0주 종목도 응답에 포함 — reconcile은 보유 종목만 비교 의무).
     */
    internal fun parseHoldings(
        output1: List<Map<String, Any?>>,
        accountType: AccountType = AccountType.REAL,
    ): List<ReconcileHolding> =
        output1.mapNotNull { item ->
            val symbol = item["pdno"]?.toString() ?: return@mapNotNull null
            val quantity = item["hldg_qty"]?.toString()?.toIntOrNull() ?: 0
            if (quantity <= 0) return@mapNotNull null
            val avgPrice =
                item["pchs_avg_pric"]?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            ReconcileHolding(symbol = symbol, quantity = quantity, avgPrice = avgPrice, accountType = accountType)
        }

    private fun trIdToAccountTypeName(trId: String): String = if (trId == TR_ID_REAL) AccountType.REAL.name else AccountType.MOCK.name

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /** 페이지 1건 응답 — raw L125~134 페이징 carry 정합. */
    internal data class BalancePage(
        val holdings: List<ReconcileHolding>,
        val trCont: String,
        val ctxAreaFk100: String,
        val ctxAreaNk100: String,
    )
}
