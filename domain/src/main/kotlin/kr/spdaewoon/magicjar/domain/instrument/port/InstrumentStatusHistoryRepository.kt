package kr.spdaewoon.magicjar.domain.instrument.port

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.instrument.InstrumentStatus
import kr.spdaewoon.magicjar.domain.instrument.InstrumentStatusHistory

/**
 * 종목 status history 영속 포트 (ADR 0090 Phase 1 LOCK).
 *
 * 책임:
 *   - instrument_status_history 테이블 영속 단일 진입점
 *   - chart-curator §12 `passesActiveGapAxes(code, ts)` 시점 lookup 권위
 *   - market-data `InstrumentMasterRefreshJob` status 변화 detection 시 row 발행
 *
 * 운영 룰 (ADR 0090 §2-2):
 *   - 추가만 LOCK — row 갱신은 effective_until backfill 1회만 허용 (history immutability)
 *   - active row(effective_until=null)는 instrument code당 0~1건 LOCK
 *
 * 정합 헌법 §6 (Repository default fun 함정 가드):
 *   - 본 interface는 abstract fun만 — default fun body 금지
 *   - 구현은 별도 adapter 단일 책임
 *
 * 정합 ADR:
 *   - ADR 0017 Instrument Master 1급 시민
 *   - ADR 0085 chart-curator 영입
 *   - ADR 0088 persistence-shared 모듈 (Phase 1 옵션 a 결정 — api 단독 발행, Phase 2 reuse 검토)
 *   - ADR 0090 instrument_status_history 신설 (본 port)
 */
interface InstrumentStatusHistoryRepository {
    /**
     * 신규 status history row 발행 (NORMAL→TRADING_HALT 같은 status 변화 시점).
     *
     * 호출 사이클:
     *   1. caller가 [findActiveByCode]로 기존 active row 조회
     *   2. caller가 [closeActiveStatus]로 기존 row effectiveUntil backfill (status 변화 시)
     *   3. caller가 [insert]로 신규 active row 발행
     *
     * @return 발행된 row (id 포함)
     */
    fun insert(history: InstrumentStatusHistory): InstrumentStatusHistory

    /**
     * 종목코드의 현재 active row 조회 (effective_until IS NULL row).
     *
     * @return active row (없으면 null — backfill 0회 종목)
     */
    fun findActiveByCode(instrumentCode: String): InstrumentStatusHistory?

    /**
     * 종목코드의 특정 시점 active status 조회 — chart-curator §12 권위 path.
     *
     * 알고리즘:
     *   - effective_from <= ts < (effective_until ?: ∞) 조건 만족 row 1건
     *   - status enum 반환 (row 없으면 null — default NORMAL 추정은 caller 결정)
     */
    fun findStatusAt(
        instrumentCode: String,
        ts: Instant,
    ): InstrumentStatus?

    /**
     * 기존 active row effective_until backfill (status 변화 detection 시).
     *
     * @return 업데이트된 row 수 (0 = active row 없음)
     */
    fun closeActiveStatus(
        instrumentCode: String,
        effectiveUntil: Instant,
    ): Int

    /**
     * watchlist 종목 baseline backfill용 — instrument 단일 row를 history 초기 row로 batch INSERT.
     *
     * Phase 1 backfill 1회 path — instrument.active=true 종목 중 history 부재 종목 1건씩 NORMAL row 발행.
     *
     * @return 발행된 row 수
     */
    fun backfillBaselineForMissingCodes(
        codes: Collection<String>,
        effectiveFrom: Instant,
    ): Int
}
