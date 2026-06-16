package kr.spdaewoon.magicjar.api.service.order

import kr.spdaewoon.magicjar.api.controller.order.dto.ManualOrderRequestDto
import kr.spdaewoon.magicjar.api.messaging.order.ManualOrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.order.execution.OrderIntent
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.kafka.KafkaException
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException

/**
 * 수동 주문 서비스 (ADR 0082 T-MAN B-2-extended LOCK · spec §3-2 본문).
 *
 * 책임:
 *   1. validate(request) — Bean Validation 후 도메인 추가 검증 (priceType=LIMIT 시 limitPrice 의무)
 *   2. preCheckRealGate(userId) — 환경 변수 + DB consent 3중 게이트 fast precheck (UX 가속 한정)
 *   3. preCheckKillSwitch(userId) — KillSwitchQueryPort UX 가속 한정
 *   4. buildIntent(request) — OrderIntent 생성 (source=MANUAL LOCK · signalId/strategyId=0 sentinel)
 *   5. publish(intent) — ManualOrderIntentKafkaProducer 위임
 *
 * **권위는 consumer OrderRouterKafkaListener step 3·4** — api 측 precheck는 fail-fast UX 가속만.
 * api precheck PASS 후 → consumer가 재검증(권위 가정) → step 12 동일 path.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082)
 */
@Service
class ManualOrderService(
    private val manualProducer: ManualOrderIntentKafkaProducer,
    private val consentService: RealTradingConsentService,
    // ObjectProvider — KillSwitchQueryPort 부재 시 graceful skip (api 측 fast precheck 한정)
    private val killSwitchProvider: ObjectProvider<KillSwitchQueryPort>,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
    @Value("\${magicjar.allow-real:false}")
    private val magicjarAllowReal: Boolean,
) {
    private val log = LoggerFactory.getLogger(ManualOrderService::class.java)

    /**
     * 수동 주문 처리 — endpoint controller 호출 진입점.
     *
     * side·priceType DTO @Pattern 1차 검증 통과 가정 (controller @Valid LOCK).
     * 방어적 valueOf — DTO 우회(reflection·테스트 등) 이상 입력 시 400 fallback.
     *
     * @throws ResponseStatusException 400 validation 위반 / 403 게이트 차단 / 503 Kafka publish 실패
     */
    fun submit(request: ManualOrderRequestDto): ManualOrderSubmitResult {
        val side = OrderSide.valueOf(request.side.trim().uppercase())
        val priceType = OrderPriceType.valueOf(request.priceType.trim().uppercase())
        val limitPrice = validateLimitPrice(priceType, request.limitPrice)

        // ── api 측 fast precheck (UX 가속 — 권위는 consumer) ──
        preCheckKillSwitch(request.userId)
        preCheckRealGate(request.userId)

        // ── OrderIntent 생성 + Kafka publish ──
        // FIX B-1 (P0) — idempotencyKey 강화 (코덱스 review P2):
        // 기존 "manual:{userId}:{epochSecond}" → 같은 사용자·같은 초·다른 종목/방향 충돌 risk.
        // 신규 "manual:{userId}:{code}:{side}:{UUID}" — UUID 도입으로 동일 초 다중 주문 무조건 unique.
        // 향후 client-supplied Idempotency-Key 헤더(Stripe 패턴) 도입 시 헤더 우선 + 본 sentinel fallback.
        val now = Instant.now()
        val idempotencyKey = "manual:${request.userId}:${request.code}:${side.name}:${UUID.randomUUID()}"
        val intent =
            OrderIntent(
                id = 0L, // MANUAL sentinel — consumer step 5 INSERT 시점에 채번
                userId = request.userId,
                signalId = 0L, // MANUAL sentinel (V38 FK relax + audit 이중)
                strategyId = 0L, // MANUAL sentinel
                code = request.code,
                side = side,
                priceType = priceType,
                quantity = request.quantity,
                limitPrice = limitPrice,
                idempotencyKey = idempotencyKey,
                requestedAt = now,
                source = OrderSource.MANUAL,
            )

        // FIX B-3 (P2) — catch 좁힘:
        // 기존 RuntimeException 광범위 (IllegalArgumentException도 503 분류 risk).
        // 신규 KafkaException · ExecutionException specific — 진짜 Kafka publish 실패만 503.
        try {
            manualProducer.publish(intent)
        } catch (e: KafkaException) {
            log.error("ManualOrderService.submit Kafka publish 실패(KafkaException): userId={} err={}", request.userId, e.message)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KAFKA_PUBLISH_FAILED: ${e.message}")
        } catch (e: ExecutionException) {
            log.error("ManualOrderService.submit Kafka publish 실패(ExecutionException): userId={} err={}", request.userId, e.message)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KAFKA_PUBLISH_FAILED: ${e.message}")
        }

        log.info(
            "ManualOrderService.submit OK: userId={} code={} side={} priceType={} qty={} idempotencyKey={}",
            request.userId,
            request.code,
            side,
            priceType,
            request.quantity,
            idempotencyKey,
        )
        return ManualOrderSubmitResult(idempotencyKey = idempotencyKey)
    }

    // ── private helper ──

    private fun validateLimitPrice(
        priceType: OrderPriceType,
        limitPriceStr: String?,
    ): BigDecimal? {
        if (priceType == OrderPriceType.MARKET) {
            return null
        }
        // LIMIT 시 not null + 양수
        if (limitPriceStr.isNullOrBlank()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "INVALID_LIMIT_PRICE: priceType=LIMIT 시 limitPrice는 필수입니다.",
            )
        }
        val parsed =
            runCatching { BigDecimal(limitPriceStr) }
                .getOrElse {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_LIMIT_PRICE: limitPrice 형식 오류 (실제=$limitPriceStr)",
                    )
                }
        if (parsed <= BigDecimal.ZERO) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "INVALID_LIMIT_PRICE: limitPrice는 양수여야 합니다 (실제=$parsed)",
            )
        }
        return parsed
    }

    /**
     * KillSwitch fast precheck — KillSwitchQueryPort 부재(JPA 미합류 시) graceful skip.
     */
    private fun preCheckKillSwitch(userId: String) {
        val killSwitch = killSwitchProvider.ifAvailable ?: return
        if (killSwitch.isOn(userId)) {
            log.warn("ManualOrderService precheck KILLSWITCH_ON 차단: userId={}", userId)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "KILLSWITCH_ON: 킬스위치 ON — 모든 주문 차단")
        }
    }

    /**
     * 3중 게이트 fast precheck (ADR 0068 §14-3 + ADR 0082 §3-2):
     *   1. env magicjar.mode == "REAL"
     *   2. env magicjar.allow-real == true
     *   3. user_account.real_trading_consent_at != null
     * 1·2·3 중 하나라도 false면 MOCK fallback이 정상 path (REAL_GATE_DENIED 차단 아님).
     * 단, MOCK fallback 모드에서도 주문은 통과 → MOCK 가상계좌로 실행.
     * REAL_GATE_DENIED 403은 mode=REAL 명시지만 다른 조건 미충족인 경우만 (UX 명시).
     *
     * 즉 api precheck는 "사용자가 REAL을 원했는데 조건 미충족" 케이스만 fail-fast.
     * MOCK fallback은 정상 path로 흘려보냄 (consumer step 3 권위 재검증).
     */
    private fun preCheckRealGate(userId: String) {
        val modeIsReal = RuntimeModeParser.isRealMode(magicjarMode)
        if (!modeIsReal) {
            // MOCK 모드 — MOCK fallback 정상 path (차단 X)
            return
        }
        // mode=REAL 진입 → 조건 2·3 검증
        if (!magicjarAllowReal) {
            log.warn(
                "ManualOrderService precheck REAL_GATE_DENIED:ALLOW_REAL: userId={} mode={} allowReal={}",
                userId,
                magicjarMode,
                magicjarAllowReal,
            )
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "REAL_GATE_DENIED:ALLOW_REAL — 환경변수 MAGICJAR_ALLOW_REAL=true 의무",
            )
        }
        val consentAt = consentService.getConsentAt(userId)
        if (consentAt == null) {
            log.warn(
                "ManualOrderService precheck REAL_GATE_DENIED:CONSENT: userId={} consent=null",
                userId,
            )
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "REAL_GATE_DENIED:CONSENT — SettingsModal 실거래 동의 토글 의무",
            )
        }
    }
}

/**
 * 수동 주문 service 응답 — controller 변환 진입점.
 */
data class ManualOrderSubmitResult(
    val idempotencyKey: String,
)
