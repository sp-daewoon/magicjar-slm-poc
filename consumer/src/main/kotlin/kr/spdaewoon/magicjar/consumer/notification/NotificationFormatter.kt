package kr.spdaewoon.magicjar.consumer.notification

import kr.spdaewoon.magicjar.domain.notification.NotificationEventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Kafka 이벤트 → Slack 메시지 포맷터 (ADR 0044 §2-3 + spec §2-4 LOCK).
 *
 * 한국어 5필드 포맷:
 * 1. 시각 (KST)
 * 2. 이벤트 유형 (이모지 + 한국어 설명)
 * 3. 사용자 (마스킹 — userId 앞 4자만)
 * 4. 사유
 * 5. 즉시 액션 (P0만)
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
object NotificationFormatter {
    private val KST_ZONE = ZoneId.of("Asia/Seoul")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun format(
        userId: String,
        eventType: NotificationEventType,
        reason: String,
        meta: Map<String, String?>,
    ): String {
        val displayInstant = eventInstant(eventType, meta) ?: Instant.now()
        val kst = formatKst(displayInstant)
        val maskedUser = maskUserId(userId)
        val (emoji, typeLabel) = eventLabel(eventType)
        val action = immediateAction(eventType, meta)

        return buildString {
            appendLine("$emoji *[$typeLabel]* $kst KST")
            appendLine("사용자: $maskedUser")
            strategyLine(eventType, meta)?.let { appendLine(it) }
            appendContent(eventType, reason, meta)
            if (action.isNotBlank()) {
                appendLine("즉시 조치: $action")
            }
        }.trimEnd()
    }

    private fun maskUserId(userId: String): String =
        if (userId == "broadcast") {
            "시스템"
        } else if (userId.length > 4) {
            userId.take(4) + "****"
        } else {
            "****"
        }

    private fun eventLabel(eventType: NotificationEventType): Pair<String, String> =
        when (eventType) {
            NotificationEventType.ORDER_FILLED -> ":white_check_mark:" to "주문 체결"
            NotificationEventType.ORDER_REJECTED -> ":x:" to "주문 거절"
            NotificationEventType.ORDER_CANCELLED -> ":grey_exclamation:" to "주문 취소"
            NotificationEventType.RISK_VIOLATION -> ":warning:" to "리스크 위반"
            NotificationEventType.RECONCILE_DRIFT -> ":rotating_light:" to "포지션 불일치"
            NotificationEventType.KILLSWITCH_ENGAGED -> ":red_circle:" to "KillSwitch 자동 발동"
            NotificationEventType.RECONCILE_RUN -> ":mag:" to "잔고 점검"
            NotificationEventType.DLT_RECEIVED -> ":boom:" to "Kafka DLT 수신 (P0 자본 path risk)"
            NotificationEventType.SYSTEM_EVENT -> ":rotating_light:" to "시스템 이벤트"
        }

    private fun immediateAction(
        eventType: NotificationEventType,
        meta: Map<String, String?>,
    ): String =
        when (eventType) {
            NotificationEventType.RECONCILE_DRIFT ->
                // 2026-06-05 fix — drift 검출 ≠ KillSwitch 발동. KillSwitch 는 strike N회(기본 2회)·grace window
                // 규칙으로 자동 평가되며 미달이면 발동하지 않는다. 따라서 "OFF 토글 필요"를 단정하면 실상과 불일치.
                // 실제 발동 시에만 별도 KILLSWITCH_ENGAGED 알림이 오므로, 그 경우에 한해 수동 OFF 안내한다.
                "KIS HTS 에서 실제 보유 수량 확인 (자동 보정은 KIS 기준으로 적용됨). " +
                    "KillSwitch 는 반복 drift 시 자동 발동되며, 발동되면 별도 알림이 오니 그때 수동 OFF 필요."
            NotificationEventType.KILLSWITCH_ENGAGED -> "자동매매 일시 중단됨. KIS 잔고 확인 후 수동 재활성화 필요"
            NotificationEventType.RISK_VIOLATION ->
                meta["alertAction"]?.takeIf { it.isNotBlank() } ?: "리스크 한도 점검 후 필요 시 전략 비활성화"
            NotificationEventType.DLT_RECEIVED ->
                "DLT 토픽 record 검토 후 수동 replay 또는 운영 결함 fix. KIS 주문 영향 시 HTS 직접 확인."
            NotificationEventType.SYSTEM_EVENT -> "시스템 이벤트 상세와 Dozzle 로그를 함께 확인"
            else -> ""
        }

    private fun StringBuilder.appendContent(
        eventType: NotificationEventType,
        reason: String,
        meta: Map<String, String?>,
    ) {
        if (eventType == NotificationEventType.ORDER_FILLED) {
            appendLine("내용: $reason")
            eventInstant(eventType, meta)?.let { appendLine("체결시각: ${formatKst(it)} KST") }
            if (meta["fillSource"] == "REST_BACKFILL") {
                appendLine("반영: KIS REST backfill")
            }
            return
        }

        if (eventType == NotificationEventType.ORDER_REJECTED) {
            appendLine("내용: $reason")
            // 친절도 보강 (2026-06-04 점검 사이클): reject_reason 코드 → 사람어 1줄.
            // "Slack 알림만 봐도 왜 막혔는지" 추적 가능하게 — 코드(KILLSWITCH_ON 등)만으론 사용자가 사유를 모름.
            rejectReasonKorean(meta)?.let { appendLine("사유: $it") }
            return
        }

        if (eventType != NotificationEventType.RISK_VIOLATION) {
            appendLine("내용: $reason")
            return
        }

        val title = meta["alertTitle"]?.takeIf { it.isNotBlank() }
        val message = meta["alertMessage"]?.takeIf { it.isNotBlank() }
        val formula = meta["alertFormula"]?.takeIf { it.isNotBlank() }
        val setting = meta["alertSetting"]?.takeIf { it.isNotBlank() }
        val instrumentLabel = instrumentLabel(meta)

        if (title == null && message == null && formula == null && setting == null) {
            appendLine("내용: $reason")
            return
        }

        instrumentLabel?.let { appendLine("종목: $it") }
        appendLine("내용: ${title ?: "리스크 차단"}")
        appendLine("설명: ${withInstrumentLabel(message ?: reason, meta)}")
        withInstrumentLabel(formula, meta)?.lineSequence()?.forEachIndexed { index, line ->
            appendLine(if (index == 0) "수식: $line" else "      $line")
        }
        setting?.let { appendLine("설정: $it") }
    }

    /**
     * reject_reason 코드 → 사람어 1줄 (2026-06-04 점검 사이클 — readiness P2 알림 친절도).
     *
     * 주문 거절 Slack 알림은 코드(KILLSWITCH_ON·REAL_GATE_DENIED:cond·{rt_cd}:{msg}·KIS_5XX 등)만 노출돼
     * 사용자가 "왜 막혔는지"를 즉시 알기 어렵다. 자주 발생하는 사유를 사람어로 매핑한다.
     *
     * 출처: OrderRouterKafkaListener / OrderRejectedKafkaProducer payload rejectReason 포맷.
     * 미등록 코드는 null 반환 → "사유" 줄 생략(기술 내용 줄은 보존, graceful).
     */
    private fun rejectReasonKorean(meta: Map<String, String?>): String? {
        val raw =
            (meta["rejectReason"] ?: meta["reason"] ?: meta["errorMessage"] ?: meta["message"])
                ?.takeIf { it.isNotBlank() } ?: return null
        val code = raw.substringBefore(":").trim().uppercase()
        val detail = raw.substringAfter(":", "").trim().takeIf { it.isNotBlank() }
        return when {
            code == "KILLSWITCH_ON" || code == "KILL_SWITCH_ACTIVE" ->
                "KillSwitch가 켜져 있어 신규 주문이 차단됐습니다. 잔고 확인 후 KillSwitch를 끄면 재개됩니다."
            code == "REAL_GATE_DENIED" ->
                "실계좌 이중 게이트 미충족으로 차단됐습니다${detail?.let { " (조건: $it)" } ?: ""}. MAGICJAR_MODE=REAL + MAGICJAR_ALLOW_REAL=true 확인 필요."
            code == "DAILY_LOSS_LIMIT" ->
                "일별 손실 한도 도달로 주문이 차단됐습니다. 리스크 설정을 점검하세요."
            code == "POSITION_LIMIT" ->
                "포지션 한도 초과로 주문이 차단됐습니다. 보유 수량/전략 한도를 점검하세요."
            code == "PRICE_GUARD" ->
                "가격 가드(상하한/괴리율) 위반으로 주문이 차단됐습니다."
            code == "KIS_5XX" ->
                "KIS 서버 오류(5XX)로 주문이 실패했습니다. 일시적 장애일 수 있으니 잠시 후 재확인하세요."
            code == "KIS_API_ERROR" ->
                "KIS API 오류로 주문이 거절됐습니다${detail?.let { " ($it)" } ?: ""}."
            code.toIntOrNull() != null || code.matches(Regex("^[0-9]+$")) ->
                // {rt_cd}:{msg} 패턴 — KIS 응답 코드:메시지
                "KIS 거절 (코드 $code)${detail?.let { ": $it" } ?: ""}."
            else -> null
        }
    }

    private fun instrumentLabel(meta: Map<String, String?>): String? {
        val code = meta["code"]?.takeIf { it.isNotBlank() }
        val name = meta["instrumentName"]?.takeIf { it.isNotBlank() && it != code }
        return when {
            name != null && code != null -> "$name($code)"
            code != null -> code
            name != null -> name
            else -> null
        }
    }

    private fun withInstrumentLabel(
        value: String?,
        meta: Map<String, String?>,
    ): String? {
        val code = meta["code"]?.takeIf { it.isNotBlank() } ?: return value
        val label = instrumentLabel(meta) ?: return value
        return value?.replace("종목 $code", "종목 $label")
    }

    private fun strategyLine(
        eventType: NotificationEventType,
        meta: Map<String, String?>,
    ): String? {
        val strategyId = meta["strategyId"]?.toLongOrNull()
        val source = meta["source"]?.uppercase()
        // ADR 0115 §2-P2-⑥ — 발사 출처 enum 우선(자동청산/손절/외부 구분). 미발행(legacy)이면 source·strategyId fallback.
        val trigger = meta["orderTrigger"]?.uppercase()
        val manual = source == "MANUAL" || trigger == "MANUAL" || strategyId == 0L
        return when (eventType) {
            NotificationEventType.ORDER_FILLED,
            NotificationEventType.ORDER_REJECTED,
            NotificationEventType.ORDER_CANCELLED,
            -> {
                val strategyLabel = strategyDisplay(strategyId, meta)
                when (trigger) {
                    "STOP_LOSS" -> "주문출처: 자동 손절${strategyLabel?.let { " $it" } ?: ""}"
                    "VI_AUTO_EXIT" -> "주문출처: VI 자동청산${strategyLabel?.let { " $it" } ?: ""}"
                    "AUTO_EXIT" -> "주문출처: 자동 익절${strategyLabel?.let { " $it" } ?: ""}"
                    "PARTIAL_TAKE_PROFIT" -> "주문출처: 분할 익절${strategyLabel?.let { " $it" } ?: ""}"
                    "EXTERNAL" -> "주문출처: 외부 주문(HTS·MTS)"
                    "MANUAL" -> "주문출처: 수동 주문"
                    "STRATEGY_SIGNAL" -> strategyLabel?.let { "주문출처: 자동 전략 $it" }
                    // trigger 미발행(legacy) — 기존 분기 보존
                    else -> if (manual) "주문출처: 수동 주문" else strategyLabel?.let { "주문출처: 자동 전략 $it" }
                }
            }
            NotificationEventType.RISK_VIOLATION -> strategyDisplay(strategyId, meta)?.let { "전략: $it" }
            else -> null
        }
    }

    private fun strategyDisplay(
        strategyId: Long?,
        meta: Map<String, String?>,
    ): String? {
        if (strategyId == null || strategyId <= 0L) return null
        val name = meta["strategyName"]?.takeIf { it.isNotBlank() }
        return if (name != null) "$name (#$strategyId)" else "#$strategyId"
    }

    private fun eventInstant(
        eventType: NotificationEventType,
        meta: Map<String, String?>,
    ): Instant? =
        when (eventType) {
            NotificationEventType.ORDER_FILLED -> parseInstant(meta["executedAt"])
            NotificationEventType.RISK_VIOLATION -> parseInstant(meta["violatedAt"] ?: meta["blockedAt"] ?: meta["ts"])
            else -> null
        }

    private fun parseInstant(value: String?): Instant? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        raw.toLongOrNull()?.let { epoch ->
            return if (epoch > 100_000_000_000L) {
                Instant.ofEpochMilli(epoch)
            } else {
                Instant.ofEpochSecond(epoch)
            }
        }
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    private fun formatKst(instant: Instant): String = instant.atZone(KST_ZONE).format(DATE_FMT)
}
