package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * ★ NXT 실측 PROBE harness (ADR 0118 R1 결재 ③ — 추론 금지·헌법 §3 할루시네이션 가드).
 *
 * 목적: 멀티시세 `intstock-multprice`(FHKST11300006)가 시장구분 입력 `J`(KRX) vs `NX`(NXT)에 따라
 *   실제로 **무슨 값**(정규장 종가 vs NXT 통합 종가/거래량)을 반환하는지 **raw 응답으로 실측**한다.
 *   결과에 따라 R2의 today 종가 source(NXT 통합 직접 vs DailyCloseFinalizer 폴백 — §2-D)가 결정된다.
 *
 * ★ PENDING 사유 (2026-06-09 R1 작성 시점):
 *   - 실 KIS 호출에는 REAL credential(DB AES 암호화) 복호화 + 발급 토큰이 필요하다.
 *   - 토큰·시크릿 수동 추출 경로는 샌드박스 보안 가드로 차단되었고, 그것이 옳다(시크릿 보호).
 *   - 따라서 본 harness는 **다음 장 시간(KST 08:00~20:00 영업일)에 ENV 주입으로 실행**하는 PENDING 검증으로 남긴다.
 *   - ENV 미주입 시 @EnabledIfEnvironmentVariable로 **skip**된다(절대 추측 단정 0 — NXT 통합 여부 미결).
 *
 * 실행 방법 (장 시간·영업일):
 *   1) REAL credential로 KIS 토큰 발급 (앱 정상 경로 — 예: 앱 부팅 후 Redis `kis:oauth:token:user:{userId}:account:REAL`).
 *   2) 아래 ENV 주입:
 *        MJ_NXT_PROBE_APPKEY=<KIS appkey>
 *        MJ_NXT_PROBE_APPSECRET=<KIS appsecret>
 *        MJ_NXT_PROBE_TOKEN=<발급된 access token (Bearer 제외)>
 *        MJ_NXT_PROBE_CODES=005930,000660  (선택 — default 삼성전자·SK하이닉스)
 *   3) ./gradlew :consumer:test --tests "*KisMultiPriceNxtProbeIT" --info
 *   4) stdout의 [NXT-PROBE] J / NX 라인 raw 값을 비교해 ADR 0118 §2-D에 반영:
 *        - J 와 NX 의 inter2_prpr(종가)·acml_vol(거래량)이 다르면 → NXT가 통합값을 별도 반환 (NXT 직접 채택 가능)
 *        - J 와 NX 가 동일하거나 NX 가 빈 응답이면 → 멀티시세는 정규장만 → DailyCloseFinalizer 폴백 (§2-D LOCK)
 *
 * KIS-first raw spec:
 *   tr_id=FHKST11300006, path=/uapi/domestic-stock/v1/quotations/intstock-multprice
 *   param=FID_COND_MRKT_DIV_CODE_1(J|NX) + FID_INPUT_ISCD_1
 *   raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/intstock_multprice.py
 *
 * ⚠ 시크릿 가드: 본 테스트는 ENV로 주입된 키를 stdout에 출력하지 않는다(응답 시세 값만 출력).
 */
class KisMultiPriceNxtProbeIT {
    companion object {
        private const val TR_ID = "FHKST11300006"
        private const val BASE = "https://openapi.koreainvestment.com:9443"
        private const val ENDPOINT = "/uapi/domestic-stock/v1/quotations/intstock-multprice"

        // raw 응답 비교 대상 필드 (NXT 통합 vs 정규장 분간 핵심 — inter2_prpr 종가 + acml_vol 거래량)
        private val OBSERVED_FIELDS =
            listOf(
                "inter_shrn_iscd",
                "inter2_oprc",
                "inter2_hgpr",
                "inter2_lwpr",
                "inter2_prpr",
                "inter2_prdy_clpr",
                "acml_vol",
                "acml_tr_pbmn",
                "kospi_kosdaq_cls_name",
                "mrkt_trtm_cls_name",
                "hour_cls_code",
            )
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MJ_NXT_PROBE_APPKEY", matches = ".+")
    fun `NXT-probe - J vs NX 멀티시세 raw 응답 실측 비교`() {
        val appKey = System.getenv("MJ_NXT_PROBE_APPKEY")
        val appSecret = System.getenv("MJ_NXT_PROBE_APPSECRET") ?: error("MJ_NXT_PROBE_APPSECRET 필요")
        val token = System.getenv("MJ_NXT_PROBE_TOKEN") ?: error("MJ_NXT_PROBE_TOKEN 필요")
        val codes = (System.getenv("MJ_NXT_PROBE_CODES") ?: "005930,000660").split(",").map { it.trim() }

        val webClient = WebClient.builder().build()

        for (mrktDivCode in listOf("J", "NX")) {
            val rows = call(webClient, appKey, appSecret, token, codes, mrktDivCode)
            if (rows.isEmpty()) {
                println("[NXT-PROBE] mrktDivCode=$mrktDivCode → 응답 0건 (NXT 미지원 가능성 evidence)")
                continue
            }
            for (row in rows) {
                val rendered =
                    OBSERVED_FIELDS.joinToString(" | ") { f -> "$f=${row[f]?.toString()?.trim() ?: "null"}" }
                println("[NXT-PROBE] mrktDivCode=$mrktDivCode :: $rendered")
            }
        }
        println(
            "[NXT-PROBE] 비교 가이드: J 와 NX 의 inter2_prpr·acml_vol 이 다르면 NXT 통합 직접 채택 가능, " +
                "동일하거나 NX 빈 응답이면 §2-D DailyCloseFinalizer 폴백 LOCK.",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun call(
        webClient: WebClient,
        appKey: String,
        appSecret: String,
        token: String,
        codes: List<String>,
        mrktDivCode: String,
    ): List<Map<String, Any?>> {
        val body =
            webClient
                .get()
                .uri { builder ->
                    val b =
                        builder
                            .scheme("https")
                            .host(BASE.removePrefix("https://").substringBefore(":"))
                            .port(9443)
                            .path(ENDPOINT)
                    codes.take(30).forEachIndexed { idx, code ->
                        val n = idx + 1
                        b.queryParam("FID_COND_MRKT_DIV_CODE_$n", mrktDivCode)
                        b.queryParam("FID_INPUT_ISCD_$n", code)
                    }
                    b.build()
                }.header("authorization", "Bearer $token")
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", TR_ID)
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(Duration.ofSeconds(10))
                .block() as? Map<String, Any?> ?: return emptyList()

        val rtCd = body["rt_cd"]?.toString()
        if (rtCd != "0") {
            println("[NXT-PROBE] mrktDivCode=$mrktDivCode rt_cd=$rtCd msg=${body["msg1"]}")
            return emptyList()
        }
        return (body["output"] as? List<Map<String, Any?>>) ?: emptyList()
    }
}
