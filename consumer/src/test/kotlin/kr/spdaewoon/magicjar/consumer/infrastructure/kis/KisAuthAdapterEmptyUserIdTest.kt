package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.reactive.function.client.WebClient

/**
 * KisAuthAdapter 빈 userId 차단 — P0 hotfix 2026-05-20 (backend-core).
 *
 * Root cause:
 *   - Redis token cache에 빈 userId 키 발견 (사용자 dogfooding evidence)
 *   - caller 중 누군가 빈 userId 흘려 cache pollution + cross-user 토큰 leak risk
 *   - 입구에서 fail-fast 강제 → 호출자 결함 발견 즉시 노출
 *
 * AI 호출 0 — hotfix 정책 정합.
 */
class KisAuthAdapterEmptyUserIdTest {
    private val webClient: WebClient = mockk(relaxed = true)
    private val redisTemplate: StringRedisTemplate = mockk(relaxed = true)
    private val secretCipher: SecretCipher = mockk(relaxed = true)
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository = mockk(relaxed = true)

    private val sut =
        KisAuthAdapter(
            webClient = webClient,
            redisTemplate = redisTemplate,
            secretCipher = secretCipher,
            credentialRepository = credentialRepository,
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )

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
}
