package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.market.PriceMetrics

/**
 * KIS REST PER·PBR·시총 등 재무 메타 조회 포트 (PR-10b add-only).
 *
 * KIS-first 출처: inquire-price endpoint (FHKST01010100)
 * 동일 endpoint에서 현재가 + 메타 동시 반환 — 책임 분리로 별도 port.
 */
interface KisRestPriceMetricsPort {
    /**
     * KIS inquire-price에서 PER·PBR·EPS·BPS·시총·업종 등 메타 필드 조회.
     * @param userId 계정 사용자 ID (KIS 토큰 조회)
     * @param code   종목코드 6자리
     */
    fun fetchPriceMetrics(
        userId: String,
        code: String,
    ): PriceMetrics
}
