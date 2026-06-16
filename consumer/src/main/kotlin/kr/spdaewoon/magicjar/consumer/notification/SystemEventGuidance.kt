package kr.spdaewoon.magicjar.consumer.notification

import kr.spdaewoon.magicjar.domain.event.SystemEventCategory

/**
 * SystemEvent → 사람어 운영 가이드 1줄 매핑 (2026-06-04 점검 사이클 — readiness P2 알림 친절도).
 *
 * ### 목적
 * SystemEvent Slack 알림은 기본적으로 `[severity] category/action actor=... detail=...` 기술 1줄이라
 * 운영자(사용자)가 "어느 모듈에서 왜 발생했고 무엇을 해야 하는지"를 즉시 알기 어렵다.
 * 본 매핑은 자주 발생하는 운영 이벤트(rate-limit·백필)에 **모듈 · 원인 추정 · 조치 가이드** 1줄을
 * add-only로 부착한다. 발송 경로·priority·rate-limit 라우팅은 일절 변경하지 않는다.
 *
 * ### 진보 페르소나 lens (DRY · 확장성)
 * - action 문자열 → 가이드의 단일 매핑 테이블([GUIDE]) — 신규 이벤트 추가 시 한 줄 row만 add.
 * - 미등록 action은 null 반환 → 호출자(SystemEventNotificationDispatcher.buildReason)가 기술 1줄만 사용(graceful).
 * - prefix 매칭(CANDLE_BACKFILL_*)으로 backfill 계열을 한 분기로 묶어 보일러플레이트 제거.
 *
 * // AI 호출 0 — 운영 알림 정적 매핑 (룰 5 정합).
 */
object SystemEventGuidance {
    /** action 정확 일치 매핑 (가장 구체적 우선). */
    private val EXACT_GUIDE: Map<String, String> =
        mapOf(
            "KIS_TPS_EXCEEDED" to
                "ℹ️ 모듈: KIS rate limiter · 원인 추정: KIS OpenAPI 초당 호출 한도(TPS) 초과 — 백필/조회 폴링 부하 가능성 · " +
                "조치: 5분 윈도 건수(detail.windowCount)로 집계된 신호입니다. WARN으로 도달했다면 한 윈도에 임계 이상 발생한 burst이므로 " +
                "백필 throttle 또는 watchlist 종목 수를 점검하세요. (산발 1~4건은 INFO로 격하되어 알림센터에만 기록됩니다.)",
            "CANDLE_BACKFILL_UNRECOVERABLE" to
                "🛠 모듈: 캔들 백필 워커 · 원인 추정: 재시도 한도 소진 후에도 KIS 응답 누락/오류로 해당 종목 백필 복구 실패 · " +
                "조치: 종목 코드와 detail의 사유를 확인하세요. KIS rate limit이면 잠시 후 자동 재시도, 지속되면 수동 재수집(관리자 백필)을 검토하세요.",
            "CANDLE_BACKFILL_KILLSWITCH_SKIPPED" to
                "🛡 모듈: 캔들 백필 워커 · 원인 추정: KillSwitch ON 상태라 백필을 건너뜀(자본 path 보호) · " +
                "조치: 시세/캔들 수집 영향만 있고 주문에는 영향 없습니다. KillSwitch OFF 후 자동 재개됩니다.",
            "CANDLE_BACKFILL_ROLLBACK" to
                "↩️ 모듈: 캔들 백필 워커 · 원인 추정: 백필 도중 정합성 가드 위반으로 해당 구간 적재를 롤백 · " +
                "조치: 데이터 손상 방지를 위한 안전 롤백입니다. detail의 구간을 확인하고 필요 시 재수집하세요.",
        )

    /**
     * action prefix 매핑 (계열 묶음 — EXACT_GUIDE 미스 시 fallback).
     *
     * pair.first = prefix, pair.second = 가이드. 순서대로 첫 매칭을 사용.
     */
    private val PREFIX_GUIDE: List<Pair<String, String>> =
        listOf(
            "CANDLE_BACKFILL_FAIL" to
                "⚠️ 모듈: 캔들 백필 워커 · 원인 추정: 백필 1회 시도 실패(주로 KIS rate limit 또는 일시 응답 오류) · " +
                "조치: 워커가 자동 재시도합니다. 반복되면 KIS rate limit 또는 종목 거래정지/상장폐지 여부를 확인하세요.",
            "CANDLE_BACKFILL_SKIPPED" to
                "ℹ️ 모듈: 캔들 백필 워커 · 원인 추정: throttle/중복/장 시간 등으로 백필을 의도적으로 건너뜀 · 조치: 정상 동작입니다. 조치 불요.",
        )

    /**
     * 카테고리·action 기준 운영 가이드 1줄 반환. 미등록이면 null (graceful).
     */
    fun guideFor(
        category: SystemEventCategory,
        action: String,
    ): String? {
        EXACT_GUIDE[action]?.let { return it }
        PREFIX_GUIDE.firstOrNull { (prefix, _) -> action.startsWith(prefix) }?.let { return it.second }
        // KIS_RATELIMIT 카테고리의 미등록 action도 일반 가이드로 cover (확장성).
        if (category == SystemEventCategory.KIS_RATELIMIT) {
            return "ℹ️ 모듈: KIS rate limiter · 원인 추정: KIS OpenAPI 호출 한도 관련 신호 · 조치: 단발이면 자동 backoff로 회복, 반복되면 호출 빈도를 점검하세요."
        }
        return null
    }
}
