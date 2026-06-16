package kr.spdaewoon.magicjar.api.infrastructure.instrument

import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.screener.ScreenerFilter
import kr.spdaewoon.magicjar.domain.screener.port.InstrumentLookupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * InstrumentLookupPort 구현 — T-IM1 baseline InstrumentJpaRepository reuse (ADR 0071 §2-2 B-1 + spec §2-3·§10).
 *
 * universe 후보는 `instrument` 테이블(T-IM1 baseline) 단일 출처.
 * KIS endpoint 호출 0 LOCK — 영속 데이터 reuse (사용자 메모리 feedback_kis_first_policy 정합).
 *
 * 필터 매트릭스 (ADR 0071 §2-2 B-1 LOCK):
 * - active=true + delistedAt IS NULL + tradingHalt=false + liquidation=false (생존자 편향 cover)
 * - market IN filter.markets (default KOSPI+KOSDAQ)
 * - type IN filter.types (default STOCK+ETF)
 * - marketCapPrev >= filter.marketCapMin (억 단위, null=미적용)
 * - prevVolume >= filter.avgVolumeMin (null=미적용, prevVolume 전일 거래량 근사 사용)
 *
 * ### AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5)
 * LLM provider import 0 — grep 검증 의무.
 *
 * ### 헌법 §6 정합
 * Repository interface 내부 body 가진 fun 0건 (InstrumentJpaRepository.findForScreener abstract LOCK).
 */
@Component
class InstrumentLookupAdapter(
    private val instrumentJpaRepository: InstrumentJpaRepository,
) : InstrumentLookupPort {
    private val log = LoggerFactory.getLogger(InstrumentLookupAdapter::class.java)

    /**
     * 필터 매트릭스 적용 universe 조회.
     * @param filter universe 필터 (default = KOSPI+KOSDAQ active STOCK+ETF)
     * @return active 종목 list (empty 가능 — instrument 미수집 환경)
     */
    @Transactional(readOnly = true)
    override fun findActive(filter: ScreenerFilter): List<Instrument> {
        val markets = filter.markets.map { it.name }
        val types = filter.types.map { it.name }

        val entities =
            instrumentJpaRepository.findForScreener(
                markets = markets,
                types = types,
                marketCapMin = filter.marketCapMin,
                avgVolumeMin = filter.avgVolumeMin,
            )

        log.debug(
            "InstrumentLookupAdapter.findActive: markets={} types={} marketCapMin={} avgVolumeMin={} → {} 건",
            markets,
            types,
            filter.marketCapMin,
            filter.avgVolumeMin,
            entities.size,
        )

        return entities.map { it.toDomain() }
    }

    // ── private helper ───────────────────────────────────────────────────

    private fun kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity.toDomain(): Instrument =
        Instrument(
            code = code,
            isin = isin,
            name = name,
            nameEn = nameEn,
            market = runCatching { InstrumentMarket.valueOf(market) }.getOrElse { InstrumentMarket.KOSPI },
            type = runCatching { InstrumentType.valueOf(type) }.getOrElse { InstrumentType.STOCK },
            sector = sector,
            listedAt =
                listedAt?.let {
                    kotlinx.datetime.LocalDate(it.year, it.monthValue, it.dayOfMonth)
                },
            delistedAt =
                delistedAt?.let {
                    kotlinx.datetime.LocalDate(it.year, it.monthValue, it.dayOfMonth)
                },
            active = active,
        )
}
