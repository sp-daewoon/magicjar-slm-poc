package kr.spdaewoon.magicjar.consumer.infrastructure.kis

/**
 * KIS 종목 마스터 raw 파싱 결과 DTO.
 *
 * KIS-first 출처:
 *   KOSPI: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kospi_code_mst.py
 *   KOSDAQ: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kosdaq_code_mst.py
 *   docs/operations/kis-api-reference.md §6
 *
 * 파일 형식: fixed-width text (EUC-KR 인코딩)
 * KOSPI 고정 폭 레이아웃:
 *   [0..8]   단축코드 (9자)
 *   [9]      공백
 *   [10..21] 표준코드 ISIN (12자)
 *   [22]     공백
 *   [23..62] 한글명 (40자, EUC-KR)
 *   [63..102] 영문명 (40자)
 *   ...
 * KOSDAQ 고정 폭 레이아웃도 유사 구조 (동일 파싱 로직 사용).
 *
 * zip 다운로드 URL:
 *   KOSPI: https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip
 *   KOSDAQ: https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip
 */
data class KisInstrumentRaw(
    val code: String,
    val isin: String?,
    val name: String,
    val nameEn: String?,
    val market: String,
    val type: String,
    val sector: String?,
    val listedAt: String?,
)
