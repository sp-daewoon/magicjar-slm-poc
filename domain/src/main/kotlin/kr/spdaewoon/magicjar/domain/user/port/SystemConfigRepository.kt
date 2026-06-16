package kr.spdaewoon.magicjar.domain.user.port

import kr.spdaewoon.magicjar.domain.user.SystemConfig

/**
 * 시스템 설정 영속 포트 (ADR 0046 §2-3 singleton 정합).
 */
interface SystemConfigRepository {
    fun get(): SystemConfig?

    fun upsert(config: SystemConfig): SystemConfig
}
