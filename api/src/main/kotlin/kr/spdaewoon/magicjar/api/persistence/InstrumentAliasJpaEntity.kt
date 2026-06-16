package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

/**
 * instrument_alias JPA 엔티티 (T-IM1 — Flyway V4 신규 테이블).
 *
 * 복합 PK: code + alias + source.
 * alias 예: "삼성전자", "samsung electronics", "005930".
 * source: KRX(공식) / NAVER(Naver 금융) / FNGUIDE.
 */
@Entity
@Table(name = "instrument_alias")
@IdClass(InstrumentAliasId::class)
class InstrumentAliasJpaEntity(
    @Id
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Id
    @Column(name = "alias", length = 120, nullable = false)
    val alias: String,
    @Id
    @Column(name = "source", length = 20, nullable = false)
    val source: String,
) {
    protected constructor() : this(code = "", alias = "", source = "KRX")
}

/**
 * instrument_alias 복합 PK 클래스.
 */
data class InstrumentAliasId(
    val code: String = "",
    val alias: String = "",
    val source: String = "",
) : Serializable
