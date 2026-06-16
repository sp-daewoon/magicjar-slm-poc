package kr.spdaewoon.magicjar.batch.job.corpaction

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpAction
import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpActionType
import kr.spdaewoon.magicjar.domain.instrument.corpaction.port.CorpActionRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/**
 * 캔들 수정주가 소급 재계산 잡 (T-CA1 PR-29b — ADR 0018 §2-3·§4-3 정합).
 *
 * Kafka 리스너: corp_action.applied.v1 → CandleAdjustmentJob 트리거.
 *
 * 처리 흐름 (ADR 0018 §4-3):
 *  1. corp_action 1건 수신 (예: 005930 STOCK_SPLIT ratio=50 ex_date=2024-04-26)
 *  2. candle WHERE code=005930 AND ts < ex_date 전체 조회
 *  3. 보정 계수 계산:
 *     - STOCK_SPLIT    : factor = 1 / ratio (가격↓, 거래량↑)
 *     - REVERSE_SPLIT  : factor = ratio      (가격↑, 거래량↓)
 *     - BONUS_ISSUE    : factor = 1 / (1 + ratio)
 *     - CASH_DIVIDEND  : 가격 보정 불필요 (open_adj·close_adj 변경 0)
 *     - 기타           : factor = 1.0 (현재 보정 없음 — ADR 0018 §4-4)
 *  4. UPDATE candle SET
 *       open_adj  = open_raw  * cumFactor (기존 adjustment_factor 누적)
 *       high_adj  = high_raw  * cumFactor
 *       low_adj   = low_raw   * cumFactor
 *       close_adj = close_raw * cumFactor
 *       volume_adjusted = volume / factor (분할 시 거래량 증가)
 *       adjustment_factor = adjustment_factor * factor
 *     WHERE code=005930 AND ts < ex_date
 *  5. Redis indicator:cache:005930:* DEL (지표 재계산 트리거)
 *  6. corp_action.applied_at = now()
 *  7. Kafka publish corp_action.applied.v1 (완료 이벤트)
 *
 * KIS ksdinfo 출처 (헌법 §3):
 *  HHKDB669105C0 rev-split: ratio = bfr_face / aft_face (STOCK_SPLIT = ratio > 1)
 *  HHKDB669101C0 bonus-issue: ratio = grts_rt / 100 (무상증자율%)
 */
@Component
class CandleAdjustmentJob(
    private val corpActionRepository: CorpActionRepository,
    private val candleAdjustmentRepository: CandleAdjustmentRepository,
    private val redisTemplate: StringRedisTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CandleAdjustmentJob::class.java)

    /**
     * Kafka 리스너 — corp_action.applied.v1 topic.
     * CorpActionSyncJob이 pending corp_action을 publish → 여기서 소비.
     */
    @KafkaListener(topics = [KafkaTopics.Data.CORP_ACTION_APPLIED], groupId = "candle-adjustment")
    fun onCorpAction(payload: String) {
        runCatching {
            val node = objectMapper.readTree(payload)
            val id = node.path("id").asLong(0L)
            val instrumentCode = node.path("instrumentCode").asText("").trim()
            val typeName = node.path("type").asText("").trim()
            val exDateStr = node.path("exDate").asText("").trim()
            val ratioStr = node.path("ratio").asText(null)

            if (instrumentCode.isEmpty() || typeName.isEmpty() || exDateStr.isEmpty()) {
                log.warn("[CandleAdj] 페이로드 파싱 실패 — payload=${payload.take(200)}")
                return
            }

            val type = runCatching { CorpActionType.valueOf(typeName) }.getOrNull() ?: return
            val exDate = runCatching { LocalDate.parse(exDateStr) }.getOrNull() ?: return
            val ratio = ratioStr?.toBigDecimalOrNull()

            val corpAction =
                CorpAction(
                    id = id,
                    instrumentCode = instrumentCode,
                    type = type,
                    exDate = exDate,
                    ratio = ratio,
                    source = "KIS_KSDINFO",
                )

            processAdjustment(corpAction)
        }.onFailure {
            log.error("[CandleAdj] Kafka 메시지 처리 실패 payload=${payload.take(200)} err=${it.message}", it)
        }
    }

    /**
     * 수정주가 소급 보정 (ADR 0018 §4-3).
     * @Transactional — candle UPDATE + corpAction markApplied 원자성 보장.
     */
    @Transactional
    fun processAdjustment(action: CorpAction) {
        val factor = computeFactor(action)
        if (factor == null) {
            log.info("[CandleAdj] ${action.type} 보정 불필요 — code=${action.instrumentCode}")
            corpActionRepository.markApplied(action.id, Instant.now())
            return
        }

        log.info(
            "[CandleAdj] 보정 시작 — code=${action.instrumentCode} type=${action.type} " +
                "ex_date=${action.exDate} factor=$factor",
        )

        val updated =
            candleAdjustmentRepository.adjustCandles(
                code = action.instrumentCode,
                exDate = action.exDate,
                priceFactor = factor,
            )

        // Redis 지표 캐시 무효화 (strategy-engine 재계산 트리거)
        val keys = redisTemplate.keys(RedisKeyRegistry.Indicator.resultPattern(action.instrumentCode))
        if (!keys.isNullOrEmpty()) {
            redisTemplate.delete(keys)
            log.debug("[CandleAdj] Redis 지표 캐시 삭제 — keys=${keys.size}건")
        }

        corpActionRepository.markApplied(action.id, Instant.now())

        log.info("[CandleAdj] 완료 — code=${action.instrumentCode} updated=$updated 행")
    }

    /**
     * corp_action type별 보정 계수 계산 (ADR 0018 §4-3 정합).
     * null = 가격 보정 불필요 (CASH_DIVIDEND, 기타).
     *
     * 액면분할 ratio=50 → factor = 1/50 = 0.02 (이전 가격을 50으로 나눔)
     * 액면병합 ratio=50 → factor = 50 (이전 가격에 50 곱함)
     * 무상증자 ratio=0.1 (10%) → factor = 1/(1+0.1) ≈ 0.909
     */
    private fun computeFactor(action: CorpAction): BigDecimal? {
        val ratio = action.ratio ?: return null
        return when (action.type) {
            CorpActionType.STOCK_SPLIT ->
                if (ratio > BigDecimal.ZERO) {
                    BigDecimal.ONE.divide(ratio, 8, RoundingMode.HALF_UP)
                } else {
                    null
                }
            CorpActionType.REVERSE_SPLIT ->
                if (ratio > BigDecimal.ZERO) ratio.setScale(8, RoundingMode.HALF_UP) else null
            CorpActionType.BONUS_ISSUE ->
                if (ratio > BigDecimal.ZERO) {
                    BigDecimal.ONE.divide(
                        BigDecimal.ONE.add(ratio),
                        8,
                        RoundingMode.HALF_UP,
                    )
                } else {
                    null
                }
            CorpActionType.CAPITAL_DECREASE ->
                // 자본감소: 비율만큼 주식수 감소 → 가격 보정 (무상소각 시)
                if (ratio > BigDecimal.ZERO) {
                    ratio.setScale(8, RoundingMode.HALF_UP)
                } else {
                    null
                }
            // 현금배당·주식배당: 배당락 가격 조정은 시장에서 자동 반영 → 소급 불필요
            CorpActionType.CASH_DIVIDEND,
            CorpActionType.STOCK_DIVIDEND,
            -> null
            // 합병·분할·유상증자: 보정 정책 post-MVP (ADR 0018 §4-4)
            else -> null
        }
    }
}
