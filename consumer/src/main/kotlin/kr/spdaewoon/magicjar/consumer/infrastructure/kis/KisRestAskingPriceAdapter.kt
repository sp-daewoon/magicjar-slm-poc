package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.port.EntryQuote
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration

/**
 * KIS REST 1호가 조회 어댑터 — ADR 0114 §10-3 R2-C1 진입 호가 **2차 fallback**.
 *
 * Redis 호가 캐시(`orderbook:top:{code}`) miss/stale 시 1회 동기 조회한다. 진입 시점 1회 호출이라 TPS 부담이
 * 낮다(ADR 0037 10 TPS 내). 실패 시 **null** 반환 — 시장가 fallback 금지는 상위 정책(EntryPricePolicy)이며
 * 본 어댑터는 정직하게 null(호가 미가용 = 진입 스킵 `SIGNAL_SKIPPED_NO_QUOTE`).
 *
 * ## KIS-first 출처 (헌법 §3)
 * - TR_ID: `FHKST01010200` (주식현재가 호가/예상체결)
 * - endpoint: GET `/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn`
 * - params: `FID_COND_MRKT_DIV_CODE=J`(KRX), `FID_INPUT_ISCD={code}`
 * - open-trading-api 출처:
 *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_asking_price_exp_ccn/inquire_asking_price_exp_ccn.py
 *   (tr_id·endpoint·params 직접 인용. real/demo 동일 tr_id `FHKST01010200`)
 * - 응답 output1 필드(KIS 공식 lowercase 규약 — WS H0UNASP0 `KisWsOrderbookParser` 동일 호가 source 대문자 ASKP1/BIDP1/ASKP_RSQN1과 1:1):
 *   · `askp1`        매도호가1
 *   · `bidp1`        매수호가1
 *   · `askp_rsqn1`   매도호가 잔량1
 *   · `aspr_acpt_hour` 호가 접수 시간(HHMMSS)
 * - magicjar.kis 키 정책: 정보 수집(호가)은 실전 키([AccountType.REAL]) — 사용자 메모리 project_kis_key_realonly_for_data.
 *
 * rate limit: [KisCallPriority.USER_PATH](진입은 사용자 자본 path) + 기존 [KisRateLimiterPort] 재사용(신규 bucket 0).
 */
@Component
class KisRestAskingPriceAdapter(
    private val webClient: WebClient,
    private val kisAuthAdapter: KisAuthAdapter,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.ws.user-id:}")
    private val defaultUserId: String,
    /**
     * 호가 REST 자격증명 userId resolve용 credential repository.
     *
     * F11(2026-06-12 라이브 incident) — `magicjar.kis.ws.user-id`(WS approval 발급 식별자)는 REST OAuth
     * 자격증명(user_kis_credential)의 user_id와 다를 수 있다. 당일 일봉 seed가 동일 결함으로 전량 skip했던
     * 선례(hotfix c95b5391: approval=thdeodns 캐시 정상이나 REST 토큰은 키 보유 user 74683223으로만 발급)와
     * 같은 패턴 — 호가 fallback도 REAL 키 보유 userId를 우선 resolve하고 부재 시 ws.user-id fallback.
     * ObjectProvider: qa fixture 등 repo 부재 환경 부팅 보장(fallback skip graceful 정합).
     */
    private val credentialRepositoryProvider: ObjectProvider<ConsumerUserKisCredentialJpaRepository>,
    private val secretCipher: SecretCipher,
) {
    private val log = LoggerFactory.getLogger(KisRestAskingPriceAdapter::class.java)

    /**
     * `FHKST01010200` 1호가 조회. 가용 시 [EntryQuote], 미가용(빈 호가·파싱 실패·REST 오류·자격증명 부재) 시 null.
     *
     * timeout 3s(다른 USER_PATH 조회 정합). asOf는 응답 `aspr_acpt_hour`(호가접수시각) 우선, 없으면 수신 시각.
     */
    fun fetchTopQuote(code: String): EntryQuote? {
        val credential = resolveRestCredential()
        if (credential == null) {
            // REAL 자격증명 부재(qa fixture 등) — KIS 필수 appkey/appsecret 헤더를 만들 수 없으므로 fallback skip.
            log.warn("KIS asking-price REST fallback skip — REAL 자격증명 resolve 불가: code={}", code)
            return null
        }
        val accountType = AccountType.REAL
        val token = kisAuthAdapter.getAccessToken(credential.userId, accountType)

        // D-1 try-finally drift 차단 패턴 — acquire를 try 안으로, signalSent flag로 통계 미발행 path 감시.
        var signalSent = false
        return try {
            kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 3000L, trId = "FHKST01010200")

            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .get()
                    .uri(
                        "$kisBaseProd/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn" +
                            "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=$code",
                    ).header("authorization", "Bearer $token")
                    .header("appkey", credential.appKey)
                    .header("appsecret", credential.appSecret)
                    .header("tr_id", "FHKST01010200")
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(3))
                    .block() as? Map<String, Any>

            kisRateLimiterPort.onSuccess()
            signalSent = true

            if (response == null) {
                log.warn("KIS asking-price 응답 없음: code={}", code)
                return null
            }
            @Suppress("UNCHECKED_CAST")
            val output1 =
                response["output1"] as? Map<String, Any>
                    ?: run {
                        log.warn("KIS asking-price output1 없음: code={}", code)
                        return null
                    }

            parseOutput1(code, output1)
        } catch (e: WebClientResponseException) {
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                log.warn("KIS asking-price Rate Limit — onTpsExceeded: code={} status={}", code, e.statusCode.value())
            }
            log.error(
                "KIS asking-price 실패: code={} status={} body={}",
                code,
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            // 진입 호가 fallback은 실패 시 null(시장가 fallback 금지는 상위 정책) — throw 하지 않는다.
            null
        } catch (e: Exception) {
            log.error("KIS asking-price 예외: code={} error={}", code, e.message)
            null
        } finally {
            if (!signalSent) {
                log.warn(
                    "[KisRateLimit] asking-price acquire 후 통계 시그널 미발행 — drift risk: code={}",
                    code,
                )
            }
        }
    }

    /**
     * output1 → [EntryQuote]. ask1/bid1 미가용(blank·≤0)이면 null(빈 호가로 진입가 오염 방지).
     * (internal — 단위 테스트가 KIS 응답 fixture로 직접 검증.)
     */
    internal fun parseOutput1(
        code: String,
        output1: Map<String, Any>,
    ): EntryQuote? {
        val ask1 = output1["askp1"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull()
        val bid1 = output1["bidp1"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull()
        if (ask1 == null || ask1 <= BigDecimal.ZERO || bid1 == null || bid1 <= BigDecimal.ZERO) {
            log.warn("KIS asking-price 1호가 미가용: code={} askp1={} bidp1={}", code, ask1, bid1)
            return null
        }
        val askQty1 = output1["askp_rsqn1"]?.toString()?.ifBlank { null }?.toLongOrNull()
        val asOf = parseAcptHour(output1["aspr_acpt_hour"]?.toString())
        return EntryQuote(ask1 = ask1, bid1 = bid1, askQty1 = askQty1, asOf = asOf)
    }

    /**
     * 호가 REST 호출 userId resolve — REAL 자격증명 보유 user 우선, 부재 시 ws.user-id fallback.
     * (user_kis_credential은 개인용 single-user 전제 — 첫 행 사용. 둘 다 없으면 blank → fallback skip.)
     * hotfix c95b5391(당일 일봉 seed) resolveSeedUserId() 동일 패턴.
     */
    private fun resolveRestUserId(): String {
        val repo = credentialRepositoryProvider.ifAvailable ?: return defaultUserId
        return try {
            repo
                .findAllByAccountType("REAL")
                .firstOrNull()
                ?.id
                ?.userId ?: defaultUserId
        } catch (e: Exception) {
            log.debug("KIS asking-price credential resolve 실패 — ws.user-id fallback: {}", e.message)
            defaultUserId
        }
    }

    private fun resolveRestCredential(): RestCredential? {
        val repo = credentialRepositoryProvider.ifAvailable ?: return null
        val cred =
            try {
                val creds = repo.findAllByAccountType(AccountType.REAL.name)
                creds.firstOrNull { it.id.userId == defaultUserId }
                    ?: creds.firstOrNull()
            } catch (e: Exception) {
                log.debug("KIS asking-price credential resolve 실패: {}", e.message)
                null
            } ?: return null

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            RestCredential(
                userId = cred.id.userId,
                appKey = String(appKey),
                appSecret = String(secretKey),
            )
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    private data class RestCredential(
        val userId: String,
        val appKey: String,
        val appSecret: String,
    )

    /**
     * `aspr_acpt_hour`(HHMMSS, KST) → [Instant]. 파싱 불가 시 수신 시각(now)으로 graceful.
     * (호가접수시각 — KIS는 당일 시각만 제공하므로 오늘 날짜 + HHMMSS 조합.)
     */
    private fun parseAcptHour(hhmmss: String?): Instant {
        val now = Clock.System.now()
        if (hhmmss == null || hhmmss.length != 6) return now
        return try {
            val local = now.toLocalDateTime(KST_ZONE)
            val hour = hhmmss.substring(0, 2).toInt()
            val minute = hhmmss.substring(2, 4).toInt()
            val second = hhmmss.substring(4, 6).toInt()
            LocalDateTime(local.year, local.monthNumber, local.dayOfMonth, hour, minute, second)
                .toInstant(KST_ZONE)
        } catch (e: Exception) {
            now
        }
    }

    private companion object {
        private val KST_ZONE = TimeZone.of("Asia/Seoul")
    }
}
