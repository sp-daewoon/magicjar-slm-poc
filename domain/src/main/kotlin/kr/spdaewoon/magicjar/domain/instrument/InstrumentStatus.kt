package kr.spdaewoon.magicjar.domain.instrument

/**
 * 종목 status enum (ADR 0090 Phase 1 LOCK).
 *
 * 책임:
 *   - instrument_status_history 테이블 status 컬럼 enum 정합 (CHECK constraint baseline)
 *   - chart-curator §12 active gap detection axis 2·3 분기 권위
 *
 * 정합 ADR:
 *   - ADR 0085 chart-curator 영입
 *   - ADR 0086 T-CC Phase 2 데이터 햇징 13 룰 §12 4축
 *   - ADR 0090 instrument_status_history 신설
 *
 * 분기 룰:
 *   - NORMAL: 정상 거래 가능 (`passesActiveGapAxes` GREEN)
 *   - TRADING_HALT: 거래정지 (KIS instrument master `tr_stop_yn=Y` 또는 동등)
 *   - LIQUIDATION: 정리매매 (상장폐지 직전 limited 거래)
 *   - DELISTED: 상장폐지 (KIS instrument master `mket_id_cd` 검증 시점)
 */
enum class InstrumentStatus {
    /** 정상 거래 (`passesActiveGapAxes` GREEN) */
    NORMAL,

    /** 거래정지 — KIS `tr_stop_yn=Y` 또는 동등 */
    TRADING_HALT,

    /** 정리매매 — 상장폐지 직전 limited 거래 */
    LIQUIDATION,

    /** 상장폐지 */
    DELISTED,
}
