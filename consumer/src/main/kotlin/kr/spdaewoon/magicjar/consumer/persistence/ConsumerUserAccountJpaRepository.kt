package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * consumer 모듈의 user_account JPA Repository — RealTradingConsentPort 배선용.
 *
 * 헌법 §6 (ADR 0051): interface body 0건 — JpaRepository 기본 `findById`만 사용
 * (PK = userId). query inference 위험이 있는 default fun 미정의.
 */
interface ConsumerUserAccountJpaRepository : JpaRepository<ConsumerUserAccountJpaEntity, String>
