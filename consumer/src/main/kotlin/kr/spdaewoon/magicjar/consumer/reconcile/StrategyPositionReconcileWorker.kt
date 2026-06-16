package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.order.HoldingPositionIndex
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerExecutionStrategyPositionAdapter
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * strategy_position drift 주기 정합 워커 — ADR 0115 §P1-1 amend (2026-06-10 incident 재발 방지).
 *
 * ## 왜 별도 worker 인가 (incident 근본 — 부족/under-count 방향)
 * 체결 직후 drift 정합([ConsumerExecutionStrategyPositionAdapter.applyExecution] → reconcileDrift)은
 * **그 체결이 strategy_position 에 반영되는 경로가 살아 있을 때만** 작동한다. 그러나 2026-06-10 오전 incident 는
 * `StrategyPositionExecutionReplayService` 의 "row 기존재 → replay skip"(over-count 회피 의도)로
 * applyExecution 경로 자체가 우회됐고, 그 strategy_position(qty=0 vs KIS 3주)은 **다음 체결 전까지 영구 잔존**해
 * 시그널 54건이 BLOCKED 됐다.
 *
 * 본 워커는 [BalanceReconcileJob] 주기(시장 OPEN 5분 cron)에 편승해 — **신규 cron 신설 0**(ADR 0115 §2-4 audit:
 * 신규 마이그레이션·토픽·cron 0 원칙) — user 의 보유 종목을 훑어 체결 이벤트와 무관하게
 * strategy_position drift 를 회복한다. 초과는 [PositionDriftCorrection] 내림, 부족은 귀속 ledger 정정이 자동 작동.
 *
 * ## 순회 범위 union (2026-06-10 오후 over-count incident — 본 amend)
 * 초기 구현은 **KIS 권위 보유(position quantity>0) 종목만** 순회했다. 그러나 그 날 오후 재발한 incident 는
 * **반대 방향(over-count)**: AUTO SELL 체결(13:11) 직후 consumer 재배포 다운 → MOCK WS 체결통보 부재 →
 * 부팅 REST 백필이 order_execution·position(KIS 0주)은 복구했으나 strategy_position(3주) 차감이 누락돼
 * **유령 over-count(strategy 3 vs KIS 0)** 가 잔존했다. position 이 0 이므로 KIS 권위 보유 enumeration 에서
 * 그 종목이 영구 제외 → drift 가 자기 치유 경로를 영영 못 만나 매 1분 매도 재시도·알람 폭주.
 *
 * 해소: 순회 범위를 **position(quantity>0) ∪ strategy_position(quantity>0)** code 합집합으로 확장한다.
 * KIS 0 인 유령 종목도 [reconcileStrategyPositionDrift] 에 도달해 [PositionDriftCorrection] Adjust(초과 내림)가
 * 작동(qty 3→0, avgPrice·filledLots·executedExitStages reset). under-count(KIS 3 vs strategy 0) 종목은
 * 그 반대로 position 측에서 잡힌다 — 두 방향 모두 합집합 한 번으로 커버.
 *
 * ## 보수성 (자본 경로)
 * - DB 귀속 정렬만 — 주문 발행 0. KillSwitch·실주문 게이트 무영향.
 * - 정정 로직은 체결 직후 경로와 **동일 함수**([ConsumerExecutionStrategyPositionAdapter.reconcileStrategyPositionDrift]) —
 *   3중 게이트(귀속 명확·멱등·전체 정합 검산) 그대로. 모호하면 WARN-only.
 * - 종목별 try-catch — 한 종목 실패가 나머지 정합을 막지 않음.
 *
 * AI 호출 0 — 룰 기반 자본 흐름 정합 (ADR 0023 §2-4 정합).
 */
@Component
class StrategyPositionReconcileWorker(
    private val positionJpaRepository: PositionJpaRepository,
    private val strategyPositionJpaRepository: StrategyPositionJpaRepository,
    private val strategyPositionAdapter: ConsumerExecutionStrategyPositionAdapter,
    // ADR 0115 §2-P2-⑤ — drift 보정·rebuild 로 strategy_position 이 바뀌면 in-memory 손절 인덱스 동반 갱신
    //   (DB 직접 변경은 onFillRefresh 미발화 → 60초·재기동 전까지 stale 였음, G3). ObjectProvider 로 느슨 결합.
    private val holdingPositionIndexProvider: ObjectProvider<HoldingPositionIndex>,
) {
    private val log = LoggerFactory.getLogger(StrategyPositionReconcileWorker::class.java)

    /**
     * 단일 user 의 보유 종목(position ∪ strategy_position) 전체에 대해 strategy_position drift 정합 1회 수행.
     *
     * [BalanceReconcileJob.runForAllActiveUsers] 가 user 별 [BalanceReconcileService.reconcileUser] 직후 호출한다
     * (position 테이블이 KIS 잔고로 최신화된 직후 — strategy_position 정합의 권위 기준이 신선).
     *
     * 순회 범위는 **position(quantity>0) ∪ strategy_position(quantity>0)** code 합집합(distinct) — under-count(KIS만
     * 보유)와 over-count(strategy_position 만 잔존하는 유령) 두 방향을 한 번에 커버한다(클래스 doc 참조).
     *
     * @param userId 정합 대상 사용자.
     * @param modeName position.source 분기 (REAL/MOCK) — KIS 권위 잔고 조회 키. strategy_position 측은 mode 무관(user+code).
     * @return 정합 시도한 종목 수(에러 종목 포함). 로깅·테스트용.
     */
    fun reconcileForUser(
        userId: String,
        modeName: String,
    ): Int {
        val codes = collectReconcileCodes(userId, modeName)
        if (codes.isEmpty()) return 0

        var attempted = 0
        for (code in codes) {
            try {
                // 1) 증분 drift 보정(초과 내림·부족 ledger 귀속) — 현행 경로.
                strategyPositionAdapter.reconcileStrategyPositionDrift(userId, code, modeName)
                // 2) ★ rebuild(대안 B 1급) — order_execution 진실원 전량 재계산 + KIS 권위 교차검산(ADR 0115 §2-P2).
                //    토글 off 면 no-op. over-count clamp·교차검산 불일치면 미적용.
                strategyPositionAdapter.rebuildStrategyPositionFromLedger(userId, code, modeName)
                // 3) ⑤ in-memory 인덱스 동반 refresh — DB 직접 정정(drift 보정·rebuild upsert)은 onFillRefresh 미발화라
                //    60초·재기동 전까지 stale(G3) 였다. 정합 사이클이 만진 code 는 단일-code refresh 로 즉시 동기화.
                //    onFillRefresh 는 단일 code DB 재조회(경량)·멱등 — 변경 0 이어도 안전.
                refreshHoldingIndex(code)
                attempted++
            } catch (e: Exception) {
                log.error(
                    "StrategyPositionReconcileWorker — 종목 정합 실패: userId={} code={} mode={} err={}",
                    maskUserId(userId),
                    code,
                    modeName,
                    e.message,
                )
            }
        }
        log.debug(
            "StrategyPositionReconcileWorker — user 정합 완료: userId={} mode={} 종목={}",
            maskUserId(userId),
            modeName,
            attempted,
        )
        return attempted
    }

    /**
     * 정합 순회 대상 code 합집합 — KIS 권위 보유(position quantity>0) ∪ strategy_position 잔존(quantity>0).
     *
     * 두 조회는 각자 try-catch 로 격리 — 한쪽 실패(예: position 측 DB 일시 오류)가 나머지 한쪽 정합까지
     * 막지 않는다(보수적: 부분 집합으로라도 진행). 둘 다 실패하면 빈 집합 → 무동작.
     * `LinkedHashSet` 으로 삽입 순서 보존 + distinct(같은 code 가 양쪽에 있어도 1회만 정합).
     */
    private fun collectReconcileCodes(
        userId: String,
        modeName: String,
    ): Set<String> {
        val codes = LinkedHashSet<String>()
        try {
            positionJpaRepository
                .findByUserIdAndSourceAndQuantityGreaterThan(userId, modeName, 0)
                .forEach { codes.add(it.code) }
        } catch (e: Exception) {
            log.warn(
                "StrategyPositionReconcileWorker — KIS 권위 보유 조회 실패, position 측 skip: userId={} mode={} err={}",
                maskUserId(userId),
                modeName,
                e.message,
            )
        }
        try {
            strategyPositionJpaRepository
                .findDistinctCodesByUserIdAndQuantityPositive(userId)
                .forEach { codes.add(it) }
        } catch (e: Exception) {
            log.warn(
                "StrategyPositionReconcileWorker — strategy_position 잔존 조회 실패, 유령 over-count 보강 skip: userId={} err={}",
                maskUserId(userId),
                e.message,
            )
        }
        return codes
    }

    /**
     * ADR 0115 §2-P2-⑤ — 정합으로 strategy_position 이 바뀐 code 의 in-memory 손절 인덱스 동반 갱신(G3 stale 차단).
     *
     * [HoldingPositionIndex] 빈이 합류 안 됐으면(tick-watch 비활성) no-op. 갱신 실패는 정합 자체를 막지 않음(격리).
     */
    private fun refreshHoldingIndex(code: String) {
        val index = holdingPositionIndexProvider.getIfAvailable() ?: return
        try {
            index.onFillRefresh(code)
        } catch (e: Exception) {
            log.warn("StrategyPositionReconcileWorker — index refresh 실패(정합은 유지): code={} err={}", code, e.message)
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
