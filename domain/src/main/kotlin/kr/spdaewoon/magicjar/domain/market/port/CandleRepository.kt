package kr.spdaewoon.magicjar.domain.market.port

import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import java.time.Instant

/**
 * 캔들 영속 포트 (ADR 0019 — UQ candle.code+interval+ts).
 * 구현은 api 모듈 persistence + consumer 모듈 persistence 각각.
 */
interface CandleRepository {
    /**
     * 캔들 일괄 저장 (ON CONFLICT DO NOTHING — idempotent).
     * @return 실제 INSERT된 row 수
     */
    fun saveAll(candles: List<Candle>): Int

    /**
     * 캔들 일괄 upsert (ON CONFLICT DO UPDATE).
     *
     * 1m gap fill 이후 3m~12h 합산봉을 재생성할 때 기존 aggregate candle 값도
     * 교정되어야 하므로, 일반 KIS raw 적재와 분리된 명시적 경로로만 사용한다.
     *
     * @return INSERT 또는 UPDATE 된 row 수
     */
    fun upsertAll(candles: List<Candle>): Int

    fun countByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): Long

    fun countByCodeAndIntervalIn(
        code: String,
        intervals: List<CandleInterval>,
    ): Long

    /**
     * 특정 종목·interval 의 전체 candle list 반환 (ts ASC).
     *
     * ADR 0062 §9 — AggregateBackfillRunner 가 1m 전량 → 3m·5m·15m·30m·90m·1h·4h·12h 합산 시 사용.
     * 30 거래일 1m 기준 ~10k~20k row 예상 (in-memory aggregation 가능 범위).
     */
    fun findByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Candle>

    /**
     * 특정 종목·interval 의 최신 candle N개 반환 (ts ASC).
     *
     * 전략 평가·실시간 신호처럼 최근 lookback만 필요한 path는 전체 시계열을 읽지 말고
     * DB에서 `ORDER BY ts DESC LIMIT N`으로 제한해야 한다. 기본 구현은 테스트/임시 구현 호환용이며,
     * 실제 JPA adapter는 native DB limit 조회로 override한다.
     */
    fun findLatestByCodeAndInterval(
        code: String,
        interval: CandleInterval,
        limit: Int,
    ): List<Candle> = findByCodeAndInterval(code, interval).takeLast(limit.coerceAtLeast(1))

    /**
     * beforeExclusive 이전 candle 중 최신 N개 반환 (ts ASC).
     *
     * boundary candle을 별도로 받은 실시간 평가에서 DB에 같은 boundary row가 있더라도 중복하지 않기 위해
     * `ts < beforeExclusive`로 조회한다.
     */
    fun findLatestBeforeByCodeAndInterval(
        code: String,
        interval: CandleInterval,
        beforeExclusive: Instant,
        limit: Int,
    ): List<Candle> =
        findByCodeAndInterval(code, interval)
            .asSequence()
            .filter { it.ts.toJavaInstant().isBefore(beforeExclusive) }
            .toList()
            .takeLast(limit.coerceAtLeast(1))

    /**
     * 특정 종목·interval 의 마지막 candle ts.
     *
     * hotfix 2026-05-15: CandleBootstrapRunner 가 부팅 시 N일 stale 검출하여 cold-start 재트리거.
     * row 0건이면 null 반환.
     */
    fun findMaxTsByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): Instant?

    /**
     * 특정 종목·interval 의 범위 내 모든 ts 조회 (ts ASC) — ADR 0086 §2-2 add-only.
     *
     * T-CC Phase 2-A: CandleGapDetector 가 expected sequence diff 산출 시 사용.
     * 전체 fetch(findByCodeAndInterval) 대비 효율 — 30 거래일 윈도우 LOCK + Instant 만 반환.
     *
     * @param code 종목 코드
     * @param interval 캔들 인터벌
     * @param from 시작 (inclusive)
     * @param to 종료 (inclusive)
     * @return 범위 내 모든 ts (ASC). row 0건이면 empty list.
     */
    fun findAllTsByCodeAndIntervalBetween(
        code: String,
        interval: CandleInterval,
        from: Instant,
        to: Instant,
    ): List<Instant>

    /**
     * 한 기간 버킷 내에서 정당 앵커([keepTs]) 외의 고아 candle 삭제 (ADR 0120 amend — 2026-06-11).
     *
     * ## 왜 필요한가 — 진행봉 앵커 전진 고아 누적 (P1 결함)
     *   1wk/1mo/1y 롤업 봉의 ts(앵커)는 KIS 관례상:
     *     - 주봉 = 그 주 **첫** 거래일 / 월·연봉 = 그 기간 **마지막** 거래일 ([DailyRollupAggregator] 표).
     *   진행 중(미마감) 월/년봉의 앵커는 "현재까지의 마지막 거래일"이라 **매 거래일 전진**한다.
     *   적재가 `ON CONFLICT(code,interval,ts) DO UPDATE` upsert 뿐이라 앵커가 6/9→6/10 으로 이동하면
     *   새 ts 행만 생기고 옛 앵커 행(6/9 partial)이 같은 버킷에 **고아로 잔존** → 차트 중복·지표 이중계산.
     *   주봉도 대칭 케이스(1d 백필로 주 초 거래일이 새로 생기면 앵커가 과거로 이동) 존재.
     *
     * ## 삭제 안전 제약 (광역 DELETE 금지 — 버킷 경계 + 앵커 불일치 이중 조건)
     *   삭제 대상은 **`bucketStart ≤ ts < bucketEnd` 반열린 기간 버킷** ∩ **`ts ≠ keepTs`** 두 조건의 교집합뿐.
     *   롤업이 이번에 산출한 봉의 버킷만 호출되므로(incremental 윈도우 안), 윈도우 밖 과거 닫힌 봉은 무손상.
     *   keepTs(이번 산출 앵커)와 일치하는 행은 절대 삭제되지 않는다 — "화요일 첫 거래일 주봉" 같은 정당 봉 보존.
     *
     * 기본 구현은 no-op(0 반환) — 롤업을 수행하지 않는 구현체(예: api 조회 어댑터) 호환용.
     * 실제 정리는 consumer 어댑터가 native DELETE 로 override 한다.
     *
     * @param code 종목 코드
     * @param interval 1wk·1mo·1y (롤업 패밀리)
     * @param bucketStart 기간 버킷 시작 (inclusive)
     * @param bucketEnd 기간 버킷 끝 (exclusive)
     * @param keepTs 보존할 정당 앵커 ts (이번 롤업 산출 봉의 ts) — 이 행은 삭제 제외
     * @return 삭제된 고아 row 수 (운영 evidence)
     */
    fun deleteStaleBucketAnchors(
        code: String,
        interval: CandleInterval,
        bucketStart: Instant,
        bucketEnd: Instant,
        keepTs: Instant,
    ): Int = 0

    /**
     * 정확히 (code, interval, ts) 1행 삭제 — ADR 0118 R-BF amend (2026-06-15 특정 날짜 1d 정밀 복구).
     *
     * ## 왜 필요한가 — 손상 row가 KIS range 미보유여서 DO UPDATE 로 덮을 수 없는 경우
     *   06-12 장전 멀티시세 0값 오염 row(open/high/low ≤0·volume=0) 를 KIS daily range 로 재fetch 했을 때,
     *   해당 종목·날짜가 실제 무거래/거래정지/신규상장 직전이라 KIS 가 그 날짜 봉을 0건 반환하면 upsert 대상이
     *   없다. 그러면 손상 0값 row 가 그대로 잔존한다(mock 합성 0 — 가짜값 채우기 금지). 손상 잔존을 막기 위해
     *   그 정확한 1행을 삭제(차트·지표가 0값 봉을 그리지 않도록)하는 좁은 경로가 필요하다.
     *
     * ## 삭제 안전 제약 (광역 DELETE 금지 — 정확 키 매칭)
     *   삭제 대상은 `code=? AND interval=? AND ts=?` 정확 1행뿐(범위·LIKE·IN 없음). 다른 날짜·다른 interval·
     *   다른 종목은 절대 건드리지 않는다. 호출자(날짜 타겟 복구)가 대상 날짜의 정확한 KST 자정 ts 로만 호출한다.
     *
     * 기본 구현은 no-op(0 반환) — 삭제를 지원하지 않는 조회 어댑터(api) 호환용.
     * 실제 삭제는 consumer 어댑터가 native DELETE 로 override 한다.
     *
     * @param code 종목 코드
     * @param interval 캔들 인터벌(복구 경로는 1d 고정)
     * @param ts 삭제할 정확한 ts (대상 날짜 KST 자정 — KIS 1d 봉 앵커 관례 정합)
     * @return 삭제된 row 수(0 또는 1)
     */
    fun deleteByCodeAndIntervalAndTs(
        code: String,
        interval: CandleInterval,
        ts: Instant,
    ): Int = 0
}
