package kr.spdaewoon.magicjar.api.infrastructure.kis

import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * KIS `inquire-balance` REST 어댑터 — api 모듈 직접 호출 (T-ACCT Phase 1B, ADR 0081 §2-2).
 *
 * ### 책임 분리 (ADR 0042 / ADR 0070 / ADR 0081)
 * - consumer [kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisBalanceAdapter] (Bootstrap 영속, output1 only) — 0 touch
 * - consumer KisReconcileBalanceAdapter (T-RECON 페이징, holdings 비교) — 0 touch
 * - **본 어댑터** (T-ACCT 사용자 노출, output1 + output2 동시 흡수, Redis cache)
 *
 * ### KIS-first raw fetch 인용 (헌법 §3 LOCK · spec §3-1 + ADR 0081 §2-6)
 * - `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py`
 *   (query parameter mapping: CANO·ACNT_PRDT_CD·AFHR_FLPR_YN·OFL_YN·INQR_DVSN·UNPR_DVSN·FUND_STTL_ICLD_YN·FNCG_AMT_AUTO_RDPT_YN·PRCS_DVSN·CTX_AREA_FK100·CTX_AREA_NK100)
 * - `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py`
 *   (COLUMN_MAPPING 45 필드 영문 → 한글 정합)
 *
 * ### TR_ID 분기 (ADR 0042 §2-2 + KIS 공식)
 * - REAL `TTTC8434R` (host: openapi.koreainvestment.com:9443)
 * - MOCK `VTTC8434R` (host: openapivts.koreainvestment.com:29443)
 *
 * ### output2 11 필드 LOCK (한글 의미 매트릭스)
 * | 영문 key             | 한글 의미                  | AccountBalance 필드        |
 * |---------------------|--------------------------|----------------------------|
 * | dnca_tot_amt        | 예수금 총금액              | cashBalance                |
 * | nxdy_excc_amt       | 익일정산금액 (D+1 예수금)   | cashD1                     |
 * | prvs_rcdl_excc_amt  | 가수도정산금액 (D+2 실가용) | cashD2                     |
 * | tot_evlu_amt        | 총평가금액                 | totalEvaluation            |
 * | nass_amt            | 순자산금액                 | netAssetValue              |
 * | pchs_amt_smtl_amt   | 매입금액합계금액           | totalPurchaseAmount        |
 * | evlu_amt_smtl_amt   | 평가금액합계금액           | totalHoldingEvaluation     |
 * | evlu_pfls_smtl_amt  | 평가손익합계금액           | totalProfitLoss            |
 * | asst_icdc_amt       | 자산증감액 (전일 대비)     | assetIncreaseDecrease      |
 * | asst_icdc_erng_rt   | 자산증감수익률 %           | assetIncreaseDecreaseRate  |
 *
 * ### output1 8 컬럼 LOCK
 * - pdno · prdt_name · hldg_qty · pchs_avg_pric · prpr · evlu_amt · evlu_pfls_amt · evlu_pfls_rt
 *
 * ### 메모리 zero-fill 의무 (ADR 0038 §5-3)
 * - APP_KEY · SECRET_KEY 사용 후 즉시 ByteArray.fill(0)
 *
 * ### Redis token cache 공유 (T-1·2·3·4 통합 — 2026-05-20)
 * - 키 `kis:token:{userId}:{accountType}` consumer KisAuthAdapter와 공유 (cross-module Redis OK — ADR 0081 §4 모듈 경계 정합)
 * - 토큰 lookup·발급은 [KisTokenCacheHelper] 위임 (T-4):
 *   - 분산락 정합 (ADR 0038): in-process ReentrantLock + Redis SETNX (다중 JVM 안전)
 *   - 동적 TTL (T-3): KIS `expires_in` 응답 × 0.95 (hardcode 23h 제거)
 *   - 단일 prefix LOCK (T-1): `access_token:` 결함 prefix 폐기
 *
 * ### EGW00123 self-heal (2026-05-21 hotfix · backend-core)
 * - 결함: Redis TTL 살아있는 stale 토큰을 helper fast path가 재사용 → KIS EGW00123 무한 폭주
 *   (REAL dashboard 진입 시 GET /api/account/positions 502 20+ 회 반복 evidence)
 * - 본 어댑터 catch 분기:
 *   1) WebClient 응답 body `msg_cd=EGW00123` 검출
 *   2) [KisTokenCacheHelper.evictToken] 호출 → Redis cache key 삭제
 *   3) `fetchFullBalanceInternal(retried=true)` 1회 재호출 → 분산락 보호 하에 신규 발급 + 정상 응답
 *   4) retried=true 진입에서 또 EGW00123 → 무한 루프 차단 위해 throw
 */
@Component
class ApiKisBalanceQueryAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,
    private val credentialRepository: UserKisCredentialJpaRepository,
    private val tokenHelper: KisTokenCacheHelper,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) : KisBalanceQueryPort {
    companion object {
        private val TIMEOUT = Duration.ofSeconds(10)
        private const val INQUIRE_BALANCE_PATH = "/uapi/domestic-stock/v1/trading/inquire-balance"

        /**
         * KIS 토큰 만료 응답 코드. HTTP status 500 + body `msg_cd=EGW00123` 으로 wrap 되어 오므로
         * status 만으로 분기 불가 — body 문자열 검사 의무.
         */
        private const val KIS_TOKEN_EXPIRED_CODE = "EGW00123"
    }

    private val log = LoggerFactory.getLogger(ApiKisBalanceQueryAdapter::class.java)

    override fun fetchFullBalance(
        userId: String,
        accountType: AccountType,
    ): KisAccountBalance = fetchFullBalanceInternal(userId, accountType, retried = false)

    /**
     * 잔고조회 본문 — [retried] flag로 self-heal 1회 LIMIT (무한 루프 차단).
     *
     * 2026-05-21 hotfix (backend-core · KIS EGW00123 self-heal):
     *   evidence — Redis TTL 살아있는 stale 토큰 fast path 재사용으로 EGW00123 무한 폭주.
     *   본 메서드: WebClient catch에서 body msg_cd 검사 → cache evict + 1회 재호출.
     *   `retried=true` 진입 시 EGW00123 또 떨어지면 즉시 throw (재시도 1회 LIMIT).
     */
    private fun fetchFullBalanceInternal(
        userId: String,
        accountType: AccountType,
        retried: Boolean,
    ): KisAccountBalance {
        val cred = loadCredential(userId, accountType)
        // T-4 — KisTokenCacheHelper 위임 (분산락 + 동적 TTL + 단일 prefix)
        val accessToken = tokenHelper.getAccessToken(userId, accountType)
        val trId = if (accountType == AccountType.REAL) "TTTC8434R" else "VTTC8434R"
        val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        // Rate Limit acquire (D-2 합류 — USER_PATH priority — T-ACCT 사용자 응답 path)
        // try 안 진입 + signalSent flag로 drift 차단 (D-1 패턴 정합).
        var signalSent = false
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 3000L, trId = trId)

            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(parseHost(baseUrl))
                            .port(parsePort(baseUrl))
                            .path(INQUIRE_BALANCE_PATH)
                            .queryParam("CANO", cred.accountNumber)
                            .queryParam("ACNT_PRDT_CD", cred.accountPrdtCd)
                            .queryParam("AFHR_FLPR_YN", "N")
                            .queryParam("OFL_YN", "")
                            .queryParam("INQR_DVSN", "02")
                            .queryParam("UNPR_DVSN", "01")
                            .queryParam("FUND_STTL_ICLD_YN", "N")
                            .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                            .queryParam("PRCS_DVSN", "00")
                            .queryParam("CTX_AREA_FK100", "")
                            .queryParam("CTX_AREA_NK100", "")
                            .build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKeyStr)
                    .header("appsecret", secretKeyStr)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(TIMEOUT)
                    .block() as? Map<String, Any>
                    ?: error("KIS 잔고조회 응답 없음 — userId=$userId")

            val holdings = parseOutput1(response)
            val balance = parseOutput2(response)
            val now = Instant.now()
            kisRateLimiterPort.onSuccess()
            signalSent = true
            KisAccountBalance(
                userId = userId,
                accountType = accountType,
                balance = balance,
                holdings = holdings,
                fetchedAt = now,
            )
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — KIS EGW00201 = HTTP 500 wrap. 429 단일 가드 폐기.
            // classifyKisError() 가 body msg_cd 까지 검사 → RateLimit/AuthError/NetworkError/ClientError/Unknown 분류
            val body = e.responseBodyAsString
            if (classifyKisError(e.statusCode.value(), body) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }

            // 2026-05-21 hotfix (backend-core · EGW00123 self-heal):
            // KIS 토큰 만료(EGW00123) 시 HTTP 500 wrap → status 만으로 분기 불가.
            // Redis TTL 살아있는 stale 토큰 무한 재사용 차단 — cache evict + 1회 재호출.
            // retried=true 이미 진입 후 또 EGW00123 → 정상 발급 토큰이 즉시 만료된 비정상 상황,
            // 무한 루프 차단 위해 그대로 throw.
            if (!retried && body.contains(KIS_TOKEN_EXPIRED_CODE)) {
                log.warn(
                    "KIS {} 검출 — token cache evict 후 1회 재발급 시도: userId={} type={}",
                    KIS_TOKEN_EXPIRED_CODE,
                    userId,
                    accountType.name,
                )
                tokenHelper.evictToken(userId, accountType)
                return fetchFullBalanceInternal(userId, accountType, retried = true)
            }
            throw e
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
            if (!signalSent) {
                log.warn(
                    "[KisRateLimit] ApiKisBalanceQuery acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): userId={}",
                    userId,
                )
            }
        }
    }

    // ── private — credential / token ──────────────────────────────────────────

    private fun loadCredential(
        userId: String,
        accountType: AccountType,
    ): UserKisCredentialJpaEntity =
        credentialRepository
            .findAllByAccountType(accountType.name)
            .firstOrNull { it.id.userId == userId }
            ?: throw IllegalStateException(
                "KIS 자격증명 없음 — userId=$userId type=${accountType.name}",
            )

    // ── private — response parsing ────────────────────────────────────────────

    /**
     * output1 보유 종목 파싱 — qty 0 항목 필터 (Bootstrap 어댑터와 동일 규칙).
     *
     * `internal` 가시성 — 단위 테스트에서 파싱 정합 검증 목적 (헌법 §6 함정 무관 · Repository 외부).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseOutput1(response: Map<String, Any>): List<AccountHolding> {
        val output1 = response["output1"] as? List<Map<String, Any>> ?: return emptyList()
        return output1.mapNotNull { item ->
            val code = item["pdno"]?.toString() ?: return@mapNotNull null
            val qty = item["hldg_qty"]?.toString()?.toIntOrNull() ?: 0
            if (qty <= 0) return@mapNotNull null
            AccountHolding(
                code = code,
                name = item["prdt_name"]?.toString().orEmpty(),
                quantity = qty,
                avgPrice = bd(item["pchs_avg_pric"]),
                currentPrice = bd(item["prpr"]),
                evaluationAmount = bd(item["evlu_amt"]),
                profitLoss = bd(item["evlu_pfls_amt"]),
                profitLossRate = bd(item["evlu_pfls_rt"]),
                orderableQuantity = item["ord_psbl_qty"]?.toString()?.toIntOrNull(),
            )
        }
    }

    /**
     * output2 잔고 합계 파싱 — 11 필드 (LOCK · KIS-first raw fetch 정합).
     *
     * mock 합성 0 LOCK (feedback_no_mock_data):
     * - output2 응답이 null·empty면 IllegalStateException 발행 → controller가 502 변환
     * - 0 값 합성으로 frontend에 정상 응답 보이기 reject
     *
     * `internal` 가시성 — 단위 테스트에서 11 필드 매핑 검증 목적.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseOutput2(response: Map<String, Any>): AccountBalance {
        val raw = response["output2"]
        val first =
            when (raw) {
                is List<*> -> (raw.firstOrNull() as? Map<String, Any>)
                is Map<*, *> -> raw as Map<String, Any>
                else -> null
            } ?: throw IllegalStateException("KIS inquire-balance output2 응답 부재 — mock 합성 0 LOCK")

        return AccountBalance(
            cashBalance = bd(first["dnca_tot_amt"]),
            cashD1 = bd(first["nxdy_excc_amt"]),
            cashD2 = bd(first["prvs_rcdl_excc_amt"]),
            totalEvaluation = bd(first["tot_evlu_amt"]),
            netAssetValue = bd(first["nass_amt"]),
            totalPurchaseAmount = bd(first["pchs_amt_smtl_amt"]),
            totalHoldingEvaluation = bd(first["evlu_amt_smtl_amt"]),
            totalProfitLoss = bd(first["evlu_pfls_smtl_amt"]),
            assetIncreaseDecrease = bdOrNull(first["asst_icdc_amt"]),
            assetIncreaseDecreaseRate = bdOrNull(first["asst_icdc_erng_rt"]),
            fetchedAt = Instant.now(),
        )
    }

    // ── private — util ────────────────────────────────────────────────────────

    private fun bd(v: Any?): BigDecimal = v?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun bdOrNull(v: Any?): BigDecimal? = v?.toString()?.toBigDecimalOrNull()

    private fun parseHost(baseUrl: String): String = baseUrl.removePrefix("https://").substringBefore(":")

    private fun parsePort(baseUrl: String): Int =
        baseUrl
            .removePrefix("https://")
            .substringAfter(":", "")
            .toIntOrNull() ?: 443
}
