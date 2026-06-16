package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * DART 공시 JPA 엔티티 (T-NEWS Phase 1 — ADR 0075 §2-1).
 *
 * 테이블: disclosure (V32__t_news_disclosure_table.sql)
 * 멱등성: ux_disclosure_rcept_no (rcept_no UQ) — ON CONFLICT DO NOTHING
 * 인덱스: ix_disclosure_stock_code_ingested_at + ix_disclosure_rcept_dt
 *
 * DART OPENDART list.json 출처 (헌법 §3 — 자체 추론 0):
 *   API 가이드: https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
 *   endpoint  : https://opendart.fss.or.kr/api/list.json
 *
 * 응답 list[] 필드 (raw 1:1 매핑 — ADR 0075 §7-4):
 *   corp_code, corp_name, stock_code, report_nm, rcept_no, flr_nm, rcept_dt, rm
 *
 * is_correction 분류 기준:
 *   - report_nm.startsWith("[기재정정]") = true → is_correction=true
 *   - rm?.contains("정") = true → is_correction=true
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합 — sentiment NULL 고정, T-AI 사이클 분리)
 */
@Entity
@Table(name = "disclosure")
class DisclosureJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "corp_code", length = 8, nullable = false)
    val corpCode: String,
    @Column(name = "stock_code", length = 20, nullable = true)
    val stockCode: String?,
    @Column(name = "rcept_no", length = 20, nullable = false)
    val rceptNo: String,
    @Column(name = "rcept_dt", length = 8, nullable = false)
    val rceptDt: String,
    @Column(name = "corp_name", length = 200, nullable = false)
    val corpName: String,
    @Column(name = "report_nm", length = 500, nullable = false)
    val reportNm: String,
    @Column(name = "flr_nm", length = 100, nullable = true)
    val flrNm: String?,
    @Column(name = "rm", length = 50, nullable = true)
    val rm: String?,
    @Column(name = "url", length = 500, nullable = false)
    val url: String,
    @Column(name = "is_correction", nullable = false)
    val isCorrection: Boolean = false,
    @Column(name = "ingested_at", nullable = false, updatable = false)
    val ingestedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        corpCode = "",
        stockCode = null,
        rceptNo = "",
        rceptDt = "",
        corpName = "",
        reportNm = "",
        flrNm = null,
        rm = null,
        url = "",
    )
}
