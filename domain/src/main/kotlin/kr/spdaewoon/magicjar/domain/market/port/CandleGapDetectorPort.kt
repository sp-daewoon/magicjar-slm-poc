package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import java.time.Instant

/**
 * 캔들 갭 검출 포트 (ADR 0086 §2-2 + T-CC Phase 2-A LOCK).
 *
 * 책임:
 *   - 종목·timeframe별 영업일 캘린더(ADR 0013) 기반 expected sequence 생성
 *   - DB actual sequence 조회 (CandleRepository.findAllTsByCodeAndIntervalBetween)
 *   - diff 산출 → 갭 list 반환
 *
 * 호출자:
 *   - CandleBackfillWorker (consumer/.../aggregation) — Phase 2-A 본체
 *   - CandleBackfillAdminController — admin endpoint
 *
 * 햇징 룰 정합 (ADR 0086 §2-1):
 *   - 룰 #6 false positive 가드 — 거래정지·정리매매·휴장일·신규 상장일 이전 ts 제외 (구현 위치는 어댑터)
 *   - 룰 #7 false negative 가드 — last_ts null 시 Long.MAX_VALUE (Phase 1 패턴 재사용)
 *
 * 자본 영향 0 — 본 port는 조회만 수행하며 데이터 변경 0건.
 */
interface CandleGapDetectorPort {
    /**
     * 종목·interval별 갭 검출.
     *
     * 알고리즘 (ADR 0086 §2-3 LOCK):
     *   ```
     *   expected = (now - windowDays..now).filter { calendar.isBusinessDay(it) }
     *   actual   = candleRepository.findAllTsByCodeAndIntervalBetween(code, interval, from, to)
     *   gaps     = expected.filterNot { it in actualSet }
     *   ```
     *
     * 윈도우 LOCK 사유:
     *   - KIS 호출 cost 보호 (ADR 0037 — 초당 10건)
     *   - chart-curator skill §캔들 데이터 SLA 매트릭스 3m·5m·15m·30m·90m·1h·4h·12h 30 거래일 정합
     *   - 30 거래일 × 1m = 21,600 ts/종목 (in-memory diff 1초 이내)
     *
     * @param code 종목 코드 (KIS 6자리, 예: "004090")
     * @param interval 캔들 인터벌 (1d / 1m 지원 — 3m·5m·15m·30m·90m·1h·4h·12h는 AggregateBackfillRunner 위임)
     * @param windowDays 최근 거래일 윈도우 (default 30, LOCK — admin endpoint에서 override 가능)
     * @return 누락된 ts list (영업일 expected sequence diff, ts ASC)
     */
    fun detectGaps(
        code: String,
        interval: CandleInterval,
        windowDays: Int = 30,
    ): List<Instant>
}
