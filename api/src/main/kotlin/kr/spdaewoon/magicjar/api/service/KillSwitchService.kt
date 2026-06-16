package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.controller.KillSwitchStateResponse
import kr.spdaewoon.magicjar.api.controller.KillSwitchToggleRequest
import kr.spdaewoon.magicjar.api.messaging.KillSwitchEngagedProducer
import kr.spdaewoon.magicjar.api.persistence.killswitch.KillSwitchWriteEntity
import kr.spdaewoon.magicjar.api.persistence.killswitch.KillSwitchWriteJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * KillSwitch 수동 토글 서비스 (ADR 0074 §2-3 + spec §2-3 정합).
 *
 * GET: kill_switch_state DB 단일 source 조회 (Redis 캐시 0건 — ADR 0043 §2-5 정합)
 * PUT: DB UPDATE + Kafka killswitch.engaged.v1 publish (schema 확장 — engaged:boolean)
 *
 * 20초 cooldown: frontend 담당 (ADR 0074 §3-4 §j 채택 — backend 강제 없음, 2026-05-20 60→20 단축 dogfooding)
 * KillSwitch OFF = 사용자 수동만 (ADR 0043 §2-3 자동 OFF 0 LOCK 정합)
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@Service
class KillSwitchService(
    private val killSwitchWriteJpaRepository: KillSwitchWriteJpaRepository,
    private val killSwitchEngagedProducer: KillSwitchEngagedProducer,
) {
    private val log = LoggerFactory.getLogger(KillSwitchService::class.java)

    /**
     * KillSwitch 현재 상태 조회 (GET /api/system-config/kill-switch).
     * DB 단일 source — Redis cache 없음 (ADR 0043 §2-5).
     * row 없으면 enabled=false (초기 상태) 반환 — 합성 데이터 X.
     * KillSwitchWriteJpaRepository 사용 — updated_by 포함.
     */
    @Transactional(readOnly = true)
    fun getState(userId: String): KillSwitchStateResponse {
        val entity = killSwitchWriteJpaRepository.findById(userId).orElse(null)
        return if (entity != null) {
            KillSwitchStateResponse(
                enabled = entity.enabled,
                reason = entity.reason,
                updatedAt = entity.updatedAt,
                updatedBy = entity.updatedBy,
            )
        } else {
            // 초기 row 없음 — enabled=false (합성 가상 상태 금지 — 메모리 feedback_no_mock_data.md)
            log.debug("KillSwitch row 없음 — userId={}**** 초기 상태 반환", userId.take(4))
            KillSwitchStateResponse(
                enabled = false,
                reason = null,
                updatedAt = null,
                updatedBy = null,
            )
        }
    }

    /**
     * KillSwitch 수동 토글 (PUT /api/system-config/kill-switch).
     * ADR 0043 §2-3: 사용자 수동 토글만 (자동 OFF 0).
     *
     * @param userId 실전 계좌번호
     * @param request engaged + reason
     * @return 새 상태
     */
    @Transactional
    fun toggle(
        userId: String,
        request: KillSwitchToggleRequest,
    ): KillSwitchStateResponse {
        val now = Instant.now()
        val updatedBy = "USER:$userId"

        // upsert — 기존 row 있으면 UPDATE, 없으면 INSERT
        val existingOpt = killSwitchWriteJpaRepository.findById(userId)

        if (existingOpt.isPresent) {
            val entity = existingOpt.get()
            entity.enabled = request.engaged
            entity.reason = request.reason
            entity.updatedAt = now
            entity.updatedBy = updatedBy
            killSwitchWriteJpaRepository.save(entity)
        } else {
            killSwitchWriteJpaRepository.save(
                KillSwitchWriteEntity(
                    userId = userId,
                    enabled = request.engaged,
                    reason = request.reason,
                    updatedAt = now,
                    updatedBy = updatedBy,
                ),
            )
        }

        // Kafka publish — killswitch.engaged.v1 schema 확장 (ADR 0074 §2-3-3)
        killSwitchEngagedProducer.publish(
            userId = userId,
            engaged = request.engaged,
            reason = request.reason,
            updatedBy = updatedBy,
        )

        val stateLabel = if (request.engaged) "ON" else "OFF"
        log.warn(
            "KillSwitch 수동 {} — userId={}**** reason={}",
            stateLabel,
            userId.take(4),
            request.reason.take(50),
        )

        return KillSwitchStateResponse(
            enabled = request.engaged,
            reason = request.reason,
            updatedAt = now,
            updatedBy = updatedBy,
        )
    }
}
