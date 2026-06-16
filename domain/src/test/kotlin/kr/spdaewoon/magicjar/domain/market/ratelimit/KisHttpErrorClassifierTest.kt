package kr.spdaewoon.magicjar.domain.market.ratelimit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * KisHttpErrorClassifier 단위 테스트 (진단 PR #639 P0 hotfix 검증).
 *
 * ### 핵심 결함 회귀 게이트
 * - EGW00201 = HTTP 500 wrap → RateLimit 분류 확정 (사용자 dogfooding 502 root cause)
 * - HTTP 429 단일 가드 회귀 reject (msg_cd 우선순위 LOCK)
 *
 * ### 7 케이스 매트릭스
 *  (a) EGW00201 + HTTP 500 → RateLimit (★ root cause fix)
 *  (b) EGW00121 + HTTP 500 → RateLimit (일일 한도 family)
 *  (c) EGW00133 + HTTP 500 → AuthError (토큰 만료 family)
 *  (d) HTTP 429 + body 없음 → RateLimit (status fallback)
 *  (e) HTTP 500 + body 없음 → NetworkError
 *  (f) HTTP 400 + body 없음 → ClientError
 *  (g) 비-JSON body (HTML 등) + HTTP 500 → NetworkError (graceful 파싱 실패)
 *  (h) HTTP 200 + rt_cd=1 + msg_cd=알 수 없음 → Unknown(매트릭스 확장 trigger)
 */
class KisHttpErrorClassifierTest {
    @Test
    fun `(a) EGW00201 HTTP 500 wrap — RateLimit 분류 확정 (root cause fix)`() {
        val body = """{"rt_cd":"1","msg1":"초당 거래건수를 초과하였습니다.","msg_cd":"EGW00201","message":"EGW00201"}"""
        val result = classifyKisError(statusCode = 500, responseBody = body)
        result shouldBe KisErrorClass.RateLimit
    }

    @Test
    fun `(b) EGW00121 일일 한도 초과 — RateLimit 분류 (family LOCK)`() {
        val body = """{"rt_cd":"1","msg_cd":"EGW00121","msg1":"일일 거래건수를 초과하였습니다."}"""
        val result = classifyKisError(statusCode = 500, responseBody = body)
        result shouldBe KisErrorClass.RateLimit
    }

    @Test
    fun `(c) EGW00133 토큰 만료 — AuthError 분류`() {
        val body = """{"rt_cd":"1","msg_cd":"EGW00133","msg1":"기간이 만료된 token 입니다."}"""
        val result = classifyKisError(statusCode = 500, responseBody = body)
        result shouldBe KisErrorClass.AuthError
    }

    @Test
    fun `(d) HTTP 429 body 없음 — RateLimit status fallback`() {
        val result = classifyKisError(statusCode = 429, responseBody = null)
        result shouldBe KisErrorClass.RateLimit
    }

    @Test
    fun `(e) HTTP 500 body 없음 — NetworkError 분류`() {
        val result = classifyKisError(statusCode = 500, responseBody = null)
        result shouldBe KisErrorClass.NetworkError
    }

    @Test
    fun `(f) HTTP 400 body 없음 — ClientError 분류`() {
        val result = classifyKisError(statusCode = 400, responseBody = "")
        result shouldBe KisErrorClass.ClientError
    }

    @Test
    fun `(g) 비-JSON body (HTML error page) — graceful 파싱 실패 + status fallback`() {
        val body = "<html><body>500 Internal Server Error</body></html>"
        val result = classifyKisError(statusCode = 500, responseBody = body)
        result shouldBe KisErrorClass.NetworkError
    }

    @Test
    fun `(h) 알 수 없는 msg_cd — Unknown(rt_cd, msg_cd) 매트릭스 확장 trigger`() {
        val body = """{"rt_cd":"1","msg_cd":"EGW99999","msg1":"unknown future code"}"""
        val result = classifyKisError(statusCode = 200, responseBody = body)
        val unknown = result.shouldBeInstanceOf<KisErrorClass.Unknown>()
        unknown.rtCd shouldBe "1"
        unknown.msgCd shouldBe "EGW99999"
    }

    @Test
    fun `parseKisErrorBody — null body 안전`() {
        val parsed = parseKisErrorBody(null)
        parsed.rtCd shouldBe null
        parsed.msgCd shouldBe null
    }

    @Test
    fun `parseKisErrorBody — empty body 안전`() {
        val parsed = parseKisErrorBody("   ")
        parsed.rtCd shouldBe null
        parsed.msgCd shouldBe null
    }
}
