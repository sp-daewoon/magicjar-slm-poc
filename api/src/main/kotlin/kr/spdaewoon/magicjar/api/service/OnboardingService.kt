package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.adapter.kis.KisAuthValidator
import kr.spdaewoon.magicjar.api.adapter.kis.ValidationResult
import kr.spdaewoon.magicjar.api.controller.KisMockRequest
import kr.spdaewoon.magicjar.api.controller.KisMockResponse
import kr.spdaewoon.magicjar.api.controller.KisValidateRequest
import kr.spdaewoon.magicjar.api.controller.KisValidateResponse
import kr.spdaewoon.magicjar.api.controller.OnboardingCompleteResponse
import kr.spdaewoon.magicjar.api.controller.OnboardingStatus
import kr.spdaewoon.magicjar.api.controller.SystemConfigRequest
import kr.spdaewoon.magicjar.api.controller.SystemConfigResponse
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaEntity
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialId
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.api.persistence.killswitch.KillSwitchWriteEntity
import kr.spdaewoon.magicjar.api.persistence.killswitch.KillSwitchWriteJpaRepository
import kr.spdaewoon.magicjar.api.secret.AesGcmCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import kr.spdaewoon.magicjar.domain.user.UserOnboardedEvent
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

/**
 * 온보딩 비즈니스 로직 (ADR 0046 §2-2, spec §4 정합).
 * - KIS 실전/모의 키 검증 후 AES-256-GCM 암호화 → DB 영속
 * - 시스템 설정 (DART·Slack webhook) 갱신
 * - 온보딩 완료 이벤트 발행
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class OnboardingService(
    private val userAccountJpaRepository: UserAccountJpaRepository,
    private val userKisCredentialJpaRepository: UserKisCredentialJpaRepository,
    private val killSwitchWriteJpaRepository: KillSwitchWriteJpaRepository,
    private val systemConfigJpaRepository: SystemConfigJpaRepository,
    private val auditTrailJpaRepository: AuditTrailJpaRepository,
    private val kisAuthValidator: KisAuthValidator,
    private val aesGcmCipher: AesGcmCipher,
    private val eventPublisher: ApplicationEventPublisher,
    private val webClient: WebClient,
) {
    private val log = LoggerFactory.getLogger(OnboardingService::class.java)

    companion object {
        private const val AUDIT_ACTOR = "user"
        private const val SLACK_TEST_TIMEOUT_SECONDS = 3L
    }

    /**
     * 온보딩 상태 조회 (spec §4-1-1).
     * 단일 사용자 시스템: hasUserAccount=true 시 첫 등록 row userId 포함.
     * frontend store auto-sync — 새 탭/브라우저/localStorage 클리어 후에도 즉시 복원.
     */
    @Transactional(readOnly = true)
    fun getStatus(): OnboardingStatus {
        val hasUserAccount = userAccountJpaRepository.existsAny()
        val userId =
            if (hasUserAccount) {
                userAccountJpaRepository.findFirstUserIds(PageRequest.of(0, 1)).firstOrNull()
            } else {
                null
            }
        return OnboardingStatus(
            hasUserAccount = hasUserAccount,
            hasMockAccount = userKisCredentialJpaRepository.existsMockAccount(),
            hasDartKey = systemConfigJpaRepository.hasDartApiKey(),
            hasSlackWebhook = systemConfigJpaRepository.hasSlackWebhook(),
            userId = userId,
        )
    }

    /**
     * 시스템 설정 조회.
     *
     * DB 저장은 계속 AES-256-GCM 암호문을 권위로 유지하되, 설정 화면 재진입 UX를 위해
     * DART API 키와 Slack webhook URL은 복호화한 평문으로 반환한다.
     */
    @Transactional(readOnly = true)
    fun getSystemConfig(): SystemConfigResponse {
        val config =
            systemConfigJpaRepository.findSingleton()
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    """{"code":"SYSTEM_CONFIG_NOT_FOUND","message":"시스템 설정을 찾을 수 없습니다"}""",
                )

        return SystemConfigResponse(
            hasDartKey = config.dartApiKey != null,
            hasSlackWebhook = config.notificationSlackWebhook != null,
            dartApiKey = decryptSystemConfigValue(config.dartApiKey, "dart_api_key"),
            slackWebhookUrl = decryptSystemConfigValue(config.notificationSlackWebhook, "notification_slack_webhook"),
            realtimeFallbackBuyEnabled = config.realtimeFallbackBuyEnabled,
            updatedAt = config.updatedAt,
        )
    }

    /**
     * KIS 실전 키 검증 + 영속 (spec §4-1-2).
     * - KIS OAuth tokenP 호출 → 200 + access_token 수신 시 통과
     * - AES-256-GCM 암호화 후 DB INSERT
     * - 메모리 zero-fill (성공·실패 양쪽 finally)
     */
    @Transactional
    fun validateAndPersistKisReal(request: KisValidateRequest): KisValidateResponse {
        val appKeyBytes = request.appKey.toByteArray(Charsets.UTF_8)
        val secretKeyBytes = request.secretKey.toByteArray(Charsets.UTF_8)

        try {
            // 이미 등록된 경우 409
            if (userAccountJpaRepository.existsById(request.accountNumber)) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    """{"code":"ALREADY_REGISTERED","message":"이미 등록된 사용자입니다"}""",
                )
            }

            // KIS OAuth tokenP 검증
            val validationResult =
                kisAuthValidator.validate(
                    accountType = AccountType.REAL,
                    appKey = request.appKey,
                    secretKey = request.secretKey,
                )

            when (validationResult) {
                is ValidationResult.Failed ->
                    throw ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        """{"code":"KIS_AUTH_FAILED","message":"KIS 키 검증 실패 — 콘솔에서 키 재발급"}""",
                    )
                is ValidationResult.Unavailable ->
                    throw ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        """{"code":"KIS_UNAVAILABLE","message":"KIS 서비스 일시 장애 — 잠시 후 재시도"}""",
                    )
                is ValidationResult.Success -> Unit // 통과
            }

            // AES-256-GCM 암호화
            val appKeyEnc = aesGcmCipher.encrypt(appKeyBytes)
            val secretKeyEnc = aesGcmCipher.encrypt(secretKeyBytes)

            val now = Instant.now()

            // user_account INSERT
            val userAccount =
                userAccountJpaRepository.save(
                    UserAccountJpaEntity(
                        userId = request.accountNumber,
                        displayName = request.displayName,
                        createdAt = now,
                    ),
                )
            // JPA는 GENERATED 컬럼을 저장 후 refresh가 필요 — refresh하거나 직접 계산
            val maskedAccount = request.accountNumber.take(4) + "****"

            seedKillSwitchOffRow(request.accountNumber, now)

            // user_kis_credential (account_type='REAL') INSERT
            userKisCredentialJpaRepository.save(
                UserKisCredentialJpaEntity(
                    id = UserKisCredentialId(userId = request.accountNumber, accountType = "REAL"),
                    accountNumber = request.accountNumber,
                    appKeyEnc = appKeyEnc,
                    secretKeyEnc = secretKeyEnc,
                    lastTokenIss = (validationResult as ValidationResult.Success).tokenIssuedAt,
                    lastValidatedAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            // audit_trail INSERT
            auditTrailJpaRepository.save(
                AuditTrailJpaEntity(
                    actor = AUDIT_ACTOR,
                    action = "ONBOARD_KIS_REAL",
                    entityType = "user_kis_credential",
                    entityId = request.accountNumber,
                    detailJson = """{"accountType":"REAL"}""",
                    occurredAt = now,
                ),
            )

            log.info("KIS 실전 계좌 등록 완료 — userId=[MASKED]")

            return KisValidateResponse(
                userId = userAccount.userId,
                maskedAccount = maskedAccount,
                displayName = request.displayName,
                validatedAt = now,
            )
        } finally {
            // 메모리 zero-fill (ADR 0046 §2-5, spec §2-H)
            appKeyBytes.fill(0)
            secretKeyBytes.fill(0)
        }
    }

    /**
     * KIS 모의 계좌 추가 (spec §4-1-3).
     * - 실전 계좌 미등록 시 404
     * - 모의 host:29443으로 KIS OAuth tokenP 검증
     */
    @Transactional
    fun validateAndPersistKisMock(request: KisMockRequest): KisMockResponse {
        val appKeyBytes = request.appKey.toByteArray(Charsets.UTF_8)
        val secretKeyBytes = request.secretKey.toByteArray(Charsets.UTF_8)

        try {
            // 실전 계좌 존재 확인 (없으면 404)
            if (!userAccountJpaRepository.existsById(request.userId)) {
                throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    """{"code":"REAL_ACCOUNT_NOT_FOUND","message":"실전 계좌 등록 후 모의 계좌를 추가할 수 있습니다"}""",
                )
            }

            // 이미 모의 계좌 등록 시 409
            if (userKisCredentialJpaRepository.existsByUserIdAndAccountType(request.userId, "MOCK")) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    """{"code":"ALREADY_REGISTERED","message":"이미 등록된 모의 계좌입니다"}""",
                )
            }

            // KIS 모의 OAuth tokenP 검증
            val validationResult =
                kisAuthValidator.validate(
                    accountType = AccountType.MOCK,
                    appKey = request.appKey,
                    secretKey = request.secretKey,
                )

            when (validationResult) {
                is ValidationResult.Failed ->
                    throw ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        """{"code":"KIS_AUTH_FAILED","message":"KIS 모의 키 검증 실패 — 콘솔에서 키 재발급"}""",
                    )
                is ValidationResult.Unavailable ->
                    throw ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        """{"code":"KIS_UNAVAILABLE","message":"KIS 서비스 일시 장애 — 잠시 후 재시도"}""",
                    )
                is ValidationResult.Success -> Unit
            }

            // AES-256-GCM 암호화
            val appKeyEnc = aesGcmCipher.encrypt(appKeyBytes)
            val secretKeyEnc = aesGcmCipher.encrypt(secretKeyBytes)

            val now = Instant.now()

            userKisCredentialJpaRepository.save(
                UserKisCredentialJpaEntity(
                    id = UserKisCredentialId(userId = request.userId, accountType = "MOCK"),
                    accountNumber = request.mockAccountNumber,
                    appKeyEnc = appKeyEnc,
                    secretKeyEnc = secretKeyEnc,
                    lastTokenIss = (validationResult as ValidationResult.Success).tokenIssuedAt,
                    lastValidatedAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            auditTrailJpaRepository.save(
                AuditTrailJpaEntity(
                    actor = AUDIT_ACTOR,
                    action = "ONBOARD_KIS_MOCK",
                    entityType = "user_kis_credential",
                    entityId = request.userId,
                    detailJson = """{"accountType":"MOCK"}""",
                    occurredAt = now,
                ),
            )

            log.info("KIS 모의 계좌 등록 완료")
            return KisMockResponse(userId = request.userId, mockAccountNumber = request.mockAccountNumber)
        } finally {
            appKeyBytes.fill(0)
            secretKeyBytes.fill(0)
        }
    }

    /**
     * 시스템 전역 설정 갱신 (spec §4-1-4).
     * - DART 키는 검증 없이 AES 암호화
     * - Slack webhook은 test message 1회 발송 (200 OK 시 등록)
     */
    @Transactional
    fun updateSystemConfig(request: SystemConfigRequest): SystemConfigResponse {
        val config =
            systemConfigJpaRepository.findSingleton()
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    """{"code":"SYSTEM_CONFIG_NOT_FOUND","message":"시스템 설정을 찾을 수 없습니다"}""",
                )

        val now = Instant.now()

        // DART 키 처리
        if (request.dartApiKey != null) {
            config.dartApiKey =
                when {
                    request.dartApiKey.isEmpty() -> null // 빈 문자열 = 삭제
                    else -> aesGcmCipher.encrypt(request.dartApiKey.toByteArray(Charsets.UTF_8))
                }
        }

        // Slack webhook 처리
        if (request.slackWebhookUrl != null) {
            when {
                request.slackWebhookUrl.isEmpty() -> config.notificationSlackWebhook = null
                else -> {
                    // Slack test message 발송 (timeout 3s, retry 0)
                    val testOk = sendSlackTestMessage(request.slackWebhookUrl)
                    if (!testOk) {
                        throw ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            """{"code":"SLACK_WEBHOOK_FAILED","message":"Slack webhook 검증 실패 — URL 확인 후 재시도"}""",
                        )
                    }
                    config.notificationSlackWebhook =
                        aesGcmCipher.encrypt(request.slackWebhookUrl.toByteArray(Charsets.UTF_8))
                }
            }
        }

        request.realtimeFallbackBuyEnabled?.let {
            config.realtimeFallbackBuyEnabled = it
        }

        config.updatedAt = now
        systemConfigJpaRepository.save(config)

        auditTrailJpaRepository.save(
            AuditTrailJpaEntity(
                actor = AUDIT_ACTOR,
                action = "SYSTEM_CONFIG_UPDATED",
                entityType = "system_config",
                entityId = "1",
                detailJson =
                    """{"hasDartKey":${config.dartApiKey != null},"hasSlackWebhook":${config.notificationSlackWebhook != null},"realtimeFallbackBuyEnabled":${config.realtimeFallbackBuyEnabled}}""",
                occurredAt = now,
            ),
        )

        return SystemConfigResponse(
            hasDartKey = config.dartApiKey != null,
            hasSlackWebhook = config.notificationSlackWebhook != null,
            dartApiKey = decryptSystemConfigValue(config.dartApiKey, "dart_api_key"),
            slackWebhookUrl = decryptSystemConfigValue(config.notificationSlackWebhook, "notification_slack_webhook"),
            realtimeFallbackBuyEnabled = config.realtimeFallbackBuyEnabled,
            updatedAt = now,
        )
    }

    private fun decryptSystemConfigValue(
        encrypted: ByteArray?,
        fieldName: String,
    ): String? {
        if (encrypted == null || encrypted.isEmpty()) return null
        return runCatching {
            String(aesGcmCipher.decrypt(encrypted), Charsets.UTF_8)
        }.onFailure {
            log.warn("system_config {} 복호화 실패 — 응답에서는 null 처리: err={}", fieldName, it.message)
        }.getOrNull()
    }

    /**
     * KillSwitchQueryPort fail-closed 정책 정합.
     *
     * 자본 경로에서는 row 없음이 ON으로 해석되므로, 정상 온보딩 사용자는 명시적 OFF row를 보유해야 한다.
     */
    private fun seedKillSwitchOffRow(
        userId: String,
        now: Instant,
    ) {
        if (killSwitchWriteJpaRepository.existsById(userId)) {
            return
        }
        killSwitchWriteJpaRepository.save(
            KillSwitchWriteEntity(
                userId = userId,
                enabled = false,
                reason = null,
                updatedAt = now,
                updatedBy = "ONBOARDING",
            ),
        )
    }

    /**
     * 온보딩 완료 처리 (spec §4-1-5).
     * - 실전 계좌 미등록 시 412 Precondition Failed
     * - UserOnboardedEvent 발행 (PR-7+ consumer에서 listen)
     */
    @Transactional
    fun complete(userId: String): OnboardingCompleteResponse {
        // 실전 계좌 존재 확인
        if (!userKisCredentialJpaRepository.existsByUserIdAndAccountType(userId, "REAL")) {
            throw ResponseStatusException(
                HttpStatus.PRECONDITION_FAILED,
                """{"code":"REAL_ACCOUNT_REQUIRED","message":"KIS 실전 계좌 등록 후 완료할 수 있습니다"}""",
            )
        }

        val now = Instant.now()

        // last_login_at 갱신
        val userAccount =
            userAccountJpaRepository.findById(userId).orElseThrow {
                ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    """{"code":"REAL_ACCOUNT_NOT_FOUND","message":"등록된 사용자를 찾을 수 없습니다"}""",
                )
            }
        userAccount.lastLoginAt = now
        userAccountJpaRepository.save(userAccount)

        // audit_trail
        auditTrailJpaRepository.save(
            AuditTrailJpaEntity(
                actor = AUDIT_ACTOR,
                action = "ONBOARDING_COMPLETED",
                entityType = "user_account",
                entityId = userId,
                occurredAt = now,
            ),
        )

        // UserOnboardedEvent 발행 (consumer PR-7+에서 listen)
        eventPublisher.publishEvent(UserOnboardedEvent(userId = userId, onboardedAt = now))
        log.info("온보딩 완료 이벤트 발행 — userId=[MASKED]")

        val maskedAccount = userId.take(4) + "****"
        return OnboardingCompleteResponse(userId = userId, maskedAccount = maskedAccount)
    }

    /**
     * Slack webhook test message 발송 (timeout 3s, retry 0).
     * 200 OK → true, 실패 → false.
     */
    private fun sendSlackTestMessage(webhookUrl: String): Boolean =
        runCatching {
            webClient
                .post()
                .uri(webhookUrl)
                .header("Content-Type", "application/json")
                .bodyValue("""{"text":"magicJar 셋업 — Slack webhook 검증 OK"}""")
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(SLACK_TEST_TIMEOUT_SECONDS))
                .block()
            true
        }.getOrElse { ex ->
            when (ex) {
                is WebClientRequestException -> {
                    log.warn("Slack webhook 연결 실패: {}", ex.message)
                    false
                }
                else -> {
                    log.warn("Slack webhook 검증 실패: {}", ex.message)
                    false
                }
            }
        }
}
