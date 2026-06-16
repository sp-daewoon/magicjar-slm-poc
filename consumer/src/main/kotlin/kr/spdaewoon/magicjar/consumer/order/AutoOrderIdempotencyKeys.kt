package kr.spdaewoon.magicjar.consumer.order

import java.security.MessageDigest

/**
 * 청산 안전망 intent 멱등 키 빌더 (ADR 0114 R2-C2b — Codex `AutoOrderIdempotencyKeys` 패턴 흡수).
 *
 * tick 손절 watcher([PositionStopLossTickWatcher])·체결 즉시 TP 선주문([AutoExitOrderCoordinator])·
 * tick 익절 watcher([PositionTakeProfitTickWatcher])가
 * 발행하는 OrderIntent의 `idempotencyKey`를 생성한다. **prefix가 의미를 갖는다**:
 * - `auto-sl:` — 손절 MARKET intent. open SELL 검사([PositionStopLossTickWatcher.hasOpenStopOrder])가 prefix로 식별.
 * - `auto-vi:` — VI 발동 MARKET intent. 전략별 opt-in 보유분만 대상.
 * - `auto-tp:` — 익절 LIMIT 선주문(체결 직후). 재발행(scale-in 평단 변동) 시 기존 TP를 prefix로 찾아 취소.
 * - `auto-tpt:` — 익절 MARKET tick 발사([PositionTakeProfitTickWatcher]). **KST 날짜 차원 포함**(아래 §F1 회피).
 *
 * OrderRouter 멱등 가드(`idem:order:{key}` Redis SETNX 60s — [OrderRouterKafkaListener])가 동일 키 재발행을
 * 흡수하므로, **동일 (사용자·전략·종목·수량·평단·체결시각)** 입력은 같은 키 → 이중 발화 멱등.
 *
 * ## Codex 대비 정합
 * Codex와 동일한 SHA-256 32-hex digest. 단 우리는 prefix별 의도를 KDoc으로 LOCK하고
 * 1m 시그널 경로(`{signalId}:{userId}`)와 키 공간이 겹치지 않음을 보장한다(prefix 격리).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 순수 유틸 (ADR 0023 §2-4)
 */
internal object AutoOrderIdempotencyKeys {
    /** 익절 LIMIT 선주문 키 — `auto-tp:<digest>`. parts: userId·strategyId·code·quantity·avgPrice·epoch. */
    fun takeProfit(vararg parts: Any?): String = shortKey(PREFIX_TAKE_PROFIT, parts.asList())

    /** 손절 MARKET 키 — `auto-sl:<digest>`. parts: userId·strategyId·code·quantity·epoch. */
    fun stopLoss(vararg parts: Any?): String = shortKey(PREFIX_STOP_LOSS, parts.asList())

    /** VI MARKET 자동청산 키 — `auto-vi:<digest>`. parts: userId·strategyId·code·quantity·epoch·viCode. */
    fun viAutoExit(vararg parts: Any?): String = shortKey(PREFIX_VI_AUTO_EXIT, parts.asList())

    /**
     * 익절 MARKET tick 발사 키 — `auto-tpt:<digest>`. parts: userId·strategyId·code·quantity·**kstDate(yyyyMMdd)**.
     *
     * ## 왜 KST 날짜 차원인가 (F1 고정 해시 함정 회피 — canary D2 hotfix B)
     * 손절 키(`auto-sl:`)는 (qty·updatedAtEpoch) signature 라 포지션 상태가 안 바뀌면 종일 같은 digest 다.
     * 익절도 같은 패턴이면, KIS 가 한 번 REJECTED(예: 일시 잔량 부족·장 시작 직후) 한 키가 OrderRouter
     * `idem:order:{key}` 또는 caller dedup 에 남아 **그날 내내 익절 재발사를 영구 차단**할 수 있다(어제 F1 evidence).
     * 익절은 가격이 익절가 이상으로 머무는 한 계속 발사 가능해야 하므로, 키에 **KST 당일 날짜**를 섞어
     * 하루 1회 발사로 한정하되 **날이 바뀌면(또는 상태 변동) 자연 재발사**가 되게 한다(영구 차단 0).
     * (stop-loss 키는 본 PR 에서 건드리지 않는다 — 별도 의제.)
     */
    fun takeProfitTick(vararg parts: Any?): String = shortKey(PREFIX_TAKE_PROFIT_TICK, parts.asList())

    const val PREFIX_TAKE_PROFIT: String = "auto-tp"
    const val PREFIX_STOP_LOSS: String = "auto-sl"
    const val PREFIX_VI_AUTO_EXIT: String = "auto-vi"
    const val PREFIX_TAKE_PROFIT_TICK: String = "auto-tpt"

    private fun shortKey(
        prefix: String,
        parts: List<Any?>,
    ): String {
        val raw = parts.joinToString("|") { it?.toString() ?: "" }
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32)
        return "$prefix:$digest"
    }
}
