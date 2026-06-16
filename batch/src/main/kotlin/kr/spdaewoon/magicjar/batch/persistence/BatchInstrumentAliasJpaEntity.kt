package kr.spdaewoon.magicjar.batch.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

/**
 * instrument_alias JPA 엔티티 (batch 모듈 — T-IM1 sync 전용).
 * V4 마이그레이션으로 신설된 instrument_alias 테이블.
 */
@Entity
@Table(name = "instrument_alias")
@IdClass(InstrumentAliasId::class)
class BatchInstrumentAliasJpaEntity(
    @Id
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Id
    @Column(name = "alias", length = 80, nullable = false)
    val alias: String,
    @Id
    @Column(name = "source", length = 20, nullable = false)
    val source: String,
) {
    protected constructor() : this(code = "", alias = "", source = "KRX")
}

/**
 * 복합 PK 클래스.
 */
data class InstrumentAliasId(
    val code: String = "",
    val alias: String = "",
    val source: String = "",
) : Serializable
