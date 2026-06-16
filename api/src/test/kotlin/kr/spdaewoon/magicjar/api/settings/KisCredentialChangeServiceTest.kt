package kr.spdaewoon.magicjar.api.settings

import kr.spdaewoon.magicjar.api.adapter.kis.KisAuthValidator
import kr.spdaewoon.magicjar.api.adapter.kis.ValidationResult
import kr.spdaewoon.magicjar.api.adapter.redis.KisCacheInvalidator
import kr.spdaewoon.magicjar.api.controller.KisCredentialUpdateRequest
import kr.spdaewoon.magicjar.api.messaging.KisKeyChangedProducer
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialId
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.api.secret.AesGcmCipher
import kr.spdaewoon.magicjar.api.service.KisCredentialChangeService
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Base64
import java.util.Optional

/**
 * KisCredentialChangeService 단위 테스트 (spec §2-4-1 정합).
 *
 * 검증 항목:
 *   - updateKisCredential: KIS 검증 통과 → UPDATE + Redis DEL + Kafka publish
 *   - updateKisCredential: KIS 검증 실패 → 기존 row 보존 (atomicity)
 *   - deleteKisCredential: REAL accountType 시 403 거부
 *   - getKisCredentialsMasked: 마스킹 응답 형식 검증
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@ExtendWith(MockitoExtension::class)
class KisCredentialChangeServiceTest {
    @Mock
    private lateinit var userAccountJpaRepository: UserAccountJpaRepository

    @Mock
    private lateinit var userKisCredentialJpaRepository: UserKisCredentialJpaRepository

    @Mock
    private lateinit var auditTrailJpaRepository: AuditTrailJpaRepository

    @Mock
    private lateinit var kisAuthValidator: KisAuthValidator

    @Mock
    private lateinit var kisCacheInvalidator: KisCacheInvalidator

    @Mock
    private lateinit var kisKeyChangedProducer: KisKeyChangedProducer

    private lateinit var aesGcmCipher: AesGcmCipher
    private lateinit var service: KisCredentialChangeService

    // 테스트 전용 마스터 키 (32 bytes)
    private val testMasterKeyBase64: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    companion object {
        private const val TEST_USER_ID = "74683223"
        private const val TEST_APP_KEY = "PSxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        private const val TEST_SECRET_KEY = "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy"
    }

    @BeforeEach
    fun setUp() {
        aesGcmCipher = AesGcmCipher(testMasterKeyBase64)
        service =
            KisCredentialChangeService(
                userAccountJpaRepository = userAccountJpaRepository,
                userKisCredentialJpaRepository = userKisCredentialJpaRepository,
                auditTrailJpaRepository = auditTrailJpaRepository,
                kisAuthValidator = kisAuthValidator,
                aesGcmCipher = aesGcmCipher,
                kisCacheInvalidator = kisCacheInvalidator,
                kisKeyChangedProducer = kisKeyChangedProducer,
            )
    }

    // ─── updateKisCredential 테스트 ──────────────────────────────────────────────

    @Test
    fun `updateKisCredential - KIS 검증 통과 시 Redis DEL + Kafka publish 호출`() {
        // given
        val existingEntity = buildTestCredentialEntity(TEST_USER_ID, "REAL")
        `when`(userKisCredentialJpaRepository.findById(UserKisCredentialId(TEST_USER_ID, "REAL")))
            .thenReturn(Optional.of(existingEntity))
        `when`(
            kisAuthValidator.validate(
                accountType = AccountType.REAL,
                appKey = TEST_APP_KEY,
                secretKey = TEST_SECRET_KEY,
            ),
        ).thenReturn(ValidationResult.Success(tokenIssuedAt = Instant.now()))
        `when`(userKisCredentialJpaRepository.save(any(UserKisCredentialJpaEntity::class.java))).thenReturn(existingEntity)

        val request = KisCredentialUpdateRequest(appKey = TEST_APP_KEY, secretKey = TEST_SECRET_KEY)

        // when
        val result = service.updateKisCredential(TEST_USER_ID, "REAL", request)

        // then
        assertThat(result.hasKey).isTrue()
        assertThat(result.accountType).isEqualTo("REAL")
        assertThat(result.appKeyMasked).doesNotContain("xxxxxxxxxxxxxxxx") // 중간 평문 없어야 함
        assertThat(result.secretKeyMasked).isEqualTo("********")

        // Redis invalidate 호출 검증
        verify(kisCacheInvalidator).deleteAccessToken(TEST_USER_ID, "REAL")
        verify(kisCacheInvalidator).deleteApprovalKey(TEST_USER_ID, "REAL")
        verify(kisCacheInvalidator).setKeyChangedLock(TEST_USER_ID, "REAL")

        // Kafka publish 호출 검증 (REAL이므로 publish)
        verify(kisKeyChangedProducer).publish(TEST_USER_ID, "REAL")
    }

    @Test
    fun `updateKisCredential - KIS 검증 실패 시 401 예외 + Redis DEL 미호출 (atomicity)`() {
        // given
        val existingEntity = buildTestCredentialEntity(TEST_USER_ID, "REAL")
        `when`(userKisCredentialJpaRepository.findById(UserKisCredentialId(TEST_USER_ID, "REAL")))
            .thenReturn(Optional.of(existingEntity))
        `when`(
            kisAuthValidator.validate(
                accountType = AccountType.REAL,
                appKey = TEST_APP_KEY,
                secretKey = TEST_SECRET_KEY,
            ),
        ).thenReturn(ValidationResult.Failed(httpStatus = 401))

        val request = KisCredentialUpdateRequest(appKey = TEST_APP_KEY, secretKey = TEST_SECRET_KEY)

        // when + then
        val thrown =
            runCatching { service.updateKisCredential(TEST_USER_ID, "REAL", request) }
                .exceptionOrNull()
        assertThat(thrown).isInstanceOf(ResponseStatusException::class.java)
        val rse = thrown as ResponseStatusException
        assertThat(rse.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(rse.reason).contains("KIS_AUTH_FAILED")

        // atomicity: 기존 row UPDATE 미호출
        verify(userKisCredentialJpaRepository, never()).deleteById(any())
        verify(userKisCredentialJpaRepository, never()).save(any(UserKisCredentialJpaEntity::class.java))

        // Redis DEL 미호출
        verify(kisCacheInvalidator, never()).deleteAccessToken(anyString(), anyString())
        verify(kisCacheInvalidator, never()).deleteApprovalKey(anyString(), anyString())

        // Kafka publish 미호출
        verify(kisKeyChangedProducer, never()).publish(anyString(), anyString())
    }

    @Test
    fun `updateKisCredential - MOCK accountType 시 Kafka publish 미호출 (WS 미사용)`() {
        // given
        val existingEntity = buildTestCredentialEntity(TEST_USER_ID, "MOCK")
        `when`(userKisCredentialJpaRepository.findById(UserKisCredentialId(TEST_USER_ID, "MOCK")))
            .thenReturn(Optional.of(existingEntity))
        `when`(
            kisAuthValidator.validate(
                accountType = AccountType.MOCK,
                appKey = TEST_APP_KEY,
                secretKey = TEST_SECRET_KEY,
            ),
        ).thenReturn(ValidationResult.Success(tokenIssuedAt = Instant.now()))
        `when`(userKisCredentialJpaRepository.save(any(UserKisCredentialJpaEntity::class.java))).thenReturn(existingEntity)

        val request = KisCredentialUpdateRequest(appKey = TEST_APP_KEY, secretKey = TEST_SECRET_KEY)

        // when
        service.updateKisCredential(TEST_USER_ID, "MOCK", request)

        // then: MOCK은 WS 미사용 — Kafka publish 호출 없음
        verify(kisKeyChangedProducer, never()).publish(anyString(), anyString())
        // approval_key DEL도 미호출 (REAL만 대상)
        verify(kisCacheInvalidator, never()).deleteApprovalKey(anyString(), anyString())
    }

    // ─── deleteKisCredential 테스트 ──────────────────────────────────────────────

    @Test
    fun `deleteKisCredential - REAL accountType 시 403 반환 (ADR 0074 §2-2-1 §h)`() {
        // when + then
        val thrown =
            runCatching { service.deleteKisCredential(TEST_USER_ID, "REAL") }
                .exceptionOrNull()
        assertThat(thrown).isInstanceOf(ResponseStatusException::class.java)
        val rse = thrown as ResponseStatusException
        assertThat(rse.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(rse.reason).contains("REAL_DELETE_FORBIDDEN")

        // DB DELETE 미호출
        verify(userKisCredentialJpaRepository, never()).deleteById(any())
    }

    // ─── getKisCredentialsMasked 테스트 ──────────────────────────────────────────

    @Test
    fun `getKisCredentialsMasked - REAL row 있음 MOCK row 없음 시 hasKey 응답 정합`() {
        // given
        val encryptedAppKey = aesGcmCipher.encrypt(TEST_APP_KEY.toByteArray(Charsets.UTF_8))
        val encryptedSecretKey = aesGcmCipher.encrypt(TEST_SECRET_KEY.toByteArray(Charsets.UTF_8))
        val realEntity =
            UserKisCredentialJpaEntity(
                id = UserKisCredentialId(TEST_USER_ID, "REAL"),
                accountNumber = TEST_USER_ID,
                appKeyEnc = encryptedAppKey,
                secretKeyEnc = encryptedSecretKey,
                lastValidatedAt = Instant.now(),
            )

        `when`(userKisCredentialJpaRepository.findById(UserKisCredentialId(TEST_USER_ID, "REAL")))
            .thenReturn(Optional.of(realEntity))
        `when`(userKisCredentialJpaRepository.findById(UserKisCredentialId(TEST_USER_ID, "MOCK")))
            .thenReturn(Optional.empty())

        // when
        val result = service.getKisCredentialsMasked(TEST_USER_ID)

        // then
        assertThat(result.userId).isEqualTo(TEST_USER_ID)
        assertThat(result.credentials).hasSize(2)

        val realItem = result.credentials.first { it.accountType == "REAL" }
        assertThat(realItem.hasKey).isTrue()
        assertThat(realItem.appKeyMasked).doesNotContain("xxxxxxxxxxxxxxxx")
        assertThat(realItem.appKeyMasked).startsWith("PSxx")
        assertThat(realItem.secretKeyMasked).isEqualTo("********")

        val mockItem = result.credentials.first { it.accountType == "MOCK" }
        assertThat(mockItem.hasKey).isFalse()
        // 합성 placeholder mask 금지 확인
        assertThat(mockItem.appKeyMasked).isEqualTo("등록 없음")
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────────

    private fun buildTestCredentialEntity(
        userId: String,
        accountType: String,
    ): UserKisCredentialJpaEntity {
        val encryptedAppKey = aesGcmCipher.encrypt(TEST_APP_KEY.toByteArray(Charsets.UTF_8))
        val encryptedSecretKey = aesGcmCipher.encrypt(TEST_SECRET_KEY.toByteArray(Charsets.UTF_8))
        return UserKisCredentialJpaEntity(
            id = UserKisCredentialId(userId, accountType),
            accountNumber = userId,
            appKeyEnc = encryptedAppKey,
            secretKeyEnc = encryptedSecretKey,
            lastValidatedAt = Instant.now().minusSeconds(60),
        )
    }
}
