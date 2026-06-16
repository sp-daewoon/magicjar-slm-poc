package kr.spdaewoon.magicjar.api.stomp

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.persistence.Column
import kr.spdaewoon.magicjar.api.messaging.StompEventPersister
import kr.spdaewoon.magicjar.domain.stomp.StompEvent
import kr.spdaewoon.magicjar.domain.stomp.port.StompEventRepository
import kr.spdaewoon.magicjar.persistence.shared.stomp.StompEventJpaEntity
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.junit.jupiter.api.Test

/**
 * v3.6.18 hotfix 회귀 가드 — stomp_event.payload_json jsonb cast.
 *
 * ### root cause (PR #523 결함, 2026-05-19 13:30 KST docker logs evidence)
 * ```
 * ERROR: column "payload_json" is of type jsonb but expression is of type character varying
 * ```
 * - `StompEventJpaEntity.payloadJson` 필드에 `@JdbcTypeCode(SqlTypes.JSON)` 누락 시 Hibernate 6은
 *   Kotlin `String`을 PostgreSQL `character varying`으로 전달 → JSONB 컬럼 cast 실패.
 * - 동일 패턴 fix: PR #530 (`ConsumerAuditTrailJpaEntity.detailJson`).
 *
 * ### 3 회귀 가드
 *  (1) JPA entity 필드 reflection — `payloadJson`에 `@JdbcTypeCode(SqlTypes.JSON)` annotation 존재 확인
 *  (2) JPA entity `@Column(columnDefinition)` 매핑이 "jsonb" 인지 확인 (대소문자 무관)
 *  (3) StompEventPersister payload 직렬화 실패 시 "{}" fallback 발행 — audit row 누락 0건 보장
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
class StompEventJpaEntityJsonCastTest {
    @Test
    fun `(1) payloadJson 필드는 @JdbcTypeCode(SqlTypes_JSON) annotation 보유 — Hibernate 6 jsonb cast`() {
        val field =
            StompEventJpaEntity::class.java.getDeclaredField("payloadJson")
        val ann = field.getAnnotation(JdbcTypeCode::class.java)
        assertThat(ann)
            .describedAs(
                "StompEventJpaEntity.payloadJson 필드에 @JdbcTypeCode 누락 — " +
                    "v3.6.18 jsonb cast 결함 회귀. PR #530 패턴 정합 필요.",
            ).isNotNull
        assertThat(ann.value)
            .describedAs("@JdbcTypeCode value는 SqlTypes.JSON 이어야 함 (jsonb 매핑)")
            .isEqualTo(SqlTypes.JSON)
    }

    @Test
    fun `(2) payload_json 컬럼 정의는 jsonb 명시 — Flyway V41 stomp_event 스키마 정합`() {
        val field =
            StompEventJpaEntity::class.java.getDeclaredField("payloadJson")
        val column = field.getAnnotation(Column::class.java)
        assertThat(column).isNotNull
        assertThat(column.name).isEqualTo("payload_json")
        assertThat(column.columnDefinition.lowercase())
            .describedAs("columnDefinition은 'jsonb' 이어야 함 — Flyway V41 매핑 LOCK")
            .isEqualTo("jsonb")
        assertThat(column.nullable).isFalse
    }

    @Test
    fun `(3) StompEventPersister — payload 직렬화 실패 시 빈 '{}' fallback 으로 audit row 발행`() {
        val brokenMapper = mockk<ObjectMapper>()
        every { brokenMapper.writeValueAsString(any()) } throws RuntimeException("synthetic serialize failure")
        val repository = mockk<StompEventRepository>()
        val savedSlot = slot<StompEvent>()
        every { repository.save(capture(savedSlot)) } answers { savedSlot.captured.copy(id = 99L) }

        val persister = StompEventPersister(repository, brokenMapper, enabled = true)
        persister.persist(
            userId = 74683223L,
            channel = "recon",
            eventType = "reconcile.run.completed",
            payload = mapOf("user" to "x"),
        )

        verify(exactly = 1) { repository.save(any()) }
        assertThat(savedSlot.captured.payloadJson)
            .describedAs("직렬화 실패 시 빈 '{}' fallback 으로 jsonb 정합 보장 — backfill 추적성 유지")
            .isEqualTo("{}")
        assertThat(savedSlot.captured.userId).isEqualTo(74683223L)
        assertThat(savedSlot.captured.channel).isEqualTo("recon")
        assertThat(savedSlot.captured.eventType).isEqualTo("reconcile.run.completed")
    }

    @Test
    fun `(4) StompEventPersister — enabled=false 시 영속 skip + repository 미호출`() {
        val mapper = ObjectMapper()
        val repository = mockk<StompEventRepository>(relaxed = true)

        val persister = StompEventPersister(repository, mapper, enabled = false)
        persister.persist(
            userId = 1L,
            channel = "order",
            eventType = "FILLED",
            payload = mapOf("k" to "v"),
        )

        verify(exactly = 0) { repository.save(any()) }
    }
}
