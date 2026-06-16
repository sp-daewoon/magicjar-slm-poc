package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

/**
 * [CapitalSnapshotProvider] default no-op bean (ADR 0067 §7-1 — graceful fallback only).
 *
 * **현행 정합 (T-ACCT Phase 3 머지 — 2026-05-18 ADR 0081 §2-5)**: 실제 어댑터
 * [KisCapitalSnapshotProviderAdapter] (`@Component`) 가 등록되면 본 default bean은
 * `@ConditionalOnMissingBean(CapitalSnapshotProvider::class)` 로 자동 비활성. 본 bean은 다음 케이스만 활성:
 * 1. consumer 모듈 단독 부팅 시 `KisCapitalSnapshotProviderAdapter` import 부재 (테스트 fixture 등)
 * 2. 본 어댑터 revert 시 자동 fallback
 *
 * ### F10 (2026-06-12) — 0 fabricate 폐기, [CapitalOutcome.Unavailable] 반환
 * 이 no-op 은 실제로 잔고를 알 수 없는 상태다. 과거엔 0 을 반환해 POSITION_LIMIT 거짓 차단으로 이어졌으나
 * (14:12 incident 와 동일 부류), "알 수 없음"을 0 으로 위장하는 것은 금지된다(결재 §4). 따라서
 * [capitalOutcome] 은 [CapitalOutcome.Unavailable] 을 반환해 호출부가 시그널을 skip(audit) 하게 한다.
 * [getCapital] 은 레거시 시그니처라 0 을 유지하되 주문경로는 [capitalOutcome] 만 사용한다.
 *
 * spec 인용: ADR 0067 §5-5·§7-1 CapitalSnapshotProvider + ADR 0081 §2-5 supersede.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Configuration
class CapitalSnapshotProviderAdapter {
    private val log = LoggerFactory.getLogger(CapitalSnapshotProviderAdapter::class.java)

    @Bean
    @ConditionalOnMissingBean(CapitalSnapshotProvider::class)
    fun defaultCapitalSnapshotProvider(): CapitalSnapshotProvider =
        object : CapitalSnapshotProvider {
            override fun getCapital(userId: String): BigDecimal = BigDecimal.ZERO

            override fun capitalOutcome(userId: String): CapitalOutcome {
                log.debug(
                    "CapitalSnapshotProvider default no-op — Unavailable(skip) (KisCapitalSnapshotProviderAdapter 미등록, userId={})",
                    userId,
                )
                return CapitalOutcome.Unavailable("CAPITAL_PROVIDER_NOOP")
            }
        }
}
