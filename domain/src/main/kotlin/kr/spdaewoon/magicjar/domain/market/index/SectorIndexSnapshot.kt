package kr.spdaewoon.magicjar.domain.market.index

import java.math.BigDecimal
import java.time.Instant

/**
 * KRX 21 섹터 1분 snapshot 도메인 모델 (ADR 0100 §2-7 V48 DDL 정합).
 *
 * 1분 cron(MarketIndexSyncJob) 발사 결과 영속.
 * 섹터 ranking 계산용 input — backend-core R1 SectorRankingComposer 흡수.
 *
 * @property sectorCode KIS 업종 코드 (예: "0018" 금융업) — FID_INPUT_ISCD 또는
 *                      FHPUP02140000 output 항목 코드 raw
 * @property sectorName 사용자 노출 한국어명 (IndexCode.KOSPI_SECTORS 카탈로그 정합)
 * @property ts 1분 단위 snapshot 시각 (KST)
 * @property price 섹터 지수 현재가
 * @property changeRate 전일 대비 % (BSTP_NMIX_PRDY_CTRT)
 * @property tradeValue 거래대금 (원) — KIS output `acml_tr_pbmn` 등. nullable
 * @property rawPayload KIS raw response JSON
 */
data class SectorIndexSnapshot(
    val sectorCode: String,
    val sectorName: String,
    val ts: Instant,
    val price: BigDecimal,
    val changeRate: BigDecimal,
    val tradeValue: BigDecimal? = null,
    val rawPayload: String? = null,
) {
    init {
        require(sectorCode.isNotBlank()) { "섹터 코드 빈 문자열 금지" }
        require(sectorName.isNotBlank()) { "섹터 이름 빈 문자열 금지" }
        require(price >= BigDecimal.ZERO) { "섹터 지수 가격 음수 금지: code=$sectorCode price=$price" }
        tradeValue?.let { require(it >= BigDecimal.ZERO) { "거래대금 음수 금지: code=$sectorCode value=$it" } }
    }
}
