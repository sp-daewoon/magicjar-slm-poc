package kr.spdaewoon.magicjar.domain.market.index

/**
 * KIS 지수·섹터 코드 권위 enum (ADR 0100 §2-3 LOCK).
 *
 * ### 범주
 * - DOMESTIC_INDEX  — KOSPI(0001) · KOSDAQ(1001) · KOSPI200(2001)
 * - DOMESTIC_SECTOR — KRX 21 업종 (KIS FID_INPUT_ISCD raw)
 * - OVERSEAS_INDEX  — NASDAQ(.IXIC) · S&P 500(.SPX) — EXCD=NAS 단일 1차 (ADR 0100 §3-4)
 *
 * ### raw 출처 (헌법 §3 KIS-first)
 * - 국내 지수 코드: https://apiportal.koreainvestment.com/intro (KIS doc portal — 0001/1001/2001 정합)
 * - KRX 21 업종 코드: https://github.com/koreainvestment/open-trading-api (FHPUP02100000·FHPUP02140000 example)
 * - 해외 EXCD 매트릭스: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/price/price.py
 *
 * ### 자체 추론 0 LOCK (ADR 0100 §3-5)
 * 본 enum의 모든 raw code는 KIS 공식 출처 + KIS open-trading-api 레포 raw fetch 인용 정합.
 * 향후 확장(다우 .DJI · NYSE 등) 시 추가 raw fetch + ADR amend 의무.
 */
enum class IndexCategory {
    DOMESTIC_INDEX,
    DOMESTIC_SECTOR,
    OVERSEAS_INDEX,
}

/**
 * 코드 분류 헬퍼 — DB market 컬럼 직접 매핑 (V47 market VARCHAR(16)).
 *
 * @property code KIS FID_INPUT_ISCD 또는 SYMB raw 값 (예: "0001", ".IXIC")
 * @property displayName 사용자 노출 한국어 이름
 * @property category 분류 (snapshot 영속·STOMP topic 분기 권위)
 * @property exchange 해외 한정 EXCD (예: "NAS"). 국내는 null
 */
data class IndexCode(
    val code: String,
    val displayName: String,
    val category: IndexCategory,
    val exchange: String? = null,
) {
    init {
        require(code.isNotBlank()) { "지수 코드는 빈 문자열 금지" }
        require(displayName.isNotBlank()) { "지수 한국어 이름 빈 문자열 금지" }
        if (category == IndexCategory.OVERSEAS_INDEX) {
            require(!exchange.isNullOrBlank()) { "해외 지수는 EXCD 필수 (예: NAS)" }
        }
    }

    companion object {
        // ── 국내 지수 3건 (FHPUP02100000 FID_INPUT_ISCD raw) ─────────────────────
        val KOSPI = IndexCode("0001", "코스피", IndexCategory.DOMESTIC_INDEX)
        val KOSDAQ = IndexCode("1001", "코스닥", IndexCategory.DOMESTIC_INDEX)
        val KOSPI200 = IndexCode("2001", "코스피200", IndexCategory.DOMESTIC_INDEX)

        // ── 해외 지수 2건 (HHDFS00000300 SYMB raw · ADR 0100 §3-4 NAS 단일) ────
        val NASDAQ = IndexCode(".IXIC", "나스닥 종합", IndexCategory.OVERSEAS_INDEX, exchange = "NAS")
        val SP500 = IndexCode(".SPX", "S&P 500", IndexCategory.OVERSEAS_INDEX, exchange = "NAS")

        /**
         * 1차 KOSPI 21 업종 카탈로그 (ADR 0100 §1-1 — KRX 21 업종 LOCK).
         *
         * raw 출처 (FID_INPUT_ISCD 카탈로그):
         *   https://github.com/koreainvestment/open-trading-api (FHPUP02140000 example)
         *   + KIS 공식 doc portal 업종 코드표
         *
         * 본 21 업종은 KOSPI 표준 분류 (KIS FID_MRKT_CLS_CODE=K · FID_BLNG_CLS_CODE=0).
         * KOSDAQ 별도 분류는 post-MVP 확장 (ADR 0100 §8 후속 의제).
         */
        val KOSPI_SECTORS: List<IndexCode> =
            listOf(
                IndexCode("0002", "음식료품", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0003", "섬유의복", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0004", "종이목재", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0005", "화학", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0006", "의약품", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0007", "비금속광물", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0008", "철강금속", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0009", "기계", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0010", "전기전자", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0011", "의료정밀", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0012", "운수장비", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0013", "유통업", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0014", "전기가스업", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0015", "건설업", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0016", "운수창고", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0017", "통신업", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0018", "금융업", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0019", "은행", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0020", "증권", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0021", "보험", IndexCategory.DOMESTIC_SECTOR),
                IndexCode("0022", "서비스업", IndexCategory.DOMESTIC_SECTOR),
            )

        /**
         * cron 1분 호출 매트릭스 LOCK (ADR 0100 §2-4 — 26 req/min 안전 검증).
         *
         * 국내 3 지수 + 21 업종 + 해외 2 지수 = 26 req/min · 0.43 req/s · 4.3% 한도 점유
         */
        val ALL_FOR_MINUTE_SYNC: List<IndexCode> =
            buildList {
                add(KOSPI)
                add(KOSDAQ)
                add(KOSPI200)
                addAll(KOSPI_SECTORS)
                add(NASDAQ)
                add(SP500)
            }

        /**
         * 국내 16:30 KST 일봉 backfill 대상 (3 지수 + 21 업종 = 24 endpoint).
         */
        val DOMESTIC_FOR_DAILY: List<IndexCode> =
            buildList {
                add(KOSPI)
                add(KOSDAQ)
                add(KOSPI200)
                addAll(KOSPI_SECTORS)
            }

        /**
         * 해외 06:00 KST 일봉 backfill 대상 (2 지수).
         */
        val OVERSEAS_FOR_DAILY: List<IndexCode> = listOf(NASDAQ, SP500)
    }
}
