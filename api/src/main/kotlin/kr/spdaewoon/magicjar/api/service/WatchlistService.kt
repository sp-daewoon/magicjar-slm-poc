package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.controller.WatchlistAddRequest
import kr.spdaewoon.magicjar.api.controller.WatchlistEntryDto
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaEntity
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.WatchlistJpaEntity
import kr.spdaewoon.magicjar.api.persistence.WatchlistJpaRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 관심종목 비즈니스 로직 (ADR 0028·0040, spec §4 정합).
 * - 옵션 C 채택: clientside 6자리 숫자 검증 + raw code 저장 + instrument stub upsert
 * - Kafka watchlist.changed.v1 producer (consumer listener는 T-W2)
 * - user_id: user_account 첫 row 자동 resolve (1차 single-tenant 단순화)
 *
 * PR-21b 보강: list/add 응답 DTO에 KIS-first 신규 필드 채움.
 * - type·securitiesGroup·marketCapPrev·tradingHalt·managementStock·industryMedium·isKospi200·isKosdaq150
 * - instrument V5 sync 완료 전: 신규 필드 null/false graceful (기존 동작 변경 0)
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class WatchlistService(
    private val watchlistJpaRepository: WatchlistJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val userAccountJpaRepository: UserAccountJpaRepository,
    private val auditTrailJpaRepository: AuditTrailJpaRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    private val log = LoggerFactory.getLogger(WatchlistService::class.java)

    companion object {
        private const val AUDIT_ACTOR = "user"
        private const val WATCHLIST_CHANGED_TOPIC = KafkaTopics.System.WATCHLIST_CHANGED

        // watchlist 응답 허용 type — securities_group이 비어있는 시드/상장폐지/stub 종목의 보조 판별.
        // STOCK·PREFERRED는 보통주·우선주, UNKNOWN은 stub(sync 전 임시).
        private val WATCHLIST_ALLOWED_TYPES = setOf("STOCK", "PREFERRED", "UNKNOWN")
    }

    /**
     * 1차 single-tenant: user_account 첫 row의 user_id 자동 resolve.
     * multi-user 본격화 시 HTTP header X-User-Id 또는 OAuth2 세션으로 전환.
     */
    fun currentUserId(): String =
        userAccountJpaRepository
            .findAll(Sort.by(Sort.Direction.ASC, "createdAt"))
            .firstOrNull()
            ?.userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "셋업 완료 후 사용 가능합니다")

    /**
     * 관심종목 목록 조회 (priority 오름차순, created_at 내림차순).
     * PR-20 — instrument 테이블 join으로 name/market 채움 (사용자 가시성).
     * PR-21b — type·securitiesGroup·marketCapPrev·tradingHalt·managementStock·industryMedium 추가 노출.
     *
     * 응답 정책 (사용자 요청):
     * - 보통주+우선주만 응답 포함 (1차 KIS securities_group='ST').
     * - 시드/상장폐지/stub 등 securities_group이 비어있는 옛 종목은 type ∈ {STOCK, PREFERRED, UNKNOWN}으로 보조 판별
     *   (KIS-first sync 후 미보강된 시드 데이터 fallback).
     * - ETF·ETN·REIT·WARRANT·DR·외국주식·펀드는 응답 제외 (DB row는 보존).
     * - active=false (상장폐지/구코드)는 delisted=true 라벨로 노출 — 사용자가 직접 제거 결정.
     */
    @Transactional(readOnly = true)
    fun list(userId: String): List<WatchlistEntryDto> {
        val entries = watchlistJpaRepository.findByUserIdOrderByPriorityAscCreatedAtDesc(userId)
        if (entries.isEmpty()) return emptyList()
        val codes = entries.map { it.code }.toSet()
        val instrumentMap =
            instrumentJpaRepository
                .findAllById(codes)
                .associateBy { it.code }
        return entries.mapNotNull { entry ->
            val instrument = instrumentMap[entry.code] ?: return@mapNotNull null
            val isStockGroup = instrument.securitiesGroup == "ST"
            val isAllowedType = instrument.type in WATCHLIST_ALLOWED_TYPES
            if (!isStockGroup && !isAllowedType) return@mapNotNull null
            entry.toDto(instrument)
        }
    }

    /**
     * 관심종목 등록.
     * 1) instrument stub upsert (market='UNKNOWN', type='UNKNOWN' — T-IM1 진입 시 갱신)
     * 2) watchlist INSERT (UQ violation → 409 WATCHLIST_DUPLICATE)
     * 3) audit row INSERT
     * 4) Kafka watchlist.changed.v1 publish
     */
    @Transactional
    fun add(
        userId: String,
        request: WatchlistAddRequest,
    ): WatchlistEntryDto {
        val code = request.code

        // instrument stub upsert (FK 위반 방지 — §3-2-A)
        upsertInstrumentStub(code)

        val entity =
            WatchlistJpaEntity(
                userId = userId,
                code = code,
                priority = request.priority ?: 100,
            )
        val saved =
            try {
                watchlistJpaRepository.save(entity)
            } catch (e: DataIntegrityViolationException) {
                // UQ(user_id, code) 위반 → 409 (함정 4번)
                throw ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 종목입니다")
            }

        auditTrailJpaRepository.save(
            AuditTrailJpaEntity(
                actor = AUDIT_ACTOR,
                action = "WATCHLIST_ADDED",
                entityType = "watchlist",
                entityId = code,
                detailJson = """{"userId":"$userId","code":"$code"}""",
            ),
        )

        publishWatchlistChanged(userId, code, "ADDED")

        log.info("Watchlist 등록: user={} code={}", maskUserId(userId), code)
        // PR-20 — instrument 테이블 join으로 name/market 채워서 응답
        // PR-21b — 추가 메타 필드 채움 (V5 sync 전이면 null/false graceful)
        val instrument = instrumentJpaRepository.findById(code).orElse(null)
        return saved.toDto(instrument)
    }

    /**
     * 관심종목 삭제.
     * 삭제 후 audit + Kafka publish (consumer T-W2 unsubscribe 신호).
     */
    @Transactional
    fun remove(
        userId: String,
        code: String,
    ) {
        val deleted = watchlistJpaRepository.deleteByUserIdAndCode(userId, code)
        if (deleted == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "등록된 종목을 찾을 수 없습니다")
        }

        auditTrailJpaRepository.save(
            AuditTrailJpaEntity(
                actor = AUDIT_ACTOR,
                action = "WATCHLIST_REMOVED",
                entityType = "watchlist",
                entityId = code,
                detailJson = """{"userId":"$userId","code":"$code"}""",
            ),
        )

        publishWatchlistChanged(userId, code, "REMOVED")

        log.info("Watchlist 삭제: user={} code={}", maskUserId(userId), code)
    }

    // ── private helpers ─────────────────────────────────────────────────────

    /**
     * instrument stub row upsert (옵션 C — market/type='UNKNOWN', T-IM1에서 갱신).
     */
    private fun upsertInstrumentStub(code: String) {
        if (!instrumentJpaRepository.existsByCode(code)) {
            val stub =
                InstrumentJpaEntity(
                    code = code,
                    name = code,
                    market = "UNKNOWN",
                    type = "UNKNOWN",
                )
            runCatching { instrumentJpaRepository.save(stub) }
                .onFailure { e ->
                    // 동시 INSERT race condition 허용 — 이미 존재하면 무시
                    log.debug("instrument stub upsert race — code={}: {}", code, e.message)
                }
        }
    }

    /**
     * watchlist.changed.v1 Kafka 발행 (ADR 0028 §4-5 — T-W2 listener는 후속).
     */
    private fun publishWatchlistChanged(
        userId: String,
        code: String,
        action: String,
    ) {
        val payload =
            """{"userId":"$userId","code":"$code","action":"$action","occurredAt":"${Instant.now()}"}"""
        runCatching {
            kafkaTemplate.send(WATCHLIST_CHANGED_TOPIC, userId, payload)
        }.onFailure { e ->
            // Kafka 실패는 비치명적 — Watchlist 등록/삭제 자체는 성공 처리
            log.warn("Kafka publish 실패 (watchlist.changed.v1) — code={} action={}: {}", code, action, e.message)
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /**
     * WatchlistJpaEntity → WatchlistEntryDto 변환.
     * PR-21b: instrument entity의 KIS-first 신규 필드를 DTO에 채움.
     * instrument null 또는 V5 sync 전 (market='UNKNOWN'): 신규 필드 null/false graceful.
     */
    private fun WatchlistJpaEntity.toDto(instrument: InstrumentJpaEntity?): WatchlistEntryDto {
        val nameResolved = instrument?.name?.takeIf { instrument.market != "UNKNOWN" }
        val marketResolved = instrument?.market?.takeIf { it != "UNKNOWN" }
        return WatchlistEntryDto(
            id = id ?: 0L,
            userId = userId,
            code = code,
            name = nameResolved,
            market = marketResolved,
            priority = priority,
            createdAt = createdAt,
            // PR-21b 신규 필드 — instrument null 또는 V5 미적용 시 graceful
            type = instrument?.type?.takeIf { it != "UNKNOWN" },
            securitiesGroup = instrument?.securitiesGroup,
            marketCapPrev = instrument?.marketCapPrev,
            tradingHalt = instrument?.tradingHalt ?: false,
            managementStock = instrument?.managementStock ?: false,
            industryMedium = instrument?.industryMedium,
            isKospi200 = instrument?.isKospi200 ?: false,
            isKosdaq150 = instrument?.isKosdaq150 ?: false,
            // 상장폐지 라벨 — 사용자가 직접 제거 결정용
            delisted = instrument?.active == false,
        )
    }
}
