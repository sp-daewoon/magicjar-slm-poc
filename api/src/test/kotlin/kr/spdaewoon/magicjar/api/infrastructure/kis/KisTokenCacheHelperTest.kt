package kr.spdaewoon.magicjar.api.infrastructure.kis

import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialId
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * KisTokenCacheHelper 단위 테스트 — T-1·2·3·4 통합 가드.
 *
 * 검증 대상 (사용자 호출 path 단순화 — WebClient 통합 호출은 통합 테스트 영역):
 * 1. tokenKey / lockKey 단일 prefix LOCK (T-1)
 * 2. expires_in 동적 TTL 계산 (T-3)
 *    - 양수 → × 0.95
 *    - 0/음수/누락 → fallback 23h
 *    - 너무 작은 값(< 60s) coerceAtLeast(60) 보장
 *
 * 통합 테스트(분산락 직렬화, KIS WebClient stub)는 별도 사이클의 통합 시나리오 영역.
 */
class KisTokenCacheHelperTest {
    private val webClient: WebClient = mock()
    private val redis: StringRedisTemplate = mock()
    private val secretCipher: SecretCipher = mock()
    private val credentialRepository: UserKisCredentialJpaRepository = mock()

    private val sut =
        KisTokenCacheHelper(
            webClient = webClient,
            redisTemplate = redis,
            secretCipher = secretCipher,
            credentialRepository = credentialRepository,
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )

    @Test
    fun `T-1 단일 prefix LOCK — tokenKey는 kis_token prefix`() {
        val key = KisTokenCacheHelper.tokenKey("u-001", AccountType.REAL)
        assertThat(key).isEqualTo("kis:oauth:token:user:u-001:account:REAL")
        assertThat(key).doesNotStartWith("access_token")
    }

    @Test
    fun `T-1 단일 prefix LOCK — MOCK accountType 도 동일 prefix`() {
        val key = KisTokenCacheHelper.tokenKey("u-001", AccountType.MOCK)
        assertThat(key).isEqualTo("kis:oauth:token:user:u-001:account:MOCK")
    }

    @Test
    fun `T-1 lockKey 는 kis_token_lock prefix`() {
        val lockKey = KisTokenCacheHelper.lockKey("u-001", AccountType.REAL)
        assertThat(lockKey).isEqualTo("kis:oauth:token:user:u-001:account:REAL:lock")
    }

    @Test
    fun `T-3 expires_in 동적 TTL — 86400s 응답 → 82080s (95퍼센트)`() {
        // KIS 표준 응답값 86400s (24h)
        val ttl = sut.computeTtl(86400L)
        // 86400 × 0.95 = 82080
        assertThat(ttl).isEqualTo(Duration.ofSeconds(82080L))
    }

    @Test
    fun `T-3 expires_in 동적 TTL — 3600s 응답 → 3420s`() {
        val ttl = sut.computeTtl(3600L)
        // 3600 × 0.95 = 3420
        assertThat(ttl).isEqualTo(Duration.ofSeconds(3420L))
    }

    @Test
    fun `T-3 expires_in 누락 fallback — 23h`() {
        val ttl = sut.computeTtl(-1L)
        assertThat(ttl).isEqualTo(Duration.ofHours(23L))
    }

    @Test
    fun `T-3 expires_in 0 fallback — 23h`() {
        val ttl = sut.computeTtl(0L)
        assertThat(ttl).isEqualTo(Duration.ofHours(23L))
    }

    @Test
    fun `T-3 expires_in 매우 작은 값 — 60s 최소 보장 (음수 보호)`() {
        // 50 × 0.95 = 47 < 60 → coerceAtLeast(60)
        val ttl = sut.computeTtl(50L)
        assertThat(ttl).isEqualTo(Duration.ofSeconds(60L))
    }

    @Test
    fun `T-3 parseExpiresInSeconds — Long`() {
        assertThat(sut.parseExpiresInSeconds(86400L)).isEqualTo(86400L)
    }

    @Test
    fun `T-3 parseExpiresInSeconds — Integer`() {
        assertThat(sut.parseExpiresInSeconds(86400)).isEqualTo(86400L)
    }

    @Test
    fun `T-3 parseExpiresInSeconds — String 숫자`() {
        assertThat(sut.parseExpiresInSeconds("86400")).isEqualTo(86400L)
    }

    @Test
    fun `T-3 parseExpiresInSeconds — null 응답 → -1`() {
        assertThat(sut.parseExpiresInSeconds(null)).isEqualTo(-1L)
    }

    @Test
    fun `T-3 parseExpiresInSeconds — 비숫자 String → -1`() {
        assertThat(sut.parseExpiresInSeconds("invalid")).isEqualTo(-1L)
    }

    // ── P0 hotfix 2026-05-20 (backend-core) — 빈 userId cache pollution 차단 ───────────────────

    @Test
    fun `P0 hotfix — getAccessToken userId 빈 문자열 IllegalArgumentException`() {
        assertThatThrownBy { sut.getAccessToken(userId = "", accountType = AccountType.REAL) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("userId 빈 문자열 차단")
            .hasMessageContaining("kis:oauth:token:user::account:REAL")
    }

    @Test
    fun `P0 hotfix — getAccessToken userId 공백 IllegalArgumentException`() {
        assertThatThrownBy { sut.getAccessToken(userId = "   ", accountType = AccountType.MOCK) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("userId 빈 문자열 차단")
            .hasMessageContaining("kis:oauth:token:user::account:MOCK")
    }

    @Test
    fun `P0 hotfix — resolveTokenAndKeys userId 빈 문자열 IllegalArgumentException`() {
        assertThatThrownBy { sut.resolveTokenAndKeys(userId = "", accountType = AccountType.REAL) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("userId 빈 문자열 차단")
    }

    // ── 2026-05-21 hotfix (backend-core) — EGW00123 self-heal cache evict ───────────────────

    @Test
    fun `EGW00123 self-heal — evictToken Redis delete 호출 (cache key prefix LOCK)`() {
        val valueOps: org.springframework.data.redis.core.ValueOperations<String, String> = mock()
        whenever(redis.opsForValue()).thenReturn(valueOps)

        sut.evictToken(userId = "u-001", accountType = AccountType.REAL)

        verify(redis).delete("kis:oauth:token:user:u-001:account:REAL")
    }

    @Test
    fun `EGW00123 self-heal — evictToken MOCK accountType 도 동일 prefix delete`() {
        sut.evictToken(userId = "u-001", accountType = AccountType.MOCK)

        verify(redis).delete("kis:oauth:token:user:u-001:account:MOCK")
    }

    @Test
    fun `EGW00123 self-heal — evictToken userId 빈 문자열 차단 (caller 결함 fail-fast)`() {
        assertThatThrownBy { sut.evictToken(userId = "", accountType = AccountType.REAL) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("userId 빈 문자열 차단")
    }

    @Test
    fun `EGW00123 self-heal — evictToken Redis delete 예외 graceful (log warn + 계속)`() {
        whenever(redis.delete(org.mockito.kotlin.any<String>())).thenThrow(RuntimeException("redis down"))

        // 예외 흡수 — 호출자에 throw 하면 self-heal 자체가 실패해 의미 상실
        sut.evictToken(userId = "u-001", accountType = AccountType.REAL)

        verify(redis).delete("kis:oauth:token:user:u-001:account:REAL")
    }

    @Test
    fun `P0 hotfix — loadCredential userId 매칭 실패 시 fallback 없이 throw (cross-user leak 차단)`() {
        // DB에 첫 row 가 있지만 요청 userId와 매칭 안 됨 → 기존 fallback 으로 첫 row 반환 (root cause)
        // 신규: 정확 매칭 실패 시 IllegalStateException (fallback 폐기)
        val storedCred =
            UserKisCredentialJpaEntity(
                id = UserKisCredentialId(userId = "stored-user", accountType = AccountType.REAL.name),
                accountNumber = "11111111",
                appKeyEnc = ByteArray(0),
                secretKeyEnc = ByteArray(0),
            )
        whenever(credentialRepository.findAllByAccountType(AccountType.REAL.name))
            .thenReturn(listOf(storedCred))

        // 요청 userId="other-user" — DB row 의 "stored-user" 와 매칭 실패
        assertThatThrownBy { sut.resolveTokenAndKeys(userId = "other-user", accountType = AccountType.REAL) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("KIS 자격증명 매칭 실패")
            .hasMessageContaining("other-user")
            .hasMessageContaining("cross-user leak 차단")
    }
}
