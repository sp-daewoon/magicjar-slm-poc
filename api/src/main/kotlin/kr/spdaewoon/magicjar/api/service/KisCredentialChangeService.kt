package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.adapter.kis.KisAuthValidator
import kr.spdaewoon.magicjar.api.adapter.kis.KisCredentialMasker
import kr.spdaewoon.magicjar.api.adapter.kis.ValidationResult
import kr.spdaewoon.magicjar.api.adapter.redis.KisCacheInvalidator
import kr.spdaewoon.magicjar.api.controller.KisCredentialDeleteResponse
import kr.spdaewoon.magicjar.api.controller.KisCredentialUpdateRequest
import kr.spdaewoon.magicjar.api.controller.MaskedKisCredentialItem
import kr.spdaewoon.magicjar.api.controller.MaskedKisCredentialResponseDto
import kr.spdaewoon.magicjar.api.messaging.KisKeyChangedProducer
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaEntity
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialId
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.api.secret.AesGcmCipher
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * KIS 자격증명 변경 서비스 (ADR 0074 §2-2 + spec §2-3 정합).
 *
 * PUT /api/onboarding/kis/{accountType} 흐름:
 *   [1] 기존 row 존재 확인 (없으면 404)
 *   [2] KisAuthValidator → KIS OAuth tokenP 검증
 *   [3] 통과 시 기존 row DELETE + 새 row INSERT (val 필드 immutability 대응)
 *   [4] Redis access_token + kis:approval DEL
 *   [5] kis:key-changed-lock SET TTL 5s
 *   [6] Kafka kafka.api.evt.system.kis-key.changed.v1 publish (REAL만)
 *   [7] 마스킹 응답 반환
 *
 * Atomicity LOCK: KIS OAuth 검증 통과 시에만 UPDATE. 실패 시 기존 row 보존.
 * 평문 키 로그·응답 절대 미포함 (ADR 0046 §2-5 + 메모리 feedback_secret_handling.md).
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@Service
class KisCredentialChangeService(
    private val userAccountJpaRepository: UserAccountJpaRepository,
    private val userKisCredentialJpaRepository: UserKisCredentialJpaRepository,
    private val auditTrailJpaRepository: AuditTrailJpaRepository,
    private val kisAuthValidator: KisAuthValidator,
    private val aesGcmCipher: AesGcmCipher,
    private val kisCacheInvalidator: KisCacheInvalidator,
    private val kisKeyChangedProducer: KisKeyChangedProducer,
) {
    private val log = LoggerFactory.getLogger(KisCredentialChangeService::class.java)

    companion object {
        private const val AUDIT_ACTOR = "user"
    }

    /**
     * KIS 자격증명 마스킹 응답 조회 (GET /api/onboarding/kis/credentials).
     * row 미등록 시 hasKey=false (합성 placeholder mask 금지 — 메모리 feedback_no_mock_data.md).
     */
    @Transactional(readOnly = true)
    fun getKisCredentialsMasked(userId: String): MaskedKisCredentialResponseDto {
        val items = mutableListOf<MaskedKisCredentialItem>()

        for (accountType in listOf("REAL", "MOCK")) {
            val credOpt = userKisCredentialJpaRepository.findById(UserKisCredentialId(userId, accountType))
            if (credOpt.isPresent) {
                val cred = credOpt.get()
                val appKeyPlain = aesGcmCipher.decrypt(cred.appKeyEnc).toString(Charsets.UTF_8)
                val appKeyMasked = KisCredentialMasker.maskAppKey(appKeyPlain)
                // zero-fill
                val appKeyBytes = appKeyPlain.toByteArray(Charsets.UTF_8)
                appKeyBytes.fill(0)

                items.add(
                    MaskedKisCredentialItem(
                        accountType = accountType,
                        accountNumber = KisCredentialMasker.maskAccountNumber(cred.accountNumber),
                        appKeyMasked = appKeyMasked,
                        secretKeyMasked = "********",
                        lastValidatedAt = cred.lastValidatedAt,
                        hasKey = true,
                    ),
                )
            } else {
                // row 미등록 — hasKey=false (합성 mask 금지)
                items.add(
                    MaskedKisCredentialItem(
                        accountType = accountType,
                        accountNumber = "등록 없음",
                        appKeyMasked = "등록 없음",
                        secretKeyMasked = "등록 없음",
                        lastValidatedAt = null,
                        hasKey = false,
                    ),
                )
            }
        }

        return MaskedKisCredentialResponseDto(userId = userId, credentials = items)
    }

    /**
     * KIS 자격증명 변경 (PUT /api/onboarding/kis/{accountType}).
     * val 필드 immutability 대응: 기존 row DELETE + 새 row INSERT (동일 PK → JPA merge/save).
     *
     * @param userId 실전 계좌번호
     * @param accountType "REAL" 또는 "MOCK"
     * @param request 새 appKey + secretKey
     * @return 마스킹 응답 단건
     */
    @Transactional
    fun updateKisCredential(
        userId: String,
        accountType: String,
        request: KisCredentialUpdateRequest,
    ): MaskedKisCredentialItem {
        val appKeyBytes = request.appKey.toByteArray(Charsets.UTF_8)
        val secretKeyBytes = request.secretKey.toByteArray(Charsets.UTF_8)

        try {
            // 기존 row 존재 확인
            val credId = UserKisCredentialId(userId, accountType)
            val existing =
                userKisCredentialJpaRepository.findById(credId).orElseThrow {
                    ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        """{"code":"CREDENTIAL_NOT_FOUND","message":"등록된 KIS $accountType 키가 없습니다. 먼저 SetupWizard에서 등록하세요"}""",
                    )
                }

            // KIS OAuth tokenP 검증 (ADR 0074 §2-2-1 핵심 흐름 step 3)
            val parsedAccountType = RuntimeModeParser.accountTypeOrMock(accountType)
            val validationResult =
                kisAuthValidator.validate(
                    accountType = parsedAccountType,
                    appKey = request.appKey,
                    secretKey = request.secretKey,
                )

            when (validationResult) {
                is ValidationResult.Failed ->
                    throw ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        """{"code":"KIS_AUTH_FAILED","message":"KIS 키 검증 실패 — KIS 콘솔에서 키 재발급"}""",
                    )
                is ValidationResult.Unavailable ->
                    throw ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        """{"code":"KIS_UNAVAILABLE","message":"KIS 서비스 일시 장애 — 잠시 후 재시도"}""",
                    )
                is ValidationResult.Success -> Unit // 통과
            }

            // AES-256-GCM 재암호화
            val newAppKeyEnc = aesGcmCipher.encrypt(appKeyBytes)
            val newSecretKeyEnc = aesGcmCipher.encrypt(secretKeyBytes)

            val now = Instant.now()

            // JPA entity val 필드 대응: 기존 row 제거 후 새 entity save (동일 PK → INSERT ON CONFLICT 또는 merge)
            // Spring Data save() 호출 시 id 존재 → merge (EntityManager.merge) → UPDATE
            // But UserKisCredentialJpaEntity appKeyEnc·secretKeyEnc가 val이므로
            // deleteById + save(new entity) 패턴 사용 (트랜잭션 내 안전)
            userKisCredentialJpaRepository.deleteById(credId)
            userKisCredentialJpaRepository.flush()

            val newEntity =
                UserKisCredentialJpaEntity(
                    id = credId,
                    accountNumber = existing.accountNumber,
                    accountPrdtCd = existing.accountPrdtCd,
                    appKeyEnc = newAppKeyEnc,
                    secretKeyEnc = newSecretKeyEnc,
                    lastTokenIss = (validationResult as ValidationResult.Success).tokenIssuedAt,
                    lastValidatedAt = now,
                    createdAt = existing.createdAt,
                    updatedAt = now,
                )
            userKisCredentialJpaRepository.save(newEntity)

            // Redis invalidate (ADR 0074 §7-1)
            kisCacheInvalidator.deleteAccessToken(userId, accountType)
            if (RuntimeModeParser.isRealMode(accountType)) {
                kisCacheInvalidator.deleteApprovalKey(userId, accountType)
            }
            kisCacheInvalidator.setKeyChangedLock(userId, accountType)

            // Kafka publish (REAL만 — MOCK은 WS 미사용, ADR 0074 §2-2-3)
            if (RuntimeModeParser.isRealMode(accountType)) {
                kisKeyChangedProducer.publish(userId, accountType)
            }

            // audit_trail
            auditTrailJpaRepository.save(
                AuditTrailJpaEntity(
                    actor = AUDIT_ACTOR,
                    action = "KIS_CREDENTIAL_UPDATED",
                    entityType = "user_kis_credential",
                    entityId = userId,
                    detailJson = """{"accountType":"$accountType"}""",
                    occurredAt = now,
                ),
            )

            log.info(
                "KIS 자격증명 변경 완료 — userId={}**** accountType={}",
                userId.take(4),
                accountType,
            )

            // 마스킹 응답 (새 키로 직접 마스킹 — 복호화 재시도 없음)
            val appKeyMasked = KisCredentialMasker.maskAppKey(request.appKey)

            return MaskedKisCredentialItem(
                accountType = accountType,
                accountNumber = KisCredentialMasker.maskAccountNumber(existing.accountNumber),
                appKeyMasked = appKeyMasked,
                secretKeyMasked = "********",
                lastValidatedAt = now,
                hasKey = true,
            )
        } finally {
            // 메모리 zero-fill (ADR 0046 §2-5)
            appKeyBytes.fill(0)
            secretKeyBytes.fill(0)
        }
    }

    /**
     * KIS MOCK 자격증명 삭제 (DELETE /api/onboarding/kis/{accountType}).
     * REAL DELETE 요청 시 403 (ADR 0074 §2-2-1 §h 채택 — REAL은 변경만 허용).
     *
     * @param userId 실전 계좌번호
     * @param accountType 삭제 대상 — "MOCK"만 허용
     */
    @Transactional
    fun deleteKisCredential(
        userId: String,
        accountType: String,
    ): KisCredentialDeleteResponse {
        // REAL 삭제 금지 (ADR 0074 §2-2-1 §h)
        if (RuntimeModeParser.isRealMode(accountType)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                """{"code":"REAL_DELETE_FORBIDDEN","message":"실전 계좌 자격증명은 삭제할 수 없습니다. 변경(PUT)만 허용됩니다"}""",
            )
        }

        val credId = UserKisCredentialId(userId, "MOCK")
        if (!userKisCredentialJpaRepository.existsById(credId)) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                """{"code":"CREDENTIAL_NOT_FOUND","message":"등록된 KIS MOCK 키가 없습니다"}""",
            )
        }

        userKisCredentialJpaRepository.deleteById(credId)

        val now = Instant.now()
        auditTrailJpaRepository.save(
            AuditTrailJpaEntity(
                actor = AUDIT_ACTOR,
                action = "KIS_CREDENTIAL_DELETED",
                entityType = "user_kis_credential",
                entityId = userId,
                detailJson = """{"accountType":"MOCK"}""",
                occurredAt = now,
            ),
        )

        log.info("KIS MOCK 자격증명 삭제 완료 — userId={}****", userId.take(4))

        return KisCredentialDeleteResponse(accountType = "MOCK")
    }

    /**
     * userId 조회 — 단일 사용자 시스템 정합.
     * user_account 첫 row의 userId를 반환. 없으면 412 예외.
     */
    fun resolveUserId(): String {
        val userIds = userAccountJpaRepository.findFirstUserIds(PageRequest.of(0, 1))
        return userIds.firstOrNull()
            ?: throw ResponseStatusException(
                HttpStatus.PRECONDITION_FAILED,
                """{"code":"ONBOARDING_REQUIRED","message":"셋업 완료 후 사용 가능합니다"}""",
            )
    }
}
