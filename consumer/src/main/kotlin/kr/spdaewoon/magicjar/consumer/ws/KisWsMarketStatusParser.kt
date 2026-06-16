package kr.spdaewoon.magicjar.consumer.ws

import kotlinx.datetime.Clock
import org.slf4j.LoggerFactory

/**
 * KIS WebSocket H0UNMKO0 통합 장운영정보/VI 메시지 파서.
 *
 * KIS-first 출처: open-trading-api `domestic_stock_functions_ws.py` `market_status_total()`
 * - tr_id = H0UNMKO0
 * - tr_type "1" 구독 / "2" 해제
 * - 공식 컬럼표: TRHT_YN, TR_SUSP_REAS_CNTT, MKOP_CLS_CODE, ANTC_MKOP_CLS_CODE,
 *   MRKT_TRTM_CLS_CODE, DIVI_APP_CLS_CODE, ISCD_STAT_CLS_CODE, VI_CLS_CODE,
 *   OVTM_VI_CLS_CODE, EXCH_CLS_CODE
 *
 * 주의: 공식 total 컬럼표에는 종목코드가 빠져 있다. 다종목 구독 환경에서 종목코드가 없는 payload는
 * 어느 보유분의 VI인지 확정할 수 없으므로 자동청산/알림 이벤트로 넘기지 않는다. 단, caller가 단일 구독
 * 코드만 [fallbackCode]로 넘긴 경우에는 그 코드로 귀속한다.
 */
object KisWsMarketStatusParser {
    private val log = LoggerFactory.getLogger(KisWsMarketStatusParser::class.java)

    private val inactiveViCodes = setOf("", "0", "00", "N")

    fun parseH0Unmko0(
        rawData: String,
        fallbackCode: String? = null,
    ): MarketStatusEvent? {
        return try {
            val fields = rawData.split("^")
            if (fields.size < MIN_FIELDS_OFFICIAL) {
                log.warn("H0UNMKO0 필드 수 부족: expected >={} got={}", MIN_FIELDS_OFFICIAL, fields.size)
                return null
            }

            val firstField = fields[0].trim()
            val hasCodeInPayload = CODE_REGEX.matches(firstField)
            val code =
                when {
                    hasCodeInPayload -> firstField
                    fallbackCode != null && CODE_REGEX.matches(fallbackCode) -> fallbackCode
                    else -> {
                        log.warn(
                            "H0UNMKO0 종목코드 없는 payload skip — 다종목 구독에서 귀속 불가: firstField={} fields={}",
                            firstField,
                            fields.size,
                        )
                        return null
                    }
                }
            val offset = if (hasCodeInPayload) 1 else 0
            if (fields.size < offset + MIN_FIELDS_OFFICIAL) {
                log.warn(
                    "H0UNMKO0 필드 수 부족: codeField={} expected >={} got={}",
                    hasCodeInPayload,
                    offset + MIN_FIELDS_OFFICIAL,
                    fields.size,
                )
                return null
            }

            val viClsCode = fields.textAt(offset + 7)
            val overtimeViClsCode = fields.textAt(offset + 8)
            MarketStatusEvent(
                code = code,
                tradeHaltYn = fields.textAt(offset),
                tradeSuspensionReason = fields.textAt(offset + 1),
                marketOpenClassCode = fields.textAt(offset + 2),
                anticipatedMarketOpenClassCode = fields.textAt(offset + 3),
                marketTreatmentClassCode = fields.textAt(offset + 4),
                divisionApplyClassCode = fields.textAt(offset + 5),
                instrumentStatusClassCode = fields.textAt(offset + 6),
                viClassCode = viClsCode,
                overtimeViClassCode = overtimeViClsCode,
                exchangeClassCode = fields.textAt(offset + 9),
                isViActive = viClsCode.isActiveViCode() || overtimeViClsCode.isActiveViCode(),
                rawFieldCount = fields.size,
            )
        } catch (e: Exception) {
            log.error("H0UNMKO0 파싱 실패: rawData={} error={}", rawData.take(100), e.message)
            null
        }
    }

    private fun String?.isActiveViCode(): Boolean = !inactiveViCodes.contains(this?.trim()?.uppercase() ?: "")

    private fun List<String>.textAt(index: Int): String? = getOrNull(index)?.trim()?.ifBlank { null }

    private const val MIN_FIELDS_OFFICIAL = 10
    private val CODE_REGEX = Regex("^\\d{6}$")
}

data class MarketStatusEvent(
    val code: String,
    val tradeHaltYn: String?,
    val tradeSuspensionReason: String?,
    val marketOpenClassCode: String?,
    val anticipatedMarketOpenClassCode: String?,
    val marketTreatmentClassCode: String?,
    val divisionApplyClassCode: String?,
    val instrumentStatusClassCode: String?,
    val viClassCode: String?,
    val overtimeViClassCode: String?,
    val exchangeClassCode: String?,
    val isViActive: Boolean,
    val rawFieldCount: Int,
    val ts: kotlinx.datetime.Instant = Clock.System.now(),
)
