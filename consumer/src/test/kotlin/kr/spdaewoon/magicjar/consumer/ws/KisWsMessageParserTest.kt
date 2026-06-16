package kr.spdaewoon.magicjar.consumer.ws

import kr.spdaewoon.magicjar.domain.market.port.SubscriptionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * KisWsMessageParser 단위 테스트.
 *
 * PR-9b smoke test 1 (dispatch §3-2-I 정합 — 룰 4: R2 PR 최소 smoke만).
 * ADR 0048 §5 검증: H0STCNT0 PIPE 구분자 파싱 정확성.
 */
class KisWsMessageParserTest {
    /**
     * PINGPONG 메시지 파싱 검증.
     */
    @Test
    fun `PINGPONG 메시지는 Pingpong ParsedMessage 반환`() {
        val result = KisWsMessageParser.parse("PINGPONG")
        assertThat(result).isInstanceOf(KisWsMessageParser.ParsedMessage.Pingpong::class.java)
    }

    /**
     * hotfix 2026-05-20 — KIS 첫 필드 `1` = AES 암호화 분기 검증.
     *
     * 사용자 dogfooding evidence: H0STCNI9 모의 체결통보가 `1|H0STCNI9|001|<base64>` 패턴으로 도달.
     * 이전 결함: `1`을 SubscriptionResponse로 잘못 분류 → KisExecutionWsListener forward 0.
     * fix: `1`도 RealtimeData (encrypted=true) → handleMessage에서 AES 복호화 후 listener forward.
     */
    @Test
    fun `hotfix — type=1 encrypted RealtimeData 분류 (H0STCNI9 체결통보)`() {
        val raw = "1|H0STCNI9|001|A2BZvg+Cor+sIhIcW7D4pniN4Z0SBvpfeTvpXlNyMm5FSOCbLbZ"
        val parsed = KisWsMessageParser.parse(raw)
        assertThat(parsed).isInstanceOf(KisWsMessageParser.ParsedMessage.RealtimeData::class.java)
        val data = parsed as KisWsMessageParser.ParsedMessage.RealtimeData
        assertThat(data.trId).isEqualTo("H0STCNI9")
        assertThat(data.encrypted).isTrue()
        assertThat(data.rawData).isEqualTo("A2BZvg+Cor+sIhIcW7D4pniN4Z0SBvpfeTvpXlNyMm5FSOCbLbZ")
    }

    @Test
    fun `hotfix — type=0 평문 RealtimeData encrypted=false (H0STCNT0 체결가)`() {
        val raw = "0|H0STCNT0|001|005930^090012^60900"
        val parsed = KisWsMessageParser.parse(raw)
        assertThat(parsed).isInstanceOf(KisWsMessageParser.ParsedMessage.RealtimeData::class.java)
        val data = parsed as KisWsMessageParser.ParsedMessage.RealtimeData
        assertThat(data.encrypted).isFalse()
    }

    @Test
    fun `H0STCNT0 raw 메시지 파싱 → TickEvent code·price·volume 정합`() {
        // H0STCNT0 45개 필드: IDX[0]=code, IDX[2]=price, IDX[12]=cntg_vol, IDX[33]=bsop_date
        // 총 45필드 생성 (인덱스 0~44)
        val fields =
            Array(45) { i ->
                when (i) {
                    0 -> "005930" // MKSC_SHRN_ISCD (종목코드)
                    1 -> "090012" // STCK_CNTG_HOUR (체결시각 HHMMSS)
                    2 -> "60900" // STCK_PRPR (현재가)
                    12 -> "100" // CNTG_VOL (체결거래량)
                    33 -> "20260507" // BSOP_DATE (영업일자 YYYYMMDD)
                    else -> "0"
                }
            }
        val rawData = fields.joinToString("^")
        val raw = "0|H0STCNT0|001|$rawData"

        val parsed = KisWsMessageParser.parse(raw)
        assertThat(parsed).isInstanceOf(KisWsMessageParser.ParsedMessage.RealtimeData::class.java)

        val data = parsed as KisWsMessageParser.ParsedMessage.RealtimeData
        assertThat(data.trId).isEqualTo("H0STCNT0")

        val tick = KisWsMessageParser.parseH0Stcnt0(data.rawData)
        assertThat(tick).isNotNull
        assertThat(tick!!.code).isEqualTo("005930")
        assertThat(tick.price).isEqualByComparingTo(BigDecimal("60900"))
        assertThat(tick.volume).isEqualTo(100L)
        assertThat(tick.source).isEqualTo("live")
    }

    /**
     * H0STCNT0 등락 필드 추출 — dogfooding 결함 1 fix.
     *
     * KIS-first 출처: open-trading-api domestic_stock_functions_ws.py ccnl_krx() cols 배열.
     * https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *   [3] PRDY_VRSS_SIGN — 부호 코드 (1=상한/2=상승/3=보합/4=하한/5=하락)
     *   [4] PRDY_VRSS      — 전일대비
     *   [5] PRDY_CTRT      — 전일대비율 (%)
     */
    @Test
    fun `H0STCNT0 등락 필드 PRDY_VRSS_SIGN·PRDY_VRSS·PRDY_CTRT 추출`() {
        val fields =
            Array(46) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "100012"
                    2 -> "61000"
                    3 -> "2" // PRDY_VRSS_SIGN (상승)
                    4 -> "1500" // PRDY_VRSS (전일대비 +1500)
                    5 -> "2.52" // PRDY_CTRT (전일대비율 +2.52%)
                    12 -> "100"
                    33 -> "20260511"
                    else -> "0"
                }
            }
        val tick = KisWsMessageParser.parseH0Stcnt0(fields.joinToString("^"))

        assertThat(tick).isNotNull
        assertThat(tick!!.changeSign).isEqualTo("2")
        assertThat(tick.priceChange).isEqualByComparingTo(BigDecimal("1500"))
        assertThat(tick.changeRate).isEqualByComparingTo(BigDecimal("2.52"))
    }

    /**
     * 등락 필드 공백·blank 시 null 반환 — polling source 호환 (등락 필드 미제공).
     */
    @Test
    fun `H0STCNT0 등락 필드 blank → null`() {
        val fields =
            Array(46) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "100012"
                    2 -> "61000"
                    3 -> "" // blank
                    4 -> "" // blank
                    5 -> "" // blank
                    12 -> "100"
                    33 -> "20260511"
                    else -> "0"
                }
            }
        val tick = KisWsMessageParser.parseH0Stcnt0(fields.joinToString("^"))

        assertThat(tick).isNotNull
        assertThat(tick!!.changeSign).isNull()
        assertThat(tick.priceChange).isNull()
        assertThat(tick.changeRate).isNull()
    }

    /**
     * 구독 성공 응답 JSON 파싱 검증.
     */
    @Test
    fun `구독 성공 응답 JSON 파싱 rt_cd 0 → true`() {
        val json = """{"header":{"tr_id":"H0STCNT0"},"body":{"rt_cd":"0","msg1":"SUBSCRIBE SUCCESS"}}"""
        // 구독 응답은 PIPE 없는 JSON
        val parsed = KisWsMessageParser.parse(json)
        assertThat(parsed).isInstanceOf(KisWsMessageParser.ParsedMessage.SubscriptionResponse::class.java)
    }

    // ----- 결함③: OPSP8997 invalid tr_type 감지 (진단 관측) -----

    @Test
    fun `OPSP8997 msg_cd → isOpsp8997InvalidTrType true`() {
        val json = """{"header":{"tr_id":null},"body":{"rt_cd":"1","msg_cd":"OPSP8997","msg1":"invalid tr_type"}}"""
        assertThat(KisWsMessageParser.isOpsp8997InvalidTrType(json)).isTrue()
        // 실패 응답이므로 success=false (진단 분기 진입 전제).
        assertThat(KisWsMessageParser.isSubscriptionSuccess(json)).isFalse()
    }

    @Test
    fun `msg1 invalid tr_type 문구만 있어도 대소문자 무시 감지`() {
        val json = """{"body":{"rt_cd":"1","msg1":"Invalid TR_TYPE"}}"""
        assertThat(KisWsMessageParser.isOpsp8997InvalidTrType(json)).isTrue()
    }

    @Test
    fun `정상 SUCCESS·OPSP8996·OPSP0008 응답은 OPSP8997로 오탐하지 않는다`() {
        val success = """{"body":{"rt_cd":"0","msg_cd":"OPSP0000","msg1":"SUBSCRIBE SUCCESS"}}"""
        val opsp8996 = """{"body":{"rt_cd":"1","msg_cd":"OPSP8996","msg1":"ALREADY IN USE appkey"}}"""
        val opsp0008 = """{"body":{"rt_cd":"1","msg_cd":"OPSP0008","msg1":"MAX SUBSCRIBE OVER"}}"""
        assertThat(KisWsMessageParser.isOpsp8997InvalidTrType(success)).isFalse()
        assertThat(KisWsMessageParser.isOpsp8997InvalidTrType(opsp8996)).isFalse()
        assertThat(KisWsMessageParser.isOpsp8997InvalidTrType(opsp0008)).isFalse()
    }

    /**
     * KIS WS 슬롯 한도 — ADR 0123 §1-3 KIS-first 정정 (41→40).
     *
     * KIS 공식 예제 kis_auth.py __runner()가 40을 hard enforce:
     *   `if len(open_map.keys()) > 40: raise ValueError("Subscription's max is 40")`
     * 따라서 SubscriptionPolicy.TOTAL_SLOTS = 40이어야 한다.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     */
    @Test
    fun `TOTAL_SLOTS 40 — ADR 0123 KIS-first 정정 (41에서 40)`() {
        assertThat(SubscriptionPolicy.TOTAL_SLOTS)
            .isEqualTo(40)
            .describedAs("ADR 0123 §1-3: KIS kis_auth.py __runner() enforce 값 40 정합")
    }

    // ===== H0UNCNT0 통합 체결가 테스트 (ADR 0124 단일 채널) =====

    /**
     * H0UNCNT0 통합 체결가 파싱 — ccnl_total.py columns는 ccnl_krx 핵심 인덱스와 동일하다.
     */
    @Test
    fun `H0UNCNT0 raw 메시지 파싱 → TickEvent source=live-unified 정합`() {
        val fields =
            Array(46) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "100012"
                    2 -> "61200"
                    3 -> "2"
                    4 -> "1200"
                    5 -> "2.00"
                    12 -> "44"
                    14 -> "7000000000"
                    18 -> "123.45"
                    33 -> "20260612"
                    else -> "0"
                }
            }
        val raw = "0|H0UNCNT0|001|${fields.joinToString("^")}"

        val parsed = KisWsMessageParser.parse(raw)
        assertThat(parsed).isInstanceOf(KisWsMessageParser.ParsedMessage.RealtimeData::class.java)
        val data = parsed as KisWsMessageParser.ParsedMessage.RealtimeData
        assertThat(data.trId).isEqualTo("H0UNCNT0")

        val tick = KisWsMessageParser.parseH0Uncnt0(data.rawData)
        assertThat(tick).isNotNull
        assertThat(tick!!.code).isEqualTo("005930")
        assertThat(tick.price).isEqualByComparingTo(BigDecimal("61200"))
        assertThat(tick.volume).isEqualTo(44L)
        assertThat(tick.changeRate).isEqualByComparingTo(BigDecimal("2.00"))
        assertThat(tick.tradeIntensity).isEqualByComparingTo(BigDecimal("123.45"))
        assertThat(tick.turnoverAccum).isEqualByComparingTo(BigDecimal("7000000000"))
        assertThat(tick.source).isEqualTo("live-unified")
    }

    /**
     * 40 slot 한도 — H0UNCNT0 단일 체결가 채널 상수 검증 (ADR 0124).
     *
     * ADR 0123 §1-3 + ADR 0124 정합:
     *   H0UNCNT0 종목당 1 slot. H0STCNI0(1) + H0UNCNT0(PRICE_TICK_MAX=30) = 31 ≤ 40 안전.
     */
    @Test
    fun `40 slot 한도에서 H0UNCNT0 단일 체결가 + 체결통보는 한도 이내`() {
        val totalSlots = SubscriptionPolicy.TOTAL_SLOTS
        val executionNoticeReserved = SubscriptionPolicy.EXECUTION_NOTICE_RESERVED
        val priceTickMax = SubscriptionPolicy.PRICE_TICK_MAX
        val usedSlots = executionNoticeReserved + priceTickMax

        assertThat(totalSlots).isEqualTo(40)
        assertThat(usedSlots)
            .isLessThanOrEqualTo(totalSlots)
            .describedAs("ADR 0124: H0STCNI0(1) + H0UNCNT0(30)은 40 slot 한도 이내")
    }

    // ===== H0STOUP0 시간외 단일가 구독 테스트 (ADR 0076 Phase 3 B-2) =====

    /**
     * H0STOUP0 raw 메시지 파싱 → TickEvent code·price·volume·source=live-overtime 정합.
     *
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     *   overtime_ccnl_krx() tr_id="H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
     *   국내주식 시간외 단일가(16:00~18:00) 시간대에 실시간체결가 데이터 확인 가능합니다.
     *   columns 배열 43개 필드: [0]=mksc_shrn_iscd / [1]=stck_cntg_hour / [2]=stck_prpr
     *   [12]=cntg_vol / [33]=bsop_date
     *
     * ADR 0076 Phase 3 B-2: H0STOUP0 활성 시간 16:00~18:00 KST.
     */
    @Test
    fun `H0STOUP0 raw 메시지 파싱 → TickEvent code·price·volume·source=live-overtime 정합`() {
        // H0STOUP0 43개 필드: [0]=code [1]=time [2]=price [12]=cntg_vol [33]=bsop_date
        // KIS-first 필드 인덱스:
        //   overtime_ccnl_krx.py columns: mksc_shrn_iscd[0] / stck_cntg_hour[1] / stck_prpr[2]
        //   cntg_vol[12] / bsop_date[33]
        // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
        val fields =
            Array(43) { i ->
                when (i) {
                    0 -> "005930" // mksc_shrn_iscd (종목코드)
                    1 -> "161500" // stck_cntg_hour (시간외 16:15)
                    2 -> "59500" // stck_prpr (현재가)
                    3 -> "5" // prdy_vrss_sign (하락)
                    4 -> "800" // prdy_vrss (전일대비 -800)
                    5 -> "-1.33" // prdy_ctrt (전일대비율)
                    12 -> "200" // cntg_vol (체결거래량)
                    33 -> "20260515" // bsop_date (영업일자)
                    else -> "0"
                }
            }
        val rawData = fields.joinToString("^")
        val raw = "0|H0STOUP0|001|$rawData"

        val parsed = KisWsMessageParser.parse(raw)
        assertThat(parsed).isInstanceOf(KisWsMessageParser.ParsedMessage.RealtimeData::class.java)

        val data = parsed as KisWsMessageParser.ParsedMessage.RealtimeData
        assertThat(data.trId).isEqualTo("H0STOUP0")

        val tick = KisWsMessageParser.parseH0Stoup0(data.rawData)
        assertThat(tick).isNotNull
        assertThat(tick!!.code).isEqualTo("005930")
        assertThat(tick.price).isEqualByComparingTo(BigDecimal("59500"))
        assertThat(tick.volume).isEqualTo(200L)
        assertThat(tick.source).isEqualTo("live-overtime")
    }

    /**
     * H0STOUP0 등락 필드 추출 — 시간외 단일가 등락률 정합.
     *
     * KIS-first 출처:
     *   overtime_ccnl_krx.py columns [3]=prdy_vrss_sign / [4]=prdy_vrss / [5]=prdy_ctrt
     *   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     */
    @Test
    fun `H0STOUP0 등락 필드 prdy_vrss_sign·prdy_vrss·prdy_ctrt 추출`() {
        val fields =
            Array(43) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "163000"
                    2 -> "60200"
                    3 -> "2" // prdy_vrss_sign (상승)
                    4 -> "200" // prdy_vrss (+200)
                    5 -> "0.33" // prdy_ctrt (+0.33%)
                    12 -> "50"
                    33 -> "20260515"
                    else -> "0"
                }
            }
        val tick = KisWsMessageParser.parseH0Stoup0(fields.joinToString("^"))

        assertThat(tick).isNotNull
        assertThat(tick!!.changeSign).isEqualTo("2")
        assertThat(tick.priceChange).isEqualByComparingTo(BigDecimal("200"))
        assertThat(tick.changeRate).isEqualByComparingTo(BigDecimal("0.33"))
        assertThat(tick.source).isEqualTo("live-overtime")
    }

    /**
     * 40 slot 한도 — H0STOUP0 시간 분리 slot 예산 검증 (ADR 0076 Phase 3 B-2 + ADR 0124).
     *
     * 시간외 단일가(16:00~18:00 KST):
     *   H0STOUP0(OVERTIME_SINGLE_PRICE_MAX=20) + H0STCNI0(1) = 21 ≤ 40 안전.
     * 정규장(09:00~15:30 KST):
     *   H0UNCNT0(PRICE_TICK_MAX=30) + H0STCNI0(1) = 31 ≤ 40 안전 (단일 체결가 채널).
     *
     * KIS-first: overtime_ccnl_krx.py 16:00~18:00 활성 시간 명시.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     */
    @Test
    fun `40 slot H0STOUP0 시간 분리 정합 — 시간외 21 slot 안전, 정규장 H0UNCNT0 31 slot 안전`() {
        val totalSlots = SubscriptionPolicy.TOTAL_SLOTS
        val executionNoticeReserved = SubscriptionPolicy.EXECUTION_NOTICE_RESERVED

        // 시간외 단일가 시간대 (16:00~18:00 KST) — H0STOUP0만 활성
        val overtimeSinglePriceMax = SubscriptionPolicy.OVERTIME_SINGLE_PRICE_MAX
        val overtimeUsedSlots = overtimeSinglePriceMax + executionNoticeReserved // 21
        assertThat(overtimeUsedSlots)
            .isLessThanOrEqualTo(totalSlots)
            .describedAs("시간외 단일가: H0STOUP0(20) + H0STCNI0(1) = 21 ≤ 40")

        // 정규장 시간대 (09:00~15:30 KST) — H0UNCNT0 단일 체결가 활성, H0STOUP0 = 0
        val regularUsedSlots = SubscriptionPolicy.PRICE_TICK_MAX + executionNoticeReserved // 31
        assertThat(regularUsedSlots)
            .isLessThanOrEqualTo(totalSlots)
            .describedAs("정규장 단일 체결가: H0UNCNT0(30) + H0STCNI0(1) = 31 ≤ 40")
    }

    // ===== R1-A 체결강도 흡수 테스트 (ADR 0117 §3-R1) =====

    /**
     * H0STCNT0 체결강도(CTTR)·누적거래대금(ACML_TR_PBMN) 흡수 — KIS 계산 완료값.
     *
     * KIS-first raw 출처(2026-06-09 fetch 재검증):
     *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *   ccnl_krx() columns: [14]=ACML_TR_PBMN(누적거래대금) / [18]=CTTR(체결강도)
     */
    @Test
    fun `H0STCNT0 체결강도 CTTR·누적거래대금 ACML_TR_PBMN 흡수`() {
        val fields =
            Array(46) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "100012"
                    2 -> "61000"
                    12 -> "100"
                    14 -> "1234567890000" // ACML_TR_PBMN (누적거래대금 원)
                    18 -> "152.34" // CTTR (체결강도 — 매수우위)
                    33 -> "20260609"
                    else -> "0"
                }
            }
        val tick = KisWsMessageParser.parseH0Stcnt0(fields.joinToString("^"))

        assertThat(tick).isNotNull
        assertThat(tick!!.tradeIntensity).isEqualByComparingTo(BigDecimal("152.34"))
        assertThat(tick.turnoverAccum).isEqualByComparingTo(BigDecimal("1234567890000"))
        // 기존 필드 회귀 0 — 가격·체결량 그대로
        assertThat(tick.price).isEqualByComparingTo(BigDecimal("61000"))
        assertThat(tick.volume).isEqualTo(100L)
    }

    /**
     * H0STCNT0 체결강도·누적거래대금 blank → null graceful (polling source 호환).
     */
    @Test
    fun `H0STCNT0 체결강도·누적거래대금 blank → null`() {
        val fields =
            Array(46) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "100012"
                    2 -> "61000"
                    12 -> "100"
                    14 -> "" // blank
                    18 -> "" // blank
                    33 -> "20260609"
                    else -> "0"
                }
            }
        val tick = KisWsMessageParser.parseH0Stcnt0(fields.joinToString("^"))

        assertThat(tick).isNotNull
        assertThat(tick!!.tradeIntensity).isNull()
        assertThat(tick.turnoverAccum).isNull()
        // 기존 필드 회귀 0
        assertThat(tick.price).isEqualByComparingTo(BigDecimal("61000"))
        assertThat(tick.volume).isEqualTo(100L)
    }

    /**
     * 필드 게이트 완화(< 21) graceful — index 18·14는 추출, 결손된 BSOP_DATE(33)·CTTR는 null.
     *
     * 게이트 21~33 구간: CTTR(18) 추출 보장 + BSOP_DATE(33) 결손 시 getOrNull null → Clock.now() fallback.
     * 전체 tick reject 금지 (하위호환 — 기존 < 34 게이트는 체결강도 흡수에 과도).
     */
    @Test
    fun `H0STCNT0 필드 21~33개 → CTTR 추출 + BSOP_DATE 결손 graceful (전체 reject 금지)`() {
        // 25개 필드만 — index 18(CTTR)·14(ACML_TR_PBMN)는 존재, 33(BSOP_DATE)는 결손
        val fields =
            Array(25) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "100012"
                    2 -> "61000"
                    12 -> "100"
                    14 -> "9999999999" // ACML_TR_PBMN
                    18 -> "88.5" // CTTR (매도우위)
                    else -> "0"
                }
            }
        val tick = KisWsMessageParser.parseH0Stcnt0(fields.joinToString("^"))

        // 전체 reject 금지 — tick 생성됨
        assertThat(tick).isNotNull
        assertThat(tick!!.tradeIntensity).isEqualByComparingTo(BigDecimal("88.5"))
        assertThat(tick.turnoverAccum).isEqualByComparingTo(BigDecimal("9999999999"))
        assertThat(tick.code).isEqualTo("005930")
        assertThat(tick.price).isEqualByComparingTo(BigDecimal("61000"))
    }

    /**
     * 필드 21개 미만 → null reject (게이트 하한 < 21 검증).
     */
    @Test
    fun `H0STCNT0 필드 21개 미만 → null reject`() {
        val fields = Array(20) { i -> if (i == 0) "005930" else "0" }
        val tick = KisWsMessageParser.parseH0Stcnt0(fields.joinToString("^"))
        assertThat(tick).isNull()
    }

    /**
     * H0UNCNT0 체결강도 흡수 — parseH0Stcnt0 base parser 재사용 → 동일 index 14·18 흡수 + source=live-unified.
     */
    @Test
    fun `H0UNCNT0 체결강도 CTTR 흡수 + source=live-unified`() {
        val fields =
            Array(44) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "100012"
                    2 -> "60200"
                    12 -> "30"
                    14 -> "5000000000"
                    18 -> "110.0"
                    33 -> "20260609"
                    else -> "0"
                }
            }
        val tick = KisWsMessageParser.parseH0Uncnt0(fields.joinToString("^"))

        assertThat(tick).isNotNull
        assertThat(tick!!.tradeIntensity).isEqualByComparingTo(BigDecimal("110.0"))
        assertThat(tick.turnoverAccum).isEqualByComparingTo(BigDecimal("5000000000"))
        assertThat(tick.source).isEqualTo("live-unified")
    }

    @Test
    fun `H0UNASP0 raw 메시지 파싱 → 호가 10단계와 예상체결 필드 정합`() {
        val fields =
            Array(65) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "090012"
                    3 -> "69900"
                    4 -> "70000"
                    13 -> "69800"
                    14 -> "69700"
                    23 -> "1100"
                    24 -> "1200"
                    33 -> "2100"
                    34 -> "2200"
                    43 -> "12345"
                    44 -> "23456"
                    45 -> "345"
                    46 -> "456"
                    47 -> "69950"
                    48 -> "70"
                    49 -> "3000"
                    50 -> "150"
                    51 -> "2"
                    52 -> "0.22"
                    53 -> "100000"
                    58 -> "A"
                    else -> "0"
                }
            }
        val raw = "0|H0UNASP0|001|${fields.joinToString("^")}"
        val parsed = KisWsMessageParser.parse(raw)

        assertThat(parsed).isInstanceOf(KisWsMessageParser.ParsedMessage.RealtimeData::class.java)
        val data = parsed as KisWsMessageParser.ParsedMessage.RealtimeData
        assertThat(data.trId).isEqualTo("H0UNASP0")

        val snapshot = KisWsOrderbookParser.parseH0Unasp0(data.rawData)
        assertThat(snapshot).isNotNull
        assertThat(snapshot!!.asks.first().price).isEqualByComparingTo(BigDecimal("69900"))
        assertThat(snapshot.asks.first().quantity).isEqualTo(1100L)
        assertThat(snapshot.bids.first().price).isEqualByComparingTo(BigDecimal("69800"))
        assertThat(snapshot.bids.first().quantity).isEqualTo(2100L)
        assertThat(snapshot.totalAskQuantity).isEqualTo(12345L)
        assertThat(snapshot.totalBidQuantity).isEqualTo(23456L)
        assertThat(snapshot.overtimeTotalAskQuantity).isEqualTo(345L)
        assertThat(snapshot.overtimeTotalBidQuantity).isEqualTo(456L)
        assertThat(snapshot.anticipatedPrice).isEqualByComparingTo(BigDecimal("69950"))
        assertThat(snapshot.anticipatedQuantity).isEqualTo(70L)
        assertThat(snapshot.anticipatedVolume).isEqualTo(3000L)
        assertThat(snapshot.anticipatedChange).isEqualByComparingTo(BigDecimal("150"))
        assertThat(snapshot.anticipatedChangeSign).isEqualTo("2")
        assertThat(snapshot.anticipatedChangeRate).isEqualByComparingTo(BigDecimal("0.22"))
        assertThat(snapshot.accumulatedVolume).isEqualTo(100000L)
        assertThat(snapshot.stockDealClassCode).isEqualTo("A")
    }

    // ===== ADR 0123 R1 — OPSP0008 MAX SUBSCRIBE OVER 거부 파싱 =====

    /**
     * OPSP0008 거부 응답 감지 — 2026-06-11 후성(093370) 라이브 실측 evidence (ADR 0123 §1-1).
     */
    @Test
    fun `OPSP0008 MAX SUBSCRIBE OVER 거부 감지`() {
        val json =
            """{"header":{"tr_id":"H0UNASP0","tr_key":"093370"},""" +
                """"body":{"rt_cd":"1","msg_cd":"OPSP0008","msg1":"MAX SUBSCRIBE OVER"}}"""
        assertThat(KisWsMessageParser.isOpsp0008(json)).isTrue()
        assertThat(KisWsMessageParser.isSubscriptionSuccess(json)).isFalse()
        // OPSP8996(ALREADY IN USE)과 혼동 금지 — 별개 코드.
        assertThat(KisWsMessageParser.isOpsp8996(json)).isFalse()
    }

    /**
     * 성공 응답·OPSP8996은 OPSP0008로 오탐하지 않음.
     */
    @Test
    fun `OPSP0008 오탐 금지 — 성공·OPSP8996 응답`() {
        val success = """{"header":{"tr_id":"H0STCNT0"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","msg1":"SUBSCRIBE SUCCESS"}}"""
        val already = """{"header":{"tr_id":"H0STCNT0"},"body":{"rt_cd":"1","msg_cd":"OPSP8996","msg1":"ALREADY IN USE appkey"}}"""
        assertThat(KisWsMessageParser.isOpsp0008(success)).isFalse()
        assertThat(KisWsMessageParser.isOpsp0008(already)).isFalse()
    }

    /**
     * 거부 응답 header에서 (trId, trKey) 채널 추출 — ledger 회계 보정 식별자.
     */
    @Test
    fun `OPSP0008 거부 응답에서 trId·trKey 채널 추출`() {
        val json =
            """{"header":{"tr_id":"H0UNCNT0","tr_key":"005930"},""" +
                """"body":{"rt_cd":"1","msg_cd":"OPSP0008","msg1":"MAX SUBSCRIBE OVER"}}"""
        val channel = KisWsMessageParser.extractRejectedChannel(json)
        assertThat(channel).isNotNull
        assertThat(channel!!.first).isEqualTo("H0UNCNT0")
        assertThat(channel.second).isEqualTo("005930")
    }

    /**
     * tr_key 누락 시 추출 null — graceful (호출자 회계 보정 skip).
     */
    @Test
    fun `OPSP0008 거부 응답 trKey 누락 시 null`() {
        val json = """{"header":{"tr_id":"H0STCNT0"},"body":{"rt_cd":"1","msg_cd":"OPSP0008"}}"""
        assertThat(KisWsMessageParser.extractRejectedChannel(json)).isNull()
    }
}
