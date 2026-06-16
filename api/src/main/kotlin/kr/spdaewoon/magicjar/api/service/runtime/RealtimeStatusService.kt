package kr.spdaewoon.magicjar.api.service.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.media.Schema
import kr.spdaewoon.magicjar.api.controller.runtime.RealtimeStatusResponse
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.service.CandleHealthService
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * 관리자 실시간 구독 상태 조립 서비스 (read-only — Redis 스냅샷 + 종목명 + 캔들 신선도 합성).
 *
 * ## 데이터 흐름
 * 1. consumer가 5초 주기로 Redis `ops:realtime:status`에 발행한 스냅샷 JSON read.
 * 2. updatedAt 기반 stale 판정([STALE_THRESHOLD] 초과 시 stale=true — consumer 발행 정지·장애 신호).
 * 3. subscriptions code → instrument 테이블 종목명 join (배치 조회 1회).
 * 4. ACTIVE 전략 종목 + 현재 체결가(H0UNCNT0) 구독 종목 → 1m 캔들 신선도([CandleHealthService.assess]) 부착.
 *
 * 모든 조회는 read-only + fail-open(부분 실패 시 빈 값으로 graceful) — 관측 화면이 down되지 않도록.
 *
 * // AI 호출 0 — 관측 read-only (ADR 0023 §2-4)
 */
@Service
class RealtimeStatusService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val strategyJpaRepository: StrategyJpaRepository,
    private val candleHealthService: CandleHealthService,
) {
    private val log = LoggerFactory.getLogger(RealtimeStatusService::class.java)

    companion object {
        /** consumer RealtimeStatusSnapshotPublisher.REDIS_KEY 계약 (모듈 경계로 직접 import 불가 — 리터럴 LOCK). */
        val REDIS_KEY: String = RedisKeyRegistry.Ops.realtimeStatus()

        /** stale 판정 임계 — 스냅샷 updatedAt이 본 임계보다 오래되면 consumer 발행 정지로 간주. */
        private val STALE_THRESHOLD: Duration = Duration.ofSeconds(15)

        private const val FRESHNESS_INTERVAL = "1m"
        private const val UNIFIED_TICK_TR_ID = "H0UNCNT0"
    }

    fun getStatus(): RealtimeStatusResponse {
        val snapshot = readSnapshot()
        if (snapshot == null) {
            // consumer 미발행(다운·TTL 만료) — stale=true + 빈 상태로 관리자에게 부재를 명시 표시.
            return RealtimeStatusResponse(
                updatedAt = null,
                stale = true,
                ws = WsStatusDto(),
                slots = SlotStatusDto(),
                subscriptions = emptyList(),
                executionNotice = ExecutionNoticeDto(),
                candleFreshness = emptyList(),
            )
        }

        val updatedAt = parseInstant(snapshot.updatedAt)
        val stale = updatedAt == null || Duration.between(updatedAt, Instant.now()) > STALE_THRESHOLD

        // 종목명 배치 조회 1회 — 구독 code + 슬롯 drill-down(codesByTrId) code를 합쳐 단일 조회 유지.
        val codes =
            (
                snapshot.subscriptions.map { it.code } +
                    snapshot.slots.codesByTrId.values
                        .flatten()
            ).toSet()
        val nameByCode = resolveNames(codes)

        val subscriptions =
            snapshot.subscriptions.map {
                SubscriptionStatusDto(
                    trId = it.trId,
                    code = it.code,
                    codeName = nameByCode[it.code],
                    owners = it.owners,
                    since = it.sinceMs?.let { ms -> Instant.ofEpochMilli(ms).toString() },
                )
            }

        return RealtimeStatusResponse(
            updatedAt = snapshot.updatedAt,
            stale = stale,
            ws =
                WsStatusDto(
                    connected = snapshot.ws.connected,
                    sessionCid = snapshot.ws.sessionCid,
                    reconnects24h = snapshot.ws.reconnects24h,
                    lastConnectedAt = snapshot.ws.lastConnectedAtMs?.let { Instant.ofEpochMilli(it).toString() },
                ),
            slots =
                SlotStatusDto(
                    used = snapshot.slots.used,
                    max = snapshot.slots.max,
                    byTrId = snapshot.slots.byTrId,
                    channelsByTrId =
                        snapshot.slots.codesByTrId.mapValues { (_, channelCodes) ->
                            channelCodes.map { code -> SlotChannelDto(code = code, codeName = nameByCode[code]) }
                        },
                ),
            subscriptions = subscriptions,
            executionNotice =
                ExecutionNoticeDto(
                    subscribed = snapshot.executionNotice.subscribed,
                    mode = snapshot.executionNotice.mode,
                    trId = snapshot.executionNotice.trId,
                    userIdMasked = snapshot.executionNotice.userIdMasked,
                ),
            candleFreshness = resolveCandleFreshness(snapshot, nameByCode),
        )
    }

    // ── private helper (read-only · fail-open) ──────────────────────────────

    private fun readSnapshot(): RawSnapshot? {
        val json =
            try {
                redisTemplate.opsForValue().get(REDIS_KEY)
            } catch (e: Exception) {
                log.warn("ops:realtime:status Redis read 실패: err={}", e.message)
                null
            } ?: return null
        return runCatching { objectMapper.readValue(json, RawSnapshot::class.java) }
            .onFailure { log.warn("ops:realtime:status 파싱 실패: err={}", it.message) }
            .getOrNull()
    }

    private fun resolveNames(codes: Set<String>): Map<String, String> {
        if (codes.isEmpty()) return emptyMap()
        return try {
            instrumentJpaRepository.findAllById(codes).associate { it.code to it.name }
        } catch (e: Exception) {
            log.warn("instrument 종목명 join 실패(graceful): err={}", e.message)
            emptyMap()
        }
    }

    /**
     * ACTIVE 전략 종목 + 현재 체결가(H0UNCNT0) 구독 종목 → 1m 캔들 신선도.
     *
     * 전략 평가가 의존하는 종목과 현재 실시간 체결 tick으로 1m 캔들을 갱신해야 하는 종목을 함께 본다.
     * 보유 감시·상세 화면처럼 ACTIVE 전략 owner가 아니어도 H0UNCNT0에 물려 있으면 누락을 드러내야 한다.
     * H0UNASP0(호가)·H0UNMKO0(VI) 단독 구독은 1m 캔들 생성 경로가 아니므로 대상에서 제외한다.
     */
    private fun resolveCandleFreshness(
        snapshot: RawSnapshot,
        nameByCode: Map<String, String>,
    ): List<CandleFreshnessDto> {
        val activeCodes =
            try {
                strategyJpaRepository
                    .findAllByStatus("ACTIVE")
                    .flatMap { parseCodes(it.codes) }
                    .toSortedSet()
            } catch (e: Exception) {
                log.warn("ACTIVE 전략 종목 집계 실패(graceful): err={}", e.message)
                emptySet<String>()
            }
        val liveTickCodeCandidates =
            (
                snapshot.slots.codesByTrId[UNIFIED_TICK_TR_ID].orEmpty() +
                    snapshot.subscriptions
                        .filter { it.trId == UNIFIED_TICK_TR_ID }
                        .map { it.code }
            )
        val liveTickCodes = liveTickCodeCandidates.filter { it.isNotBlank() }.toSet()
        val targetCodes = (activeCodes + liveTickCodes).toSortedSet()
        if (targetCodes.isEmpty()) return emptyList()

        // 종목명 보강 — 구독 join에 없던 ACTIVE 전용 종목은 별도 조회.
        val missing = targetCodes.filter { it !in nameByCode }.toSet()
        val extraNames = if (missing.isEmpty()) emptyMap() else resolveNames(missing)

        return targetCodes.map { code ->
            val ageSeconds =
                try {
                    candleHealthService.assess(code, FRESHNESS_INTERVAL).staleSeconds
                } catch (e: Exception) {
                    log.debug("캔들 신선도 점검 실패: code={} err={}", code, e.message)
                    null
                }
            CandleFreshnessDto(
                code = code,
                codeName = nameByCode[code] ?: extraNames[code],
                interval = FRESHNESS_INTERVAL,
                ageSeconds = ageSeconds,
            )
        }
    }

    private fun parseCodes(codesJson: String?): List<String> =
        codesJson
            ?.let {
                runCatching {
                    objectMapper
                        .readValue(it, List::class.java)
                        .mapNotNull { item -> item?.toString()?.takeIf(String::isNotBlank) }
                }.getOrNull()
            }
            ?: emptyList()

    private fun parseInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

/**
 * consumer Redis `admin:realtime-status` 직렬화 페이로드 역직렬화 형 (api 내부 — 모듈 경계 정합).
 *
 * consumer `RealtimeStatusSnapshot`과 구조 LOCK. 모듈 의존 회피를 위해 형을 api에 별도 정의한다.
 * 미지의 필드는 [ObjectMapper] FAIL_ON_UNKNOWN_PROPERTIES=false 가정(api 전역 설정)으로 graceful 무시.
 */
internal data class RawSnapshot(
    val updatedAt: String? = null,
    val ws: RawWs = RawWs(),
    val slots: RawSlot = RawSlot(),
    val subscriptions: List<RawSubscription> = emptyList(),
    val executionNotice: RawExecutionNotice = RawExecutionNotice(),
)

internal data class RawWs(
    val connected: Boolean = false,
    val sessionCid: String? = null,
    val reconnects24h: Int = 0,
    val lastConnectedAtMs: Long? = null,
)

internal data class RawSlot(
    val used: Int = 0,
    val max: Int = 0,
    val byTrId: Map<String, Int> = emptyMap(),
    // tr_id별 점유 종목코드 목록 drill-down. default 필수 — 롤링 배포 호환(옛 consumer 페이로드엔 부재).
    val codesByTrId: Map<String, List<String>> = emptyMap(),
)

internal data class RawSubscription(
    val trId: String = "",
    val code: String = "",
    val owners: List<String> = emptyList(),
    val sinceMs: Long? = null,
)

internal data class RawExecutionNotice(
    val subscribed: Boolean = false,
    val mode: String = "MOCK",
    val trId: String = "H0STCNI9",
    val userIdMasked: String? = null,
)

// ── 응답 하위 DTO (RealtimeStatusResponse 구성) ──────────────────────────────

@Schema(description = "KIS WebSocket 세션 상태")
data class WsStatusDto(
    @field:Schema(description = "현재 WS 연결 여부", example = "true")
    val connected: Boolean = false,
    @field:Schema(description = "살아있는 세션 UUID (끊김 시 null)", example = "3f2a...", nullable = true)
    val sessionCid: String? = null,
    @field:Schema(description = "최근 24시간 재연결 횟수", example = "2")
    val reconnects24h: Int = 0,
    @field:Schema(description = "마지막 연결 수립 시각(ISO-8601, 미연결 이력 시 null)", nullable = true)
    val lastConnectedAt: String? = null,
)

@Schema(description = "WS 구독 슬롯 회계 (채널 = code × tr_id)")
data class SlotStatusDto(
    @field:Schema(description = "점유 채널 수", example = "37")
    val used: Int = 0,
    @field:Schema(description = "운영 상한(기본 40)", example = "40")
    val max: Int = 0,
    @field:Schema(description = "tr_id별 점유 채널 수", example = "{\"H0UNCNT0\":25,\"H0UNASP0\":12}")
    val byTrId: Map<String, Int> = emptyMap(),
    @field:Schema(
        description = "tr_id별 점유 종목 drill-down(코드+종목명) — 카운트가 실제 어느 종목인지 확인용",
        example = "{\"H0UNCNT0\":[{\"code\":\"005930\",\"codeName\":\"삼성전자\"}]}",
    )
    val channelsByTrId: Map<String, List<SlotChannelDto>> = emptyMap(),
)

@Schema(description = "슬롯 점유 채널 1건 (종목코드 + 종목명)")
data class SlotChannelDto(
    @field:Schema(description = "점유 종목코드", example = "005930")
    val code: String,
    @field:Schema(description = "종목명(instrument join, 미상 시 null)", example = "삼성전자", nullable = true)
    val codeName: String?,
)

@Schema(description = "토픽별 구독 항목 (통합 체결가 H0UNCNT0 · 통합 호가 H0UNASP0)")
data class SubscriptionStatusDto(
    @field:Schema(description = "KIS tr_id", example = "H0UNCNT0")
    val trId: String,
    @field:Schema(description = "종목코드", example = "005930")
    val code: String,
    @field:Schema(description = "종목명(instrument join, 미상 시 null)", example = "삼성전자", nullable = true)
    val codeName: String?,
    @field:Schema(
        description = "구독 요구 owner 목록(`<owner>:<bare ownerId>` 단일 prefix)",
        example = "[\"strategy:86\",\"detail:74683223\"]",
    )
    val owners: List<String>,
    @field:Schema(description = "최초 구독 시각(ISO-8601, 미상 시 null)", nullable = true)
    val since: String?,
)

@Schema(description = "체결통보(H0STCNI0/9) 구독 상태")
data class ExecutionNoticeDto(
    @field:Schema(description = "체결통보 자동 구독 활성 여부", example = "true")
    val subscribed: Boolean = false,
    @field:Schema(description = "운영 모드(REAL/MOCK — tr_id 분기 source)", example = "MOCK")
    val mode: String = "MOCK",
    @field:Schema(description = "현재 체결통보 tr_id(REAL=H0STCNI0 / MOCK=H0STCNI9)", example = "H0STCNI9")
    val trId: String = "H0STCNI9",
    @field:Schema(description = "체결통보 구독 HTS 사용자 ID(마스킹, 미설정 시 null)", example = "7468****", nullable = true)
    val userIdMasked: String? = null,
)

@Schema(description = "ACTIVE 전략 + 체결가(H0UNCNT0) 구독 종목 1m 캔들 신선도")
data class CandleFreshnessDto(
    @field:Schema(description = "종목코드", example = "005930")
    val code: String,
    @field:Schema(description = "종목명(미상 시 null)", example = "삼성전자", nullable = true)
    val codeName: String?,
    @field:Schema(description = "캔들 interval", example = "1m")
    val interval: String,
    @field:Schema(description = "마지막 캔들 경과 초(데이터 없으면 null)", example = "12", nullable = true)
    val ageSeconds: Long?,
)
