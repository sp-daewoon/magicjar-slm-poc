package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.order.execution.port.RealTradingConsentPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * RealTradingConsentPort consumer-side JPA 어댑터 (REAL canary hotfix — Phase 2 배선 완결).
 *
 * ## 결함 배경 (라이브 evidence)
 * api `RealTradingConsentJpaAdapter`는 consumer ApplicationContext에 합류하지 못한다
 * (ADR 0088 모듈 경계 — 별도 Spring Boot context). 그 결과 [TradingModeGateImpl]의
 * `ObjectProvider<RealTradingConsentPort>`가 항상 미합류 → REAL 3중 게이트 조건 3에서
 * `CONSENT_PORT_UNAVAILABLE`로 모든 REAL intent 거부 (signalId=717 라이브 차단).
 *
 * ## 거동 (api 어댑터와 동일 시맨틱 — read-only)
 *   - `real_trading_consent_at` non-null → 동의 시각 Instant 반환 → 조건 3 통과
 *   - NULL/사용자 부재 → null 반환 → CONSENT_NOT_GIVEN denied
 *
 * 헌법 §11 정합: 본 빈이 합류하면 정상 조회, (만에 하나) 미합류 시 [TradingModeGateImpl]의
 * 기존 fail-closed(CONSENT_PORT_UNAVAILABLE) 거동 불변 — 게이트 판정 순서·조건 미변경.
 *
 * `setConsent` 토글은 api `RealTradingConsentService` 책임 — consumer는 read-only.
 * 헌법 §6 (ADR 0051): JpaRepository `findById`만 사용 (query inference 위험 default fun 0).
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0068 §8)
 */
@Component
class ConsumerRealTradingConsentJpaAdapter(
    private val userAccountJpaRepository: ConsumerUserAccountJpaRepository,
) : RealTradingConsentPort {
    @Transactional(readOnly = true)
    override fun findConsentAt(userId: String): Instant? =
        userAccountJpaRepository.findById(userId).map { it.realTradingConsentAt }.orElse(null)
}
