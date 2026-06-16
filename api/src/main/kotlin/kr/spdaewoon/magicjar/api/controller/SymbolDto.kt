package kr.spdaewoon.magicjar.api.controller

/**
 * 종목 메타 + 현재가 응답 DTO (spec §3-2 LOCK).
 *
 * source: "live" (KIS WS 실시간) | "polling" (REST polling) | "closed" (장 마감)
 * stale: true → 데이터 오래됨 (ADR 0014 fallback 3-tier 정합)
 *
 * PR-21b 보강: type·securitiesGroup·tradingHalt·managementStock·industryMedium·marketCapPrev 추가.
 * instrument V5 sync 완료 전: 신규 필드 null/false graceful.
 */
data class SymbolDto(
    val code: String,
    val name: String?,
    val market: String?,
    val industry: String?,
    val currentPrice: String,
    val previousClose: String,
    val change: String,
    val changeRate: String,
    val high: String?,
    val low: String?,
    val open: String?,
    val volume: Long?,
    val marketCap: String?,
    val per: String?,
    val pbr: String?,
    val eps: String?,
    val bps: String?,
    val asOf: String,
    val source: String,
    val stale: Boolean,
    // PR-21b 신규 — KIS-first instrument 메타 (V5 sync 전: null/false graceful)
    val type: String? = null,
    val securitiesGroup: String? = null,
    val tradingHalt: Boolean = false,
    val managementStock: Boolean = false,
    val industryMedium: String? = null,
    val marketCapPrev: Long? = null,
    val basePrice: String? = null,
    val prevVolume: Long? = null,
)

/**
 * 캔들 단건 DTO (spec §3-3).
 * ts: ISO8601 UTC. open·high·low·close: 문자열 (소수점 정밀도 보존).
 */
data class CandleDto(
    val ts: String,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: Long,
    val previousClose: String? = null,
    val change: String? = null,
    val changeRate: String? = null,
)

/**
 * 캔들 시계열 응답 DTO (spec §3-3).
 * candles: ts ASC 정렬 (LWC v4 setData() 기대).
 * hasMore: true이면 더 fetch 가능 (nextBefore cursor 사용).
 */
data class CandlesResponseDto(
    val code: String,
    val interval: String,
    val candles: List<CandleDto>,
    val hasMore: Boolean,
    val nextBefore: String?,
)

/**
 * 종목 상세 실시간 sub/unsub 응답 DTO.
 */
data class RealtimeSubscribeResponse(
    val subscribed: Boolean,
    val priceSlot: String, // "live" | "queued"
    // 종목 상세 호가는 표시 중에만 구독(2026-06-12, anchor `detail-orderbook-live-only`) → ENTER 시 "live". 이탈 즉시 해제.
    val orderbookSlot: String, // "live" (표시 중)
)

data class RealtimeUnsubscribeResponse(
    val unsubscribed: Boolean,
)

/**
 * 호가 sub/unsub 응답 DTO. deprecated alias 호환용(2026-06-12 — realtime/subscribe가 현재가+호가 단일 처리).
 * slot은 "live" — 호가는 종목 상세 표시 중(DETAIL) + 전략 ACTIVE(STRATEGY) 두 경로에서 구독된다.
 */
data class OrderbookSubscribeResponse(
    val subscribed: Boolean,
    val slot: String, // "live" (표시 중에만 구독)
)

data class OrderbookUnsubscribeResponse(
    val unsubscribed: Boolean,
)
