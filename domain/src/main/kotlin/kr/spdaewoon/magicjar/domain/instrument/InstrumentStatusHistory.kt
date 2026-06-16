package kr.spdaewoon.magicjar.domain.instrument

import kotlinx.datetime.Instant

/**
 * 종목 status 시간별 history snapshot (ADR 0090 Phase 1 LOCK).
 *
 * 책임:
 *   - chart-curator §12 active gap detection axis 2·3 시점 정밀도 권위
 *   - status 변화 detection 시 row 발행 (KIS instrument master 갱신 cron — ADR 0039)
 *
 * 운영 모델:
 *   - effectiveUntil = null → 현재 활성 row (instrument code당 0~1건)
 *   - status 변화 detection 시: 기존 active row effectiveUntil=Instant.now() + 신규 row INSERT
 *
 * 정합 ADR:
 *   - ADR 0017 Instrument Master 1급 시민
 *   - ADR 0028 Watchlist Ingestion Worker
 *   - ADR 0039 종목 마스터 갱신 Cron
 *   - ADR 0085 chart-curator 영입
 *   - ADR 0086 T-CC Phase 2 데이터 햇징 13 룰
 *   - ADR 0090 instrument_status_history 신설 (본 모델)
 */
data class InstrumentStatusHistory(
    /** PK (DB auto-increment) — neu row 시 0L baseline (caller가 미사용) */
    val id: Long = 0L,
    /** KIS PDNO 6자리 종목코드 */
    val instrumentCode: String,
    /** status enum 정합 (CHECK constraint LOCK) */
    val status: InstrumentStatus,
    /** 본 status 시작 시점 — KIS instrument master 갱신 시점 */
    val effectiveFrom: Instant,
    /** 본 status 종료 시점. null = 현재 활성 row */
    val effectiveUntil: Instant? = null,
    /** KIS 응답 raw 또는 운영자 메모 (선택) */
    val reason: String? = null,
    /** row 발행 시점 (audit_trail baseline) */
    val recordedAt: Instant,
)
