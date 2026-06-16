package kr.spdaewoon.magicjar.api.service.dto

import java.time.Instant

/**
 * 캔들 수동 갱신 endpoint 응답 DTO.
 *
 * POST /api/symbols/{code}/candles/refresh 응답.
 * refreshedIntervals: 각 interval 별 fetch/insert 결과 목록.
 * elapsedMs: 전체 처리 시간 (ms).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
data class CandleRefreshResponse(
    val refreshedIntervals: List<IntervalRefreshResult>,
    val elapsedMs: Long,
)

/**
 * 캔들 재수집 endpoint 응답 DTO.
 *
 * POST /api/symbols/{code}/candles/recollect 응답.
 * 기존 candle row를 삭제한 뒤 KIS 원천 데이터를 다시 수집하고, 합산봉 요청 시 1m 기준으로 재생성한다.
 */
data class CandleRecollectResponse(
    val deletedCount: Int,
    val resetIntervals: List<String>,
    val refreshedIntervals: List<IntervalRefreshResult>,
    val rebuiltIntervals: List<IntervalRefreshResult>,
    val elapsedMs: Long,
)

/**
 * 캔들 재수집 비동기 job 시작 응답.
 *
 * HTTP는 jobId만 즉시 반환하고, 진행 상태는 STOMP topic + REST poll fallback으로 확인한다.
 */
data class CandleRecollectJobResponse(
    val jobId: String,
    val code: String,
    val intervals: List<String>,
    val status: String,
    val topic: String,
    val submittedAt: String,
)

/**
 * 재수집 범위 옵션.
 *
 * from/to는 사용자가 검증하려는 원래 리서치 구간이고, service layer가 paddingTradingDays를
 * 거래일 기준으로 적용한 뒤 KIS 수집/DB 삭제 범위를 결정한다.
 */
data class CandleRecollectRangeRequest(
    val from: Instant,
    val to: Instant,
    val paddingTradingDays: Int = 0,
)

/**
 * 캔들 재수집 progress event.
 *
 * topic: /topic/candle.recollect.{code}.{jobId}
 */
data class CandleRecollectProgressEvent(
    val jobId: String,
    val code: String,
    val intervals: List<String>,
    val status: String,
    val stage: String,
    val processedDays: Int?,
    val totalDays: Int?,
    val deletedCount: Int,
    val insertedCount: Int,
    val rebuiltCount: Int,
    val message: String,
    val elapsedMs: Long?,
    val error: String?,
    val updatedAt: String,
)

/**
 * CandleRefreshService 내부 진행 상황 콜백 payload.
 */
data class CandleRefreshProgressUpdate(
    val stage: String,
    val interval: String?,
    val processedDays: Int? = null,
    val totalDays: Int? = null,
    val fetchedCount: Int? = null,
    val insertedCount: Int? = null,
    val message: String,
)

/**
 * interval 별 갱신 결과.
 *
 * @param interval 인터벌 코드 (예: "1d", "1wk", "1mo", "1y", "1m")
 * @param fetchedCount KIS 응답 건수
 * @param insertedCount ON CONFLICT DO NOTHING 후 실제 INSERT 건수
 * @param latestTs DB 최신 ts ISO-8601 문자열 (null = 데이터 없음)
 * @param status "OK" | "SKIPPED" | "FAILED"
 */
data class IntervalRefreshResult(
    val interval: String,
    val fetchedCount: Int,
    val insertedCount: Int,
    val latestTs: String?,
    val status: String,
)
