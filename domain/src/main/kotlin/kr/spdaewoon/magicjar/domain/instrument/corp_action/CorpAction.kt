package kr.spdaewoon.magicjar.domain.instrument.corpaction

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 기업 행위(Corporate Action) 도메인 (ADR 0018 §2-1 정합).
 *
 * 수정주가 조정의 근거 이벤트 — candle.adjustment_factor 재계산 트리거.
 * raw_payload: KIS/DART 원본 JSON 보존 (감사용 — DELETE 금지, ADR 0017 §2-2 정합).
 * source: "KIS_KSDINFO" 우선, "DART" 보조 감사 (ADR 0018 §2-5).
 */
data class CorpAction(
    val id: Long = 0L,
    val instrumentCode: String,
    val type: CorpActionType,
    /** 권리락 기준일 (ex-date) */
    val exDate: LocalDate,
    /**
     * 분할/병합 비율.
     * 액면분할 50:1 → ratio = 50.0 (1주→50주, 가격×(1/50))
     * 무상증자 10% → ratio = 0.1 (보너스 0.1주/1주)
     */
    val ratio: BigDecimal? = null,
    /** 현금배당금 (원/주) */
    val cashDividend: BigDecimal? = null,
    /** 합병·분할 비율 */
    val mergeRatio: BigDecimal? = null,
    /** 출처 식별자 — "KIS_KSDINFO" | "DART" */
    val source: String,
    /** 원본 API 응답 JSON (감사용) */
    val rawPayload: String? = null,
    /** CandleAdjustmentJob 적용 완료 시각 (null = 미적용) */
    val appliedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
)
