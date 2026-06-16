package kr.spdaewoon.magicjar.domain.user

import java.time.Instant

/**
 * 온보딩 완료 이벤트 (ADR 0046 §2-8, spec §4-4 정합).
 * POJO — Spring 의존 0. api 모듈에서 ApplicationEventPublisher로 발행.
 * consumer 모듈은 PR-7+ T-W2에서 @EventListener로 listen.
 */
data class UserOnboardedEvent(
    val userId: String,
    val onboardedAt: Instant,
)
