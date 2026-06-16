package kr.spdaewoon.magicjar.batch.job.instrument

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.batch.infrastructure.kis.KisInstrumentMasterAdapter
import kr.spdaewoon.magicjar.batch.infrastructure.kis.KisInstrumentRaw
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentAliasJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentStatusHistoryJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentStatusHistoryJpaRepository
import kr.spdaewoon.magicjar.domain.instrument.InstrumentStatus
import kr.spdaewoon.magicjar.domain.instrument.port.KisInstrumentMasterPort
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 종목 마스터 sync 잡 (T-IM1 PR-21a 보강 — ADR 0017·0039 정합).
 *
 * PR-21a 변경:
 *   - kisPort.fetchAll() (도메인 Instrument) → kisAdapter.fetchAllRaw() (KisInstrumentRaw 70+ 필드)
 *   - upsertInstrument() 기존 6 필드 → upsertInstrumentFull() 70+ 필드 전체
 *   - changed 판별 조건 보강 (매매가드·securities_group 변동 추적)
 *   - DELIST 처리는 변경 0 (ADR 0017 §2-2 — active=false + delisted_at만)
 *   - Kafka payload는 변경 0 (코드 list만 — 구독자 영향 0)
 *
 * 처리 흐름 (pr-11-im1-design.md §5-3 기반):
 * 1. Redis 동시 실행 락 (instrument:sync:running SET NX EX 600)
 * 2. KisInstrumentMasterAdapter.fetchAllRaw() (70+ 필드 파싱)
 * 3. diff (기존 active vs KIS 응답)
 * 4. DB upsert (INSERT NEW · UPDATE CHANGED · DELIST 폐지)
 * 5. instrument_alias upsert (KRX source 1건/종목)
 * 6. Kafka instrument.updated.v1 publish
 * 7. Redis invalidate (instrument:list:* · instrument:search:* · by-code:*)
 * 8. Redis instrument:sync:lastAt SET
 * 9. 락 해제
 *
 * 삭제 금지 (ADR 0017 §2-2): delisted_at UPDATE + active=false 만. DELETE 0건.
 */
@Component
class InstrumentSyncJob(
    private val kisPort: KisInstrumentMasterPort,
    private val kisAdapter: KisInstrumentMasterAdapter,
    private val instrumentRepo: BatchInstrumentJpaRepository,
    private val aliasRepo: BatchInstrumentAliasJpaRepository,
    private val statusHistoryRepo: BatchInstrumentStatusHistoryJpaRepository,
    private val redisTemplate: StringRedisTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(InstrumentSyncJob::class.java)

    companion object {
        private val SYNC_LOCK_KEY = RedisKeyRegistry.Instrument.syncRunning()
        private val SYNC_LAST_AT_KEY = RedisKeyRegistry.Instrument.syncLastAt()
        private const val SYNC_LOCK_TTL_SECONDS = 600L
        private const val KAFKA_TOPIC = KafkaTopics.Data.INSTRUMENT_UPDATED
    }

    /**
     * 종목 마스터 sync 실행.
     * @param cronTag 로깅용 cron 시각 태그 (예: "06:05", "boot")
     * @return InstrumentSyncResult — sync 결과
     */
    fun run(cronTag: String): InstrumentSyncResult {
        val startedAt = Instant.now()

        // 1. 동시 실행 락 획득
        val lockAcquired =
            redisTemplate
                .opsForValue()
                .setIfAbsent(SYNC_LOCK_KEY, "true", Duration.ofSeconds(SYNC_LOCK_TTL_SECONDS)) == true

        if (!lockAcquired) {
            log.warn("[instrument-sync] cron={} — 이미 실행 중 (락 존재) skip", cronTag)
            return InstrumentSyncResult(
                cronTag = cronTag,
                fetched = 0,
                inserted = 0,
                updated = 0,
                delisted = 0,
                failed = 0,
                startedAt = startedAt,
                finishedAt = Instant.now(),
            )
        }

        return try {
            doSync(cronTag, startedAt)
        } finally {
            redisTemplate.delete(SYNC_LOCK_KEY)
        }
    }

    private fun doSync(
        cronTag: String,
        startedAt: Instant,
    ): InstrumentSyncResult {
        // 2. KIS fetch — PR-21a: raw 70+ 필드 fetch
        val kisRaws: List<KisInstrumentRaw>
        try {
            kisRaws = kisAdapter.fetchAllRaw()
            log.info("[instrument-sync] cron={} KIS raw fetch 완료 — {} 건", cronTag, kisRaws.size)
        } catch (e: Exception) {
            log.warn(
                "[instrument-sync] cron={} KIS fetch 실패 — 시드 fallback 유지. err={}",
                cronTag,
                e.message,
            )
            return InstrumentSyncResult(
                cronTag = cronTag,
                fetched = 0,
                inserted = 0,
                updated = 0,
                delisted = 0,
                failed = 1,
                startedAt = startedAt,
                finishedAt = Instant.now(),
            )
        }

        // 3. diff
        val existingActive = instrumentRepo.findAllByActiveTrue().associateBy { it.code }
        val kisMap = kisRaws.associateBy { it.code }

        val newCodes = kisMap.keys - existingActive.keys
        val delistedCodes = existingActive.keys - kisMap.keys
        val existingCodes = existingActive.keys.intersect(kisMap.keys)

        val today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
        var inserted = 0
        var updated = 0
        var delisted = 0
        var failed = 0

        val changes = mutableListOf<Map<String, String>>()

        // status history detection 통계 (ADR 0090 Phase 1)
        var statusHistoryInserted = 0

        // 4-A. INSERT NEW
        for (code in newCodes) {
            val raw = kisMap[code] ?: continue
            try {
                upsertInstrumentFull(raw)
                upsertAlias(raw.code, raw.name)
                // ADR 0090 Phase 1 — 신규 종목 baseline status row 발행 (NORMAL or 초기 status)
                val initialStatus = deriveStatus(raw)
                insertStatusHistory(raw.code, initialStatus, "ADR 0090 신규 종목 baseline")
                statusHistoryInserted++
                inserted++
                changes.add(
                    mapOf(
                        "code" to raw.code,
                        "action" to "INSERT",
                        "market" to raw.market.name,
                        "name" to raw.name,
                    ),
                )
            } catch (e: Exception) {
                log.warn("[instrument-sync] INSERT 실패 code={} err={}", code, e.message)
                failed++
            }
        }

        // 4-B. UPDATE CHANGED
        for (code in existingCodes) {
            val raw = kisMap[code] ?: continue
            val existing = existingActive[code] ?: continue
            // PR-21a: 매매 가드·securities_group·분류 변동까지 changed 판별 보강
            val changed =
                existing.name != raw.name ||
                    existing.market != raw.market.name ||
                    existing.type != raw.type.name ||
                    existing.securitiesGroup != raw.securitiesGroup ||
                    existing.tradingHalt != raw.tradingHalt ||
                    existing.managementStock != raw.managementStock ||
                    existing.marketAlert != raw.marketAlert ||
                    existing.liquidation != raw.liquidation
            // ADR 0090 Phase 1 — status 변화 detection (trading_halt·liquidation 분기)
            val statusChanged =
                existing.tradingHalt != raw.tradingHalt ||
                    existing.liquidation != raw.liquidation
            if (changed) {
                try {
                    upsertInstrumentFull(raw)
                    upsertAlias(raw.code, raw.name)
                    if (statusChanged) {
                        // status 변화 → 기존 active row close + 신규 row insert (ADR 0090 §2-2)
                        val newStatus = deriveStatus(raw)
                        val reason =
                            "ADR 0090 status 변화 detection " +
                                "(tradingHalt=${existing.tradingHalt}->${raw.tradingHalt}, " +
                                "liquidation=${existing.liquidation}->${raw.liquidation})"
                        rotateStatusHistory(raw.code, newStatus, reason)
                        statusHistoryInserted++
                    }
                    updated++
                    changes.add(
                        mapOf(
                            "code" to raw.code,
                            "action" to "UPDATE",
                            "market" to raw.market.name,
                            "name" to raw.name,
                        ),
                    )
                } catch (e: Exception) {
                    log.warn("[instrument-sync] UPDATE 실패 code={} err={}", code, e.message)
                    failed++
                }
            }
        }

        // 4-C. DELIST (DELETE 금지 — ADR 0017 §2-2)
        for (code in delistedCodes) {
            val existing = existingActive[code] ?: continue
            try {
                instrumentRepo.markDelisted(code, today)
                // ADR 0090 Phase 1 — DELIST 시 status DELISTED 전이 row 발행
                rotateStatusHistory(code, InstrumentStatus.DELISTED, "ADR 0090 DELIST")
                statusHistoryInserted++
                delisted++
                changes.add(
                    mapOf(
                        "code" to code,
                        "action" to "DELIST",
                        "market" to existing.market,
                        "name" to existing.name,
                    ),
                )
            } catch (e: Exception) {
                log.warn("[instrument-sync] DELIST 실패 code={} err={}", code, e.message)
                failed++
            }
        }

        if (statusHistoryInserted > 0) {
            log.info("[instrument-sync] ADR 0090 status history rows inserted={}", statusHistoryInserted)
        }

        // 5. Kafka publish
        publishKafka(changes, startedAt)

        // 6. Redis invalidate
        invalidateRedis(changes.mapNotNull { it["code"] })

        // 7. lastAt SET
        redisTemplate.opsForValue().set(SYNC_LAST_AT_KEY, Instant.now().toString(), Duration.ofDays(1))

        val result =
            InstrumentSyncResult(
                cronTag = cronTag,
                fetched = kisRaws.size,
                inserted = inserted,
                updated = updated,
                delisted = delisted,
                failed = failed,
                startedAt = startedAt,
                finishedAt = Instant.now(),
            )
        log.info(result.toString())
        return result
    }

    /**
     * 70+ 필드 전체 upsert (PR-21a 보강).
     * DELIST 시는 markDelisted() 경로 — 이 메서드는 미호출.
     */
    @Transactional
    fun upsertInstrumentFull(raw: KisInstrumentRaw) {
        instrumentRepo.upsertFull(
            code = raw.code,
            isin = raw.isin,
            name = raw.name,
            nameEn = null, // KIS 마스터에서 영문명 미제공 (별도 source)
            market = raw.market.name,
            type = raw.type.name,
            sector = raw.industryMedium, // 업종 중분류를 sector로 사용
            listedAt = raw.listedAt,
            securitiesGroup = raw.securitiesGroup,
            tradingHalt = raw.tradingHalt,
            liquidation = raw.liquidation,
            managementStock = raw.managementStock,
            marketAlert = raw.marketAlert,
            marketAlertWarning = raw.marketAlertWarning,
            falseDisclosure = raw.falseDisclosure,
            backdoorListing = raw.backdoorListing,
            shortOverheated = raw.shortOverheated,
            abnormalRunup = raw.abnormalRunup,
            investmentCaution = raw.investmentCaution,
            exRightsType = raw.exRightsType,
            faceValueChange = raw.faceValueChange,
            capitalIncrease = raw.capitalIncrease,
            creditAble = raw.creditAble,
            creditDays = raw.creditDays,
            marginRate = raw.marginRate,
            shortOverheatedStatus = raw.shortOverheatedStatus,
            industryLarge = raw.industryLarge,
            industryMedium = raw.industryMedium,
            industrySmall = raw.industrySmall,
            kospi200Sector = raw.kospi200Sector,
            avlsScale = raw.avlsScale,
            isKospi100 = raw.isKospi100,
            isKospi50 = raw.isKospi50,
            isKospi200 = raw.isKospi200,
            isKosdaq150 = raw.isKosdaq150,
            isKrx = raw.isKrx,
            isKrx100 = raw.isKrx100,
            isKrx300 = raw.isKrx300,
            krxSectorCar = raw.krxSectorCar,
            krxSectorSemicon = raw.krxSectorSemicon,
            krxSectorBio = raw.krxSectorBio,
            krxSectorBank = raw.krxSectorBank,
            krxSectorEnergyChem = raw.krxSectorEnergyChem,
            krxSectorSteel = raw.krxSectorSteel,
            krxSectorMediaComm = raw.krxSectorMediaComm,
            krxSectorConstruction = raw.krxSectorConstruction,
            krxSectorSecurities = raw.krxSectorSecurities,
            krxSectorShip = raw.krxSectorShip,
            krxSectorInsurance = raw.krxSectorInsurance,
            krxSectorTransport = raw.krxSectorTransport,
            isManufacturing = raw.isManufacturing,
            isLowLiquidity = raw.isLowLiquidity,
            isGovernanceIndex = raw.isGovernanceIndex,
            isSpac = raw.isSpac,
            isVenture = raw.isVenture,
            isSriIndex = raw.isSriIndex,
            etpProductType = raw.etpProductType,
            isElwPublisher = raw.isElwPublisher,
            preferredStockType = raw.preferredStockType,
            groupCode = raw.groupCode,
            basePrice = raw.basePrice,
            tradingUnitNormal = raw.tradingUnitNormal,
            tradingUnitOvertime = raw.tradingUnitOvertime,
            prevVolume = raw.prevVolume,
            faceValue = raw.faceValue,
            listedShares = raw.listedShares,
            capital = raw.capital,
            settlementMonth = raw.settlementMonth,
            publicOfferingPrice = raw.publicOfferingPrice,
            marketCapPrev = raw.marketCapPrev,
            salesAmount = raw.salesAmount,
            operatingProfit = raw.operatingProfit,
            ordinaryProfit = raw.ordinaryProfit,
            netIncome = raw.netIncome,
            roe = raw.roe,
            financialBaseDate = raw.financialBaseDate,
            creditLimitExceeded = raw.creditLimitExceeded,
            collateralLendable = raw.collateralLendable,
            shortLendable = raw.shortLendable,
        )
    }

    @Transactional
    fun upsertAlias(
        code: String,
        name: String,
    ) {
        aliasRepo.upsert(code = code, alias = name, source = "KRX")
    }

    /**
     * ADR 0090 Phase 1 — KIS raw → InstrumentStatus enum 변환.
     *
     * 분기 룰 (chart-curator §12 4축 axis 2·3 정합):
     *   - liquidation=true → LIQUIDATION (정리매매 우선 — 상장폐지 직전 limited 거래)
     *   - tradingHalt=true → TRADING_HALT (거래정지)
     *   - 그 외 → NORMAL
     *
     * DELISTED 분기는 별도 path (delistedCodes detection) — 본 함수는 raw 정합 종목 한정.
     */
    internal fun deriveStatus(raw: KisInstrumentRaw): InstrumentStatus =
        when {
            raw.liquidation -> InstrumentStatus.LIQUIDATION
            raw.tradingHalt -> InstrumentStatus.TRADING_HALT
            else -> InstrumentStatus.NORMAL
        }

    /**
     * ADR 0090 Phase 1 — 신규 status history row INSERT (active row 1건 LOCK).
     *
     * caller 책임: 본 호출 직전 [rotateStatusHistory] 또는 신규 종목 baseline path여야 한다.
     */
    @Transactional
    fun insertStatusHistory(
        code: String,
        status: InstrumentStatus,
        reason: String,
    ) {
        val now = Instant.now()
        statusHistoryRepo.save(
            BatchInstrumentStatusHistoryJpaEntity(
                instrumentCode = code,
                status = status.name,
                effectiveFrom = now,
                effectiveUntil = null,
                reason = reason,
                recordedAt = now,
            ),
        )
    }

    /**
     * ADR 0090 Phase 1 — status 변화 detection 시 active row 교체.
     *
     * 알고리즘 (ADR 0090 §2-2 LOCK):
     *   1. 기존 active row(effective_until=null) effectiveUntil=now() UPDATE
     *   2. 신규 active row INSERT (effectiveFrom=now() · effectiveUntil=null)
     *
     * history immutability — 기존 row의 status·effective_from·reason 수정 0.
     */
    @Transactional
    fun rotateStatusHistory(
        code: String,
        newStatus: InstrumentStatus,
        reason: String,
    ) {
        val now = Instant.now()
        statusHistoryRepo.closeActiveStatus(code, now)
        statusHistoryRepo.save(
            BatchInstrumentStatusHistoryJpaEntity(
                instrumentCode = code,
                status = newStatus.name,
                effectiveFrom = now,
                effectiveUntil = null,
                reason = reason,
                recordedAt = now,
            ),
        )
    }

    private fun publishKafka(
        changes: List<Map<String, String>>,
        syncedAt: Instant,
    ) {
        if (changes.isEmpty()) return
        try {
            val byMarket = changes.groupBy { it["market"] ?: "UNKNOWN" }
            for ((market, marketChanges) in byMarket) {
                val payload =
                    mapOf(
                        "syncedAt" to syncedAt.toString(),
                        "changes" to marketChanges,
                    )
                kafkaTemplate.send(KAFKA_TOPIC, market, objectMapper.writeValueAsString(payload))
            }
        } catch (e: Exception) {
            log.warn("[instrument-sync] Kafka publish 실패 (비치명) — err={}", e.message)
        }
    }

    private fun invalidateRedis(changedCodes: List<String>) {
        try {
            val listKeys = redisTemplate.keys(RedisKeyRegistry.Instrument.listPattern()) ?: emptySet()
            if (listKeys.isNotEmpty()) redisTemplate.delete(listKeys)

            val searchKeys = redisTemplate.keys(RedisKeyRegistry.Instrument.searchPrefixPattern()) ?: emptySet()
            if (searchKeys.isNotEmpty()) redisTemplate.delete(searchKeys)

            for (code in changedCodes) {
                redisTemplate.delete(RedisKeyRegistry.Instrument.byCode(code))
            }
            log.debug("[instrument-sync] Redis invalidate 완료 — codes={}", changedCodes.size)
        } catch (e: Exception) {
            log.warn("[instrument-sync] Redis invalidate 실패 (비치명) — err={}", e.message)
        }
    }
}
