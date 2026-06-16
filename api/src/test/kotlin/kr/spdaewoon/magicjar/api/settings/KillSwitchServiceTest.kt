package kr.spdaewoon.magicjar.api.settings

import kr.spdaewoon.magicjar.api.controller.KillSwitchToggleRequest
import kr.spdaewoon.magicjar.api.messaging.KillSwitchEngagedProducer
import kr.spdaewoon.magicjar.api.persistence.killswitch.KillSwitchWriteEntity
import kr.spdaewoon.magicjar.api.persistence.killswitch.KillSwitchWriteJpaRepository
import kr.spdaewoon.magicjar.api.service.KillSwitchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.util.Optional

/**
 * KillSwitchService 단위 테스트 (spec §2-4-1 SystemConfigServiceKillSwitchTest 정합).
 *
 * 검증 항목:
 *   - toggle ON·OFF 둘 다 DB UPDATE + Kafka publish
 *   - getState: row 없을 때 enabled=false 초기 상태 반환
 *   - getState: row 있을 때 실제 상태 반환
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@ExtendWith(MockitoExtension::class)
class KillSwitchServiceTest {
    @Mock
    private lateinit var killSwitchWriteJpaRepository: KillSwitchWriteJpaRepository

    @Mock
    private lateinit var killSwitchEngagedProducer: KillSwitchEngagedProducer

    private lateinit var service: KillSwitchService

    companion object {
        private const val TEST_USER_ID = "74683223"
        private const val TEST_REASON_ON = "드리프트 감지 테스트"
        private const val TEST_REASON_OFF = "HTS 잔고 확인 완료"
    }

    @BeforeEach
    fun setUp() {
        service =
            KillSwitchService(
                killSwitchWriteJpaRepository = killSwitchWriteJpaRepository,
                killSwitchEngagedProducer = killSwitchEngagedProducer,
            )
    }

    // ─── toggle ON 테스트 ────────────────────────────────────────────────────────

    @Test
    fun `toggle ON - DB upsert + Kafka publish 호출 확인`() {
        // given: 기존 row 없음 (초기 상태)
        `when`(killSwitchWriteJpaRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty())
        val savedCaptor = ArgumentCaptor.forClass(KillSwitchWriteEntity::class.java)
        `when`(killSwitchWriteJpaRepository.save(savedCaptor.capture())).thenAnswer { savedCaptor.value }

        val request = KillSwitchToggleRequest(engaged = true, reason = TEST_REASON_ON)

        // when
        val result = service.toggle(TEST_USER_ID, request)

        // then
        assertThat(result.enabled).isTrue()
        assertThat(result.reason).isEqualTo(TEST_REASON_ON)
        assertThat(result.updatedBy).isEqualTo("USER:$TEST_USER_ID")
        assertThat(result.updatedAt).isNotNull()

        // DB save 호출 확인
        val savedEntity = savedCaptor.value
        assertThat(savedEntity.enabled).isTrue()
        assertThat(savedEntity.reason).isEqualTo(TEST_REASON_ON)
        assertThat(savedEntity.updatedBy).isEqualTo("USER:$TEST_USER_ID")

        // Kafka publish 호출 확인
        verify(killSwitchEngagedProducer).publish(
            userId = TEST_USER_ID,
            engaged = true,
            reason = TEST_REASON_ON,
            updatedBy = "USER:$TEST_USER_ID",
        )
    }

    @Test
    fun `toggle OFF - 기존 row 있을 때 UPDATE + Kafka publish`() {
        // given: 기존 enabled=true row 존재
        val existingEntity =
            KillSwitchWriteEntity(
                userId = TEST_USER_ID,
                enabled = true,
                reason = "이전 사유",
                updatedAt = Instant.now().minusSeconds(300),
                updatedBy = "AUTO",
            )
        `when`(killSwitchWriteJpaRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingEntity))
        `when`(killSwitchWriteJpaRepository.save(existingEntity)).thenReturn(existingEntity)

        val request = KillSwitchToggleRequest(engaged = false, reason = TEST_REASON_OFF)

        // when
        val result = service.toggle(TEST_USER_ID, request)

        // then
        assertThat(result.enabled).isFalse()
        assertThat(result.reason).isEqualTo(TEST_REASON_OFF)
        assertThat(result.updatedBy).isEqualTo("USER:$TEST_USER_ID")

        // Kafka publish 호출 확인 (engaged=false)
        verify(killSwitchEngagedProducer).publish(
            userId = TEST_USER_ID,
            engaged = false,
            reason = TEST_REASON_OFF,
            updatedBy = "USER:$TEST_USER_ID",
        )
    }

    // ─── getState 테스트 ─────────────────────────────────────────────────────────

    @Test
    fun `getState - row 없을 때 enabled=false 초기 상태 반환`() {
        // given
        `when`(killSwitchWriteJpaRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty())

        // when
        val result = service.getState(TEST_USER_ID)

        // then: 합성 가상 데이터 없음 — 초기 상태 반환
        assertThat(result.enabled).isFalse()
        assertThat(result.reason).isNull()
        assertThat(result.updatedAt).isNull()
        assertThat(result.updatedBy).isNull()
    }

    @Test
    fun `getState - row 있을 때 실제 상태 반환`() {
        // given
        val updatedAt = Instant.now().minusSeconds(60)
        val entity =
            KillSwitchWriteEntity(
                userId = TEST_USER_ID,
                enabled = true,
                reason = "POSITION_DRIFT",
                updatedAt = updatedAt,
                updatedBy = "AUTO",
            )
        `when`(killSwitchWriteJpaRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(entity))

        // when
        val result = service.getState(TEST_USER_ID)

        // then
        assertThat(result.enabled).isTrue()
        assertThat(result.reason).isEqualTo("POSITION_DRIFT")
        assertThat(result.updatedAt).isEqualTo(updatedAt)
        assertThat(result.updatedBy).isEqualTo("AUTO")
    }
}
