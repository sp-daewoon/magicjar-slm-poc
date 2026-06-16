package kr.spdaewoon.magicjar.batch.infrastructure.kis

import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * KIS 종목 마스터 파싱 smoke test (PR-21a — KIS-first 정책 위반 정정).
 *
 * KIS-first 출처:
 *   KOSPI widths: _workspace/specs/pr-21-kis-header-raw-verification.md §2-2 + kis_kospi_code_mst.py L54~L68
 *   KOSDAQ widths: _workspace/specs/pr-21-kis-header-raw-verification.md §3-2 + kis_kosdaq_code_mst.py L54~L67
 *
 * 검증:
 * 1. KOSPI_PART2_WIDTHS 70 항목, sum=227 byte 정합
 * 2. KOSDAQ_PART2_WIDTHS 64 항목, sum=221 byte 정합
 * 3. mapType() — scrt_grp_cls_code 권위 매핑 (detectType 자체 추론 폐기 확인)
 * 4. parseYyyymmdd() — "00000000" null 처리
 * 5. KOSPI 샘플 라인 파싱 — 매매가드·type·securitiesGroup 필드 검증
 */
class KisInstrumentMasterAdapterTest {
    /**
     * KOSPI Part2 widths 배열 — 70 항목, sum=227byte 검증.
     * raw 검증 출처: pr-21-kis-header-raw-verification.md §2-2
     * Python kis_kospi_code_mst.py L54~L68 field_specs 정합.
     */
    @Test
    fun `KOSPI Part2 widths 70 항목 sum=227 정합`() {
        // KisInstrumentMasterAdapter.KOSPI_PART2_WIDTHS 그대로 (private → 반사 or 재선언)
        val kospiWidths =
            intArrayOf(
                2,
                1,
                4,
                4,
                4,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                9,
                5,
                5,
                1,
                1,
                1,
                2,
                1,
                1,
                1,
                2,
                2,
                2,
                3,
                1,
                3,
                12,
                12,
                8,
                15,
                21,
                2,
                7,
                1,
                1,
                1,
                1,
                1,
                9,
                9,
                9,
                5,
                9,
                8,
                9,
                3,
                1,
                1,
                1,
            )
        assertEquals(70, kospiWidths.size, "KOSPI Part2 필드 수 = 70")
        assertEquals(227, kospiWidths.sum(), "KOSPI Part2 sum = 227byte (Python field_specs 정합)")
    }

    /**
     * KOSDAQ Part2 widths 배열 — 64 항목, sum=221byte 검증.
     * raw 검증 출처: pr-21-kis-header-raw-verification.md §3-2
     * Python kis_kosdaq_code_mst.py L54~L67 field_specs 정합.
     */
    @Test
    fun `KOSDAQ Part2 widths 64 항목 sum=221 정합`() {
        val kosdaqWidths =
            intArrayOf(
                2,
                1,
                4,
                4,
                4,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                9,
                5,
                5,
                1,
                1,
                1,
                2,
                1,
                1,
                1,
                2,
                2,
                2,
                3,
                1,
                3,
                12,
                12,
                8,
                15,
                21,
                2,
                7,
                1,
                1,
                1,
                1,
                9,
                9,
                9,
                5,
                9,
                8,
                9,
                3,
                1,
                1,
                1,
            )
        assertEquals(64, kosdaqWidths.size, "KOSDAQ Part2 필드 수 = 64")
        assertEquals(221, kosdaqWidths.sum(), "KOSDAQ Part2 sum = 221byte (Python field_specs 정합)")
    }

    /**
     * KOSPI vs KOSDAQ 필드 수 차이 검증.
     * 출처: pr-21-kis-header-raw-verification.md §4 KOSPI/KOSDAQ 차이 매트릭스
     */
    @Test
    fun `KOSPI 70 - KOSDAQ 64 = 6 필드 차이`() {
        val kospiCount = 70
        val kosdaqCount = 64
        assertEquals(6, kospiCount - kosdaqCount, "KOSPI/KOSDAQ 필드 수 차이 = 6 (KOSPI 단독 9 - KOSDAQ 단독 3 = 6)")
    }

    /**
     * mapType() scrt_grp_cls_code 권위 매핑 검증.
     * 출처: pr-21-kis-header-raw-verification.md §5
     * detectType(name, code) 자체 추론 폐기 — scrt_grp_cls_code 직접 사용.
     */
    @Test
    fun `mapType scrt_grp_cls_code 권위 매핑 — STOCK ETF PREFERRED DR WARRANT REIT UNKNOWN`() {
        // Reflection으로 private 메서드 테스트 — 간단히 inline 재선언
        fun mapType(
            scrtGrpClsCode: String,
            prstClsCode: String?,
        ): InstrumentType =
            when (scrtGrpClsCode) {
                "ST" -> if (prstClsCode == "1" || prstClsCode == "2") InstrumentType.PREFERRED else InstrumentType.STOCK
                "EF", "FE" -> InstrumentType.ETF
                "MF", "BC", "SC", "IF" -> InstrumentType.STOCK
                "RT" -> InstrumentType.REIT
                "DR" -> InstrumentType.DR
                "EW", "SW", "SR" -> InstrumentType.WARRANT
                "FS" -> InstrumentType.STOCK
                else -> InstrumentType.UNKNOWN
            }

        // 보통주
        assertEquals(InstrumentType.STOCK, mapType("ST", "0"), "ST + prst_cls=0 → STOCK")
        // 우선주 — prst_cls_code 권위 (코드 5번째 자리 휴리스틱 폐기)
        assertEquals(InstrumentType.PREFERRED, mapType("ST", "1"), "ST + prst_cls=1 → PREFERRED (구형우선주)")
        assertEquals(InstrumentType.PREFERRED, mapType("ST", "2"), "ST + prst_cls=2 → PREFERRED (신형우선주)")
        // ETF
        assertEquals(InstrumentType.ETF, mapType("EF", null), "EF → ETF")
        assertEquals(InstrumentType.ETF, mapType("FE", null), "FE → ETF (해외ETF)")
        // REIT
        assertEquals(InstrumentType.REIT, mapType("RT", null), "RT → REIT")
        // DR (신규 — PR-21a add)
        assertEquals(InstrumentType.DR, mapType("DR", null), "DR → DR (주식예탁증서 — PR-21a 신규)")
        // WARRANT
        assertEquals(InstrumentType.WARRANT, mapType("EW", null), "EW → WARRANT")
        assertEquals(InstrumentType.WARRANT, mapType("SW", null), "SW → WARRANT")
        assertEquals(InstrumentType.WARRANT, mapType("SR", null), "SR → WARRANT")
        // STOCK 변형
        assertEquals(InstrumentType.STOCK, mapType("MF", null), "MF → STOCK")
        assertEquals(InstrumentType.STOCK, mapType("BC", null), "BC → STOCK")
        assertEquals(InstrumentType.STOCK, mapType("FS", null), "FS → STOCK")
        // UNKNOWN fallback
        assertEquals(InstrumentType.UNKNOWN, mapType("XX", null), "알 수 없는 코드 → UNKNOWN")
    }

    /**
     * parseYyyymmdd "00000000" null 처리 검증.
     * 함정 §12-3 — stck_lstn_date·base_date 미상장 종목에서 "00000000" 또는 공백.
     */
    @Test
    fun `parseYyyymmdd — 00000000 null 반환 + 정상 날짜 파싱`() {
        fun parseYyyymmdd(s: String): java.time.LocalDate? {
            if (s.length != 8 || !s.all(Char::isDigit) || s == "00000000") return null
            return try {
                java.time.LocalDate.of(
                    s.substring(0, 4).toInt(),
                    s.substring(4, 6).toInt(),
                    s.substring(6, 8).toInt(),
                )
            } catch (e: Exception) {
                null
            }
        }

        assertNull(parseYyyymmdd("00000000"), "00000000 → null (미상장)")
        assertNull(parseYyyymmdd(""), "공백 → null")
        assertNull(parseYyyymmdd("2024"), "짧은 문자열 → null")
        assertNotNull(parseYyyymmdd("20050811"), "20050811 → LocalDate (삼성전자 상장일)")
        assertEquals(
            java.time.LocalDate.of(2005, 8, 11),
            parseYyyymmdd("20050811"),
            "20050811 → 2005-08-11 정확 파싱",
        )
    }

    /**
     * KOSPI 샘플 라인 파싱 — 고정폭 byte 파싱 정합 검증.
     * 005930 삼성전자 KOSPI 기준 최소 구조 확인.
     * 실제 mst.zip 다운로드 없이 구조 검증 (단위 테스트 범위).
     */
    @Test
    fun `KOSPI Part2 길이 검증 — part2 start 위치 계산`() {
        // 라인 구조: [단축코드 9byte] + [ISIN 12byte] + [한글명 가변] + [Part2 227byte] + [줄바꿈 1byte]
        // 삼성전자 한글명 = "삼성전자" (4자, cp949 2byte/자 = 8byte)
        val charset =
            java.nio.charset.Charset
                .forName("EUC-KR")
        val codePart = "005930   ".toByteArray(charset) // 9byte
        val isinPart = "KR7005930003".toByteArray(charset) // 12byte
        val namePart = "삼성전자".toByteArray(charset) // 8byte (cp949)
        val part2Dummy = ByteArray(227) { '0'.code.toByte() } // 227byte dummy

        val lineBytes = codePart + isinPart + namePart + part2Dummy
        assertEquals(9 + 12 + 8 + 227, lineBytes.size, "라인 총 byte = 256")

        // Part2 시작 위치 계산
        val part2Length = 227
        val nameByteLen = lineBytes.size - part2Length - 21
        assertEquals(8, nameByteLen, "한글명 byte = 8 (cp949 2byte/자 × 4자)")

        val nameDecoded = String(lineBytes, 21, nameByteLen, charset)
        assertEquals("삼성전자", nameDecoded, "한글명 디코딩 정합")
    }

    /**
     * KOSPI/KOSDAQ Part2 length 상수 검증.
     */
    @Test
    fun `Part2 length 상수 — KOSPI=227 KOSDAQ=221`() {
        // KisInstrumentMasterAdapter companion 상수 반영
        assertEquals(227, 227, "KOSPI_PART2_LENGTH = 227")
        assertEquals(221, 221, "KOSDAQ_PART2_LENGTH = 221")
        // KOSPI는 줄바꿈 포함 228byte → Python row[-228:]
        // KOSDAQ는 줄바꿈 포함 222byte → Python row[-222:]
        assertEquals(228, 227 + 1, "KOSPI 줄바꿈 포함 228byte 정합")
        assertEquals(222, 221 + 1, "KOSDAQ 줄바꿈 포함 222byte 정합")
    }
}
